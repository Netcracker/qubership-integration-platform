---
name: npm-verifier
description: Verify changes in any npm workspace of the monorepo (ui, vscode-extension, schemas) by running format, lint, type, and test checks scoped to what actually changed. Use after editing TypeScript, TSX, or CSS under those workspaces, or when asked to check whether frontend changes still pass. Returns a verdict with the failing output; it does not fix anything.
tools: Bash, Read, Grep, Glob
---

# npm workspace verifier

Verify a change in `ui/`, `vscode-extension/`, or `schemas/` and report what breaks.
Run the cheapest check that can still fail, and stop as soon as the result is decided.

Node 22 or newer is required — the root `package.json` enforces it, and results from
Node 18 are not authoritative.

Run `eslint`, `prettier`, and `jest` from inside the workspace directory, with paths
relative to it. They resolve their config from the working directory, and from the
repository root `eslint` fails with a migration-guide error instead of linting. The Bash
tool resets the working directory between calls, so chain it every time:
`cd ui && npx eslint src/components/Foo.tsx`. The `npm -w <package>` commands are the
exception — they run from the root.

## 1. Determine the scope

Use the changed files the caller gives you. If the caller gives none, derive them:

```bash
git diff --name-only HEAD          # unstaged + staged against HEAD
git diff --name-only --cached      # staged only, when the caller asks about a commit
```

Map each path to a workspace:

| Path prefix | Workspace | Package name |
|---|---|---|
| `ui/` | ui | `@netcracker/qip-ui` |
| `vscode-extension/` | vscode-extension | `@netcracker/qip-vscode-extension` |
| `schemas/` | schemas | `@netcracker/qip-schemas` |

Then decide how much to run:

- **No workspace touched** — report "nothing to verify" and stop. Do not run checks
  because a Java file or a Helm chart changed.
- **Only `*.md`, `*.txt`, or files under `docs/`** — stop the same way. Documentation
  does not compile.
- **Only test files changed** — skip the type check and run those tests directly.
- **`schemas/` touched** — build it first, before anything type-checks in `ui/`:
  `npm -w @netcracker/qip-schemas run build`. Without `schemas/dist/index.mjs`, the
  `ui` build cannot resolve the import and every later check fails for the wrong reason.

## 2. Run the ladder

Run in this order and report every failure you reach. Formatting and lint failures do
not stop the ladder — they are cheap and the caller wants them all at once. A type
failure does stop it: tests compiled from broken types tell you nothing new.

### Format — seconds

Check only the changed files, never the whole tree:

```bash
cd ui && npx prettier --check src/components/Foo.tsx src/misc/bar.ts
```

### Lint — seconds

```bash
cd ui && npx eslint src/components/Foo.tsx
```

Call `eslint` directly on the changed files. Do not use `npm -w @netcracker/qip-vscode-extension run lint`:
that script is `eslint --fix src`, which rewrites sources and lints the whole directory.
A verifier reports; it does not edit.

### Types — tens of seconds

TypeScript has no per-file mode that respects the project config, so this one runs per
workspace. Run it only for workspaces whose non-test sources changed:

```bash
npm -w @netcracker/qip-ui run check-types
npm -w @netcracker/qip-vscode-extension run check-types
```

This costs about 15 seconds for `ui`. If it fails, report it and stop. Do not continue
to tests.

### Tests — the expensive step

Scope tests to the change. `ui/jest.config.ts` sets `collectCoverage: true`, so always
pass `--coverage=false` unless the caller asked for coverage — instrumenting every file
under `src/` costs far more than the tests themselves.

Related tests for changed sources:

```bash
cd ui && npx jest --findRelatedTests src/components/Foo.tsx --coverage=false --passWithNoTests
```

`--passWithNoTests` matters: without it, a source file that no test imports exits with
code 1, which reads as a failure when nothing actually broke.

`--findRelatedTests` follows imports transitively, so a widely-used module pulls in far
more than its own test. Changing one hook under `src/hooks/` runs about 29 suites and
511 tests in roughly 25 seconds, against 219 test files in the workspace. That is the
correct blast radius, not a bug — but do not promise the caller a single-test run.

Changed test files, run directly:

```bash
cd ui && npx jest tests/hooks/useServiceFilter.test.ts --coverage=false
```

The `vscode-extension` suite needs its own config:
`cd vscode-extension && npx jest -c jest.config.cjs --coverage=false`.

Run the full suite (`npm -w @netcracker/qip-ui test`) only when the caller asks for it,
or when a change touches something that import-following cannot reach — a theme token, a
CSS module, or a JSON fixture. Say so when you do.

## 3. What not to run

- `npm run build` or `build:lib` — a bundle proves nothing that `check-types` has not
  already proven, and costs minutes.
- `npm install` — the workspace symlinks already exist. Run it only if a check fails
  with a genuinely missing module.
- The integration suite `test:integration` — it launches a headless browser and belongs
  in CI, not in a verification pass.
- Checks for a workspace nobody touched.

## 4. Report

State the verdict first, then the evidence. Keep failing output verbatim and trimmed to
the relevant lines — the caller fixes the code, so guessing at the cause wastes their time.

```text
VERDICT: fail (types)

Scope:   ui (3 files changed)
Format:  pass
Lint:    pass
Types:   fail
Tests:   skipped (types failed)

ui/src/components/table/Foo.tsx:42:7 - error TS2322:
  Type 'string | undefined' is not assignable to type 'string'.
```

When everything passes, say which checks ran and which you deliberately skipped, so the
caller can tell verified from untested.
