---
name: maven-verifier
description: Verify changes in any Maven module of the monorepo (engine, micro-engine, runtime-catalog, sessions-management, integration-build-pipeline, integration-build-maven-plugin, parents, bom, checkstyle) by compiling and testing only the affected module. Use after editing Java sources, POMs, or resources under those modules, or when asked whether backend changes still build. Returns a verdict with the failing output; it does not fix anything.
tools: Bash, Read, Grep, Glob
---

# Maven module verifier

Verify a change in one Maven module and report what breaks. Build the smallest reactor
that can still catch the failure.

All commands run from the repository root and need `-Dgpg.skip=true` — GPG signing is
configured in the `parents/` POMs for publishing and fails locally without it.

Give Maven room: use a Bash `timeout` of at least 600000 ms. Test runs here have hit the
two-minute default repeatedly.

## 1. Determine the scope

Use the changed files the caller gives you, or derive them:

```bash
git diff --name-only HEAD
```

Map each path to its module — the top-level directory is the module name:

| Path prefix | Module | Note                                                                     |
|---|---|--------------------------------------------------------------------------|
| `engine/` | engine | Spring Boot                                                              |
| `micro-engine/` | micro-engine | Quarkus; slowest suite by far                                            |
| `runtime-catalog/` | runtime-catalog | ANTLR sources regenerate on build; depends on integration-build-pipeline |
| `sessions-management/` | sessions-management |                                                                          |
| `integration-build-pipeline/` | integration-build-pipeline | chain compilation; runtime-catalog and the Maven plugin consume it       |
| `integration-build-maven-plugin/` | integration-build-maven-plugin | depends on integration-build-pipeline                                    |
| `parents/` | parents | parent POMs; affect every module                                         |
| `bom/` | bom | lists module versions; no module depends on it                           |

Decide how much to build:

- **No Maven module touched** — report "nothing to verify" and stop.
- **Only `*.md` under a module** — stop the same way.
- **One module touched** — build that module alone, without `-am`. Its dependencies are
  already installed in `~/.m2`.
- **`integration-build-pipeline/` touched** — verify it together with its consumer:
  `-pl integration-build-pipeline,runtime-catalog`. Without it in the reactor, runtime-catalog
  compiles against the library version in `~/.m2`, not against the change.
- **`parents/` or `checkstyle/` touched** — these change every downstream module. Install
  them first, then verify the modules that matter to the caller:
  `mvn -q -pl parents/base,parents/spring,parents/quarkus,checkstyle install -Dgpg.skip=true`.
- **Several modules touched** — pass them together: `-pl engine,runtime-catalog`.

## 2. Run the check

One command covers compilation, Checkstyle, and tests:

```bash
mvn -q -pl runtime-catalog test -Dgpg.skip=true -nsu
```

Two things make this enough:

- **Checkstyle already runs.** The parent POMs bind `checkstyle:check` to the `compile`
  phase with `failOnViolation=true` and `maxAllowedViolations=0`. A separate
  `mvn checkstyle:check` run repeats work that `test` has already done.
- **`-nsu` skips snapshot update checks**, which saves a remote round-trip per dependency
  on a warm `~/.m2`.

`-q` does not silence the application itself. Spring and Quarkus log to stdout during
tests, so a passing module still prints hundreds of JSON log lines. Filter to the lines
that carry the verdict rather than returning the raw stream:

```bash
mvn -pl sessions-management test -Dgpg.skip=true -nsu 2>&1 \
  | grep -E 'Tests run|ERROR|FAIL|BUILD|checkstyle|Total time'
```

Keep the full output only for the failure you report, and trim it to the relevant frames.

Narrow it further when the change is narrow. For a single touched class, run its test
class alone:

```bash
mvn -q -pl runtime-catalog test -Dtest=ChainServiceTest -DfailIfNoTests=false -Dgpg.skip=true -nsu
```

Find the matching test by name before assuming none exists:

```bash
find runtime-catalog/src/test -name 'ChainService*Test.java'
```

Fall back to the module's full suite when a change reaches shared code — a mapper, an
entity, a Flyway migration, anything under `common/` — and say that you did.

With `-am`, `test` runs the suites of the upstream modules too. Scope it with
`-Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false`, which skips modules that have no
matching test.

For `micro-engine`, prefer `-Dtest=` scoping whenever the change allows it. Its tests
run on Quarkus and dominate the wall-clock time of any full-module run.

## 3. What not to run

- `mvn clean` — it discards `target/` and forces a full recompile. Use it only when a
  build failure looks stale, such as ANTLR or MapStruct sources that no longer match.
- `mvn install` — `test` stops before packaging and before touching `~/.m2`. An installed
  snapshot is shared with every other checkout and worktree on the machine, and another
  session then builds against your branch without knowing it.
- The whole aggregator (`mvn clean install` at the root) — minutes of work to check one
  module.
- `-am` when only the module's own sources changed. It pulls the parent POMs and
  `checkstyle` into the reactor for nothing.

## 4. Read failures correctly

Not every red build is a code problem:

- **401 or 403 on `com.netcracker.cloud` artifacts** — the feature branches
  (`feature/#173-core-adaptation`, `feature/#109-core-adaptation`, `feature/#33`) depend on
  private GitHub Packages. This is a missing PAT in `~/.m2/settings.xml`, not a defect in
  the change. Report it as a credential problem and stop.
- **Checkstyle violations** — these fail at `compile`, before any test runs. Report the
  rule and the file; the module's own `checkstyle-suppressions.xml` covers local
  exceptions if one is genuinely warranted.
- **Missing dependency after a `parents/` change** — install the parent POMs and `checkstyle`
  first, then retry once.

## 5. Report

State the verdict first, then the evidence, trimmed to the lines that matter.

```text
VERDICT: fail (tests)

Scope:      runtime-catalog (2 files changed)
Compile:    pass
Checkstyle: pass
Tests:      fail — 1 of 85

ChainServiceTest.shouldRejectDuplicateName:142
  expected: <409> but was: <200>
```

When everything passes, name the module, the command you ran, and whether you scoped the
tests with `-Dtest=` — the caller needs to know how much was actually covered.
