# Align the release workflows and scripts with the new Maven module tree

- **Date:** October 9, 2026
- **Branch:** `feat/maven-module-structure-refactoring`
- **Status:** approved design, awaiting the implementation plan

## Context

The branch replaces `parent/pom.xml` (`qip-monorepo-parent`) with four POMs and adds a public BOM:

- `parents/pom.xml` (`qip-parents`), an aggregator with no children of its own.
- `parents/base/pom.xml` (`qip-parent-base`), the parent of the root POM, the BOM, checkstyle, schemas, and
  `parents/quarkus`.
- `parents/spring/pom.xml` (`qip-parent-spring`), which extends `spring-boot-starter-parent` and copies what it needs
  from the base parent.
- `parents/quarkus/pom.xml` (`qip-parent-quarkus`), the parent of micro-engine.
- `bom/pom.xml` (`qip-bom`), which lists every published module in `dependencyManagement`.

`mvn dependency:list` prints the same list as on `main` for every Maven module, and the effective build plugins differ
only in pinned versions. The POM tree is done. The release workflows and scripts still assume the old layout, so no
module can be released from the branch:

- The release and snapshot workflows install `parent/pom.xml`, which no longer exists.
- `check-version-invariants.sh` exits 1, so the Version Invariants check fails on the pull request.
  `set-platform-version.sh`, `build-drop-release.sh`, and the `platform` case of `compute-release-version.sh` read
  the root `<revision>`, which the root POM no longer has.
- The library and checkstyle pin properties were renamed, and the sync steps still write the old names.
- `SKIP_GPG` skips signing for `profile=github`, which is now every release.
- The `central` and `github` Maven profiles are gone, but every release wrapper still offers `profile: central`.
- Nothing updates the BOM's entries, releases the BOM, or moves the parents' version.
- The path filters of the build workflows watch `parent/**`.
- `e2e/env/provision/compose.ts` decides image staleness from `parent/pom.xml`.

## Goals

1. `check-version-invariants.sh` passes on the branch and enforces the new version model.
2. A standalone module release, a `release-all` wave, a platform drop, and a snapshot publish work on the new layout.
3. Every artifact the workflows deploy is signed, releases and snapshots alike.
4. The BOM's entries match the module versions at every commit. A standalone module release publishes a new BOM
   unless the dispatch turns the cascade off, and a `release-all` wave publishes one BOM.
5. The parent POMs are published at each platform drop and on demand as snapshots.
6. A pull request that changes `parents/**` triggers the builds of every module that inherits from them.

## Constraints

These come from outside the team and are not open for discussion in this change:

- The Spring parent keeps `spring-boot-starter-parent` as its parent.
- Each module keeps its own version through its `revision` and `changelist` properties.
- Nothing is deployed to Maven Central. Signing stays.
- The BOM lists the Spring services, the Quarkus service, and the Maven plugin on purpose: they are Spring Boot starters
  and extension bases for other applications.

The parents keep a literal `<version>`. Maven 3 compares a child's literal parent version with the parent's raw
`${revision}${changelist}`, finds no match, and resolves the parent from the repository instead of the `relativePath`,
so every child build fails on a clean repository.

## Decisions

| Question | Decision |
| --- | --- |
| Where does the platform version live? | The root `pom.xml` `<revision>`, restored. |
| What do the BOM's entries hold? | The module's project version: `<revision>${changelist}`. |
| When is the BOM released? | After every standalone Maven module release, as a patch, and once at the end of `release-all`. |
| Are the parents published? | Yes, at each platform drop, and as snapshots on demand. |
| Are snapshots signed? | Yes. |
| Is the e2e staleness list in scope? | Yes. |
| Structure | Extend the existing scripts and wrappers in place; add two small scripts and a sourced `pom.sh`. |

## Version model

| Version | Source of truth | Copies that must match | Written by |
| --- | --- | --- | --- |
| Platform `P` | root `pom.xml` `<revision>` | the literal `<version>P-SNAPSHOT</version>` of every POM in `QIP_PARENT_POMS`, and every `<parent><version>` in `QIP_PARENT_CHILDREN` | `set-platform-version.sh` |
| Module `M` | `<module>/pom.xml` `<revision>` | the module's BOM entry | `sync-bom-entry.sh`, from the release bump step |
| Library | `integration-build-pipeline/pom.xml` `<revision>` | `qip.integration-build-pipeline.revision` in runtime-catalog and integration-build-maven-plugin | `sync-consumer-pins.sh` |
| Checkstyle | `checkstyle/pom.xml` `<revision>` | `qip.checkstyle.version` in `parents/base` and `parents/spring`, and the BOM entry | `sync-consumer-pins.sh` and `sync-bom-entry.sh` |
| BOM `B` | `bom/pom.xml` `<revision>` | none | the release bump step, with `module: bom` |

A BOM entry is written as the module's `<revision>` followed by the literal text `${changelist}`, for example
`<version>1.5.1${changelist}</version>`. In the working tree, the BOM's own `changelist` is `-SNAPSHOT`, so the entry
resolves to `1.5.1-SNAPSHOT`, which is the module's project version. A BOM release passes `-Dchangelist=`, so every
entry resolves to the released `1.5.1`, and flatten writes the literal value into the published BOM. checkstyle's
`changelist` is empty, so its project version is the bare `<revision>`, and its entry is the bare `<revision>` too.

The platform version follows the same bump-before model as the modules: the root `<revision>` holds the last released
platform version, and the parents carry that version with `-SNAPSHOT` appended.

### One-time POM edits

Two POM edits come with this change, because the version model needs them:

- The root `pom.xml` gets `<version>${revision}${changelist}</version>`, `<revision>1.5.1</revision>`, and
  `<changelist>-SNAPSHOT</changelist>`. Nothing inherits from the root, so a CI-friendly version is safe there.
- Every BOM entry except checkstyle's becomes `<version>1.5.1${changelist}</version>`. checkstyle's entry stays
  `0.0.3`.

## Release flows

### Module release (`_maven-module-release.yaml`)

The reusable serves every Maven wrapper and the BOM.

1. `check-version` is unchanged: it bumps `<revision>` per release type, and it sets `recover=true` when the tag
   already exists.
2. The parents are installed into the runner's local repository with
   `mvn -f parents/pom.xml install -Dgpg.skip=true`. This step replaces the install of `parent/pom.xml`.
3. The deploy runs `mvn deploy -pl $MODULE -Drevision=$RELEASE_VERSION -Dchangelist= $EXTRA_ARGS`, signed. The
   `profile` input, the `-P` flag, `SKIP_GPG`, and the `MAVEN_USER` and `MAVEN_PASSWORD` secrets are removed.
4. The bump step writes `<revision>`, moves the `sync-property` pins as it does today, and runs
   `sync-bom-entry.sh $MODULE $RELEASE_VERSION` unless the module is `bom`. One commit carries every change, tagged and
   pushed by `commit-and-push.sh` as today.

Parallel releases in a wave all change `bom/pom.xml`, but each one changes only the `<version>` line of its own entry.
The entries are five lines apart, so the rebase in `commit-and-push.sh` merges them without a conflict.

### BOM cascade

Each dispatchable Maven wrapper gains a `cascade-bom` boolean input, `true` by default, on both `workflow_dispatch` and
`workflow_call`. The wrappers are `engine-release.yaml`, `micro-engine-release.yaml`, `runtime-catalog-release.yaml`,
`sessions-management-release.yaml`, `integration-build-pipeline-release.yaml`,
`integration-build-maven-plugin-release.yaml`, `checkstyle-release.yaml`, and `schemas-release.yaml`.

The `cascade-bom` job runs after the module's release:

```yaml
cascade-bom:
  needs: release            # release-maven in schemas-release.yaml
  if: ${{ inputs.cascade-bom && !failure() && !cancelled() }}
  uses: ./.github/workflows/_maven-module-release.yaml
  with:
    module: bom
    release-type: patch
    build-docker: false
```

The job calls the reusable directly, not a BOM wrapper, so that `release-all` → wrapper → reusable stays at three
levels of nesting. `ui-release.yaml` does the same for vscode-extension.

A new `bom-release.yaml` releases the BOM on its own: a patch on `workflow_dispatch`, and the caller's release type on
`workflow_call`.

### Release wave (`release-all.yaml`)

- `prepare` loses the `profile` input and the step that rejects a Central wave. `bom` joins the module tokens through
  `QIP_BOM_MODULES`. `release-bom` is `true` when any Maven module, schemas included, is selected, or when `bom` is
  requested.
- Every Maven module job passes `cascade-bom: false`.
- A new `release-bom` job needs every Maven job (engine, micro-engine, integration-build-pipeline, runtime-catalog,
  integration-build-maven-plugin, sessions-management, and the schemas Maven line). It runs when `release-bom` is
  `true` and no needed job failed, and it releases the BOM with the wave's release type through `bom-release.yaml`.
- `publish-bom` and `create-drop-release` also need `release-bom`. `create-drop-release` sets up Java with the GPG key
  and gains `packages: write`, for the parents deploy.
- The GPG secrets reach every job that deploys, micro-engine included.

### Platform drop (`build-drop-release.sh`)

1. The release notes are built as today. `DRY_RUN` exits after printing them, as today.
2. `publish-parents.sh "$version"` publishes the parents at the platform version (see below).
3. `set-platform-version.sh "$version"` writes the root `<revision>` `P`, `P-SNAPSHOT` into every parent POM, and
   `P-SNAPSHOT` into every parent pin. `commit-and-push.sh` commits, tags `vP`, and pushes, as today.
4. The GitHub Release is created or edited, as today.

The published parents equal the parent POMs at tag `vP` with `-SNAPSHOT` removed from their versions.

### Snapshot publish (`snapshot-publish.yaml`)

- The `module` choice gains `parents` and `bom`.
- The parents install step replaces the install of `parent/pom.xml`.
- `parents` deploys with `mvn -f parents/pom.xml deploy -pl base,spring,quarkus`. Every other choice deploys as today.
- `-Pgithub` and `-Dgpg.skip=true` are removed. Every deploy, including the library deploy that runs first for
  runtime-catalog and integration-build-maven-plugin, is signed with `MAVEN_GPG_PRIVATE_KEY` and
  `MAVEN_GPG_PASSPHRASE`.
- A module snapshot does not publish a BOM snapshot.

## Scripts

### `modules.sh`

| Variable | Value |
| --- | --- |
| `QIP_PARENT_POMS` | the parent POMs that declare their own `<version>`: `parents/pom.xml parents/base/pom.xml parents/spring/pom.xml` |
| `QIP_PARENT_CHILDREN` | POM paths, no longer directory names: `pom.xml bom/pom.xml checkstyle/pom.xml schemas/pom.xml integration-build-pipeline/pom.xml integration-build-maven-plugin/pom.xml runtime-catalog/pom.xml sessions-management/pom.xml engine/pom.xml micro-engine/pom.xml parents/quarkus/pom.xml` |
| `QIP_LIBRARY_PROPERTY` | `qip.integration-build-pipeline.revision` |
| `QIP_LIBRARY_CONSUMERS` | unchanged |
| `QIP_CHECKSTYLE_PROPERTY` | `qip.checkstyle.version` |
| `QIP_CHECKSTYLE_CONSUMERS` | `parents/base/pom.xml parents/spring/pom.xml` |
| `QIP_BOM_ENTRIES` | the module directories the BOM lists: `schemas checkstyle micro-engine integration-build-pipeline integration-build-maven-plugin engine runtime-catalog sessions-management` |
| `QIP_BOM_MODULES` | gains `bom` |

`parents/quarkus/pom.xml` declares no `<version>` of its own and inherits it through its pin on the base parent, so it
appears in `QIP_PARENT_CHILDREN` only.

The workflows cannot source a shell file, so `checkstyle-release.yaml` and `integration-build-pipeline-release.yaml`
spell out the property names and POM paths, as they do today.

### `pom.sh` (new, sourced)

The POM readers and writers the four version scripts share: a property, a POM's own `artifactId` and `<version>`, its
parent pin, a BOM entry, and the matching writers. It runs nothing when executed, like `modules.sh`.

### `sync-bom-entry.sh <module> <version>` (new)

Reads the module's `artifactId` from `<module>/pom.xml`: the first `<artifactId>` after the `<parent>` block. Finds the
`<dependency>` block in `bom/pom.xml` with that `artifactId`, and replaces the version number in its `<version>` with
the given version, keeping a `${changelist}` suffix when the entry has one. Fails when the BOM has no entry for the module, or when the rewrite
does not land. Prints `bom/pom.xml`, so the caller can commit it.

### `publish-parents.sh <version>` (new)

1. For each of `qip-parent-base`, `qip-parent-spring`, and `qip-parent-quarkus`, requests the POM with `curl --fail`
   and `GH_TOKEN` at
   `https://maven.pkg.github.com/Netcracker/qubership-integration-platform/org/qubership/integration/platform/<artifact>/<version>/<artifact>-<version>.pom`.
   A parent whose POM downloads is already published and is skipped.
2. When every parent is published, exits 0 without building.
3. Otherwise, rewrites the own `<version>` of every POM in `QIP_PARENT_POMS`, and the base pin in `parents/quarkus`,
   to the given version. A `trap` restores `parents/` with `git checkout -- parents` on exit, whatever the
   outcome.
4. Runs `mvn -B -s .github/maven-settings.xml -f parents/pom.xml deploy -pl <missing parents>`, signed. The
   `qip-parents` aggregator is never deployed: no POM inherits from it.

A rerun after a partial failure deploys only the parents that are still missing, so the script never redeploys a
released version, which GitHub Packages rejects.

### `set-platform-version.sh <version>`

Writes the root `<revision>`; the own `<version>` of every POM in `QIP_PARENT_POMS`, which is the first `<version>`
outside the `<parent>` block, so the `<version>` of `spring-boot-starter-parent` is never touched;
and the `<version>` inside the `<parent>` block of every POM in `QIP_PARENT_CHILDREN`. Verifies every write and prints
the changed files, as today.

### `check-version-invariants.sh`

Fails with one `::error::` line per broken invariant:

1. The root `<revision>` is `X.Y.Z`. Call it `P`.
2. The own `<version>` of every POM in `QIP_PARENT_POMS` is `P-SNAPSHOT`.
3. The parent pin of every POM in `QIP_PARENT_CHILDREN` is `P-SNAPSHOT`.
4. Every `QIP_LIBRARY_PROPERTY` pin equals the library's `<revision>`.
5. Every `QIP_CHECKSTYLE_PROPERTY` pin equals checkstyle's `<revision>`.
6. For every module in `QIP_BOM_ENTRIES`, the BOM entry is `<revision>${changelist}`, or the bare `<revision>` when
   the module's `<changelist>` is empty. Every BOM entry belongs to a module in `QIP_BOM_ENTRIES`.
7. Every property declared in both `parents/base/pom.xml` and `parents/spring/pom.xml` has the same value in both.
   The 16 shared properties agree today. The four base-only plugin versions are managed by Spring Boot for the Spring
   parent and are not compared.

### Other scripts

- `build-drop-release.sh` calls `publish-parents.sh` as described above, and its header comment names the new files.
- `compute-release-version.sh` keeps its logic. `ECOSYSTEM=maven MODULE=bom` reads `bom/pom.xml`, and
  `ECOSYSTEM=platform` reads the restored root `<revision>`.
- `build-bom.sh` reports a `bom` row, from the `bom-v*` tags, through `QIP_BOM_MODULES`.
- `commit-and-push.sh` changes only a comment that refers to a Central deploy.
- `sync-consumer-pins.sh` is unchanged. Its callers pass the new property names and POM paths.
- `scripts/README.md` describes the platform version, the BOM entries, the cascade, the parents publish, and the two
  new scripts.

## Workflows

| File | Change |
| --- | --- |
| `_maven-module-release.yaml` | parents install; signed deploy without `-P`; BOM entry sync; `profile` input and Central secrets removed |
| `snapshot-publish.yaml` | parents install; `parents` and `bom` choices; signed deploys; `-Pgithub` removed |
| `release-all.yaml` | `profile` input and Central guard removed; `cascade-bom: false`; `release-bom` job; drop job gains Java, GPG, and `packages: write`; micro-engine gets the GPG secrets |
| `bom-release.yaml` (new) | BOM release wrapper |
| `engine-release.yaml`, `micro-engine-release.yaml`, `runtime-catalog-release.yaml`, `sessions-management-release.yaml`, `integration-build-pipeline-release.yaml`, `integration-build-maven-plugin-release.yaml`, `checkstyle-release.yaml` | `profile` input and Central secrets removed; `cascade-bom` input and job; micro-engine passes the GPG secrets |
| `schemas-release.yaml`, `schemas-maven-release.yaml` | `profile` and `maven-profile` inputs and Central secrets removed; `cascade-bom` in `schemas-release.yaml` |
| `checkstyle-release.yaml`, `integration-build-pipeline-release.yaml` | `sync-property` and `sync-poms` use the new names and paths |
| `engine-build.yaml`, `runtime-catalog-build.yaml`, `sessions-management-build.yaml`, `integration-build-pipeline-build.yaml`, `integration-build-maven-plugin-build.yaml`, `main-build.yaml`, `snapshot-flow-build.yaml` | `parent/**` becomes `parents/**` |
| `micro-engine-build.yaml`, `checkstyle-build.yaml`, `schemas-build.yaml` | `parents/**` added |
| `.github/maven-settings.xml` | the `central` server and its comment removed |

`bom/**` needs no build of its own: `version-invariants.yaml` already runs on every `**/pom.xml` change, and it checks
every BOM entry.

## Documentation and e2e

- The `.apm` sources: `.apm/instructions/monorepo.instructions.md`, `.apm/agents/maven-verifier.agent.md`, and
  `.apm/skills/release-process/SKILL.md`. `apm compile` regenerates `AGENTS.md` and the `.claude`, `.cursor`, and
  `.codex` copies; none of those is edited by hand.
- `scripts/README.md`, `checkstyle/README.md`, `integration-build-maven-plugin/README.md`, and the header comments of
  every changed workflow.
- `e2e/env/provision/compose.ts`: `parent/pom.xml` becomes `parents/spring/pom.xml` in the `sources` of
  runtime-catalog, engine, and sessions-management, and the doc comment describes the new parent. The Spring parent
  extends `spring-boot-starter-parent`, so `parents/base/pom.xml` reaches none of these jars.
  `e2e/specs/schema/provision.spec.ts` and `e2e/k8s/README.md` follow.

## Failure handling

- Every script verifies each write and fails with an `::error::` line, as the existing scripts do.
- A module release whose BOM sync fails has already deployed, the same position as a failed pin sync today. Invariant
  6 on the pull request makes that state unreachable from a merged tree: every module in `QIP_BOM_ENTRIES` has an
  entry.
- Module and BOM recovery are unchanged: an existing tag skips the deploy and re-applies the bump.
- `publish-parents.sh` checks each parent before deploying, so a rerun of the drop never redeploys a published
  parent.

## Verification

Local checks, each part of the implementation plan. The script checks live in `scripts/test_version_scripts.py`, a
`unittest` file like `scripts/test_run_micro_engine_snapshots.py`, and `version-invariants.yaml` runs it.

- `check-version-invariants.sh` passes on the branch. On a scratch copy with one value broken per invariant, it fails
  with that invariant's error.
- On a scratch copy, `set-platform-version.sh 1.5.2` and `sync-bom-entry.sh engine 1.5.2` (with the engine
  `<revision>` bumped) leave the invariants passing and change only the expected files.
- `compute-release-version.sh` returns the expected version and tag for `ECOSYSTEM=platform` and for
  `ECOSYSTEM=maven MODULE=bom`.
- Deploys to a local `-DaltDeploymentRepository` directory:
  - the BOM with `-Drevision=1.5.2 -Dchangelist=` publishes release versions only;
  - engine with the same flags publishes a flattened POM at `1.5.2`;
  - the parents, rewritten as `publish-parents.sh` rewrites them, publish at `1.5.2`, and `parents/` is restored
    afterwards.
- `shellcheck` on every changed script and `actionlint` on every changed workflow. super-linter runs both in CI.
- `npx playwright test --project=schema` in `e2e/`.

Not verifiable locally: a deploy to GitHub Packages, the `curl` check against it, the nesting limit, and the tag and
branch pushes. The first release after the merge exercises them; the pull request description says so.

## Out of scope

- Moving the parents to `${revision}`, which needs Maven 4.
- Deduplicating the base and Spring parents beyond the drift check: the constraints keep `spring-boot-starter-parent`.
