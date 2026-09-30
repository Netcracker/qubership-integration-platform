# Documentation exclude patterns implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an optional `exclude` list of glob patterns to `.documentation-config.json` so that matching files never
reach the documentation destination, and therefore never reach the table of contents or the search index.

**Architecture:** Every source type (`local`, `git`, `npm`) ends in `copySourceToDest()` in
`ui/scripts/fetch-documentation.mjs`. That function gets a `filter` on its `fs.cpSync` call that drops paths matching
an `exclude` pattern. `main()` validates `exclude` before fetching. `ui/scripts/build-doc-index.mjs` is not modified.

**Tech Stack:** Node.js ESM scripts, `path.posix.matchesGlob` (Node >= 22.5), `node:test` for the new test.

**Spec:** `docs/superpowers/specs/2026-09-30-documentation-exclude-design.md`

## Global constraints

- `engines.node` in the root `package.json` (and the mirrored entry in `package-lock.json`) becomes `">=22.5.0"`.
- No new npm dependencies. Glob matching uses `path.posix.matchesGlob` only.
- Patterns match paths relative to the documentation source root, with `/` as the separator on every OS.
- A missing or empty `exclude` must leave the build output identical to today's.
- Invalid `exclude` (anything other than an array of strings) fails the build with exit code 1 and the message
  `"exclude" must be an array of glob pattern strings`.
- `exclude` is ignored when `source` is `"none"`.
- Negation patterns and include lists are out of scope.
- Commit messages follow Conventional Commits and end with the session's `Co-Authored-By` attribution trailer, passed
  where the commit commands below show `<Co-Authored-By trailer>`.
- Before writing any English text (comments, error messages, docs, commit messages), load the `english-developer-style`
  skill.

## Review focus

1. **No `exclude` field**, which is the case for the committed `ui/.documentation-config.json`: the output must be
   byte-for-byte the same set of files as before. Pinned by the "copies everything without exclude" test in Task 1.
2. **Windows path separators**: `path.relative` returns `01__Chains\drafts` on Windows. Unless it's converted to `/`,
   patterns silently match nothing on developer machines while CI (Ubuntu) passes. Pinned by the Task 1 tests when
   they run locally on Windows; the executor must run them on Windows, not only in CI.
3. **Invalid `exclude` with a `git` source**: validation must happen before the clone, so a typo fails fast instead of
   after a network round trip. Pinned by the invalid-config test, which asserts `public/doc` was never created.
4. **Stale files from a previous build**: a file excluded after it was already copied must disappear from `public/doc`.
   The existing `fs.rmSync(dest)` in `copySourceToDest` handles this; the executor must not remove or reorder it.
5. **`git` and `npm` sources**: they aren't tested (the tests would need the network). The executor must confirm by
   reading the diff that `fetchFromGit` and `fetchFromNpm` both pass `config.exclude` to `copySourceToDest`.

---

## File map

| File                                        | Change                                                          |
| ------------------------------------------- | --------------------------------------------------------------- |
| `ui/scripts/fetch-documentation.mjs`        | Validate `exclude`; filter the copy; pass `exclude` from fetchers |
| `ui/scripts/fetch-documentation.test.mjs`   | New `node:test` end-to-end test of the script                   |
| `ui/package.json`                           | Add the `test:scripts` npm script                               |
| `ui/jest.config.ts`                         | Keep Jest away from `scripts/` (its `testMatch` would pick up the new test) |
| `.github/workflows/ui-build.yaml`           | Run `npm run test:scripts` in the `npm-build` job               |
| `package.json`, `package-lock.json`         | `engines.node` to `>=22.5.0`                                    |
| `ui/docs/DOCUMENTATION_INTEGRATION.md`      | Document `exclude`                                              |

---

### Task 1: Exclude patterns in the fetch script

**Files:**
- Modify: `ui/scripts/fetch-documentation.mjs` (functions `copySourceToDest`, `fetchFromNpm`, `fetchFromGit`,
  `fetchFromLocal`, `main`)
- Create: `ui/scripts/fetch-documentation.test.mjs`
- Modify: `ui/package.json` (`scripts`)
- Modify: `ui/jest.config.ts:199` (`testPathIgnorePatterns`)
- Modify: `.github/workflows/ui-build.yaml` (`npm-build` job, after the "Test with coverage" step)
- Modify: `package.json:17`, `package-lock.json:14` (`engines.node`)

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces: config field `documentation.exclude: string[]` (optional), documented in Task 2.
  `copySourceToDest(sourceDir: string, dest: string, exclude: string[] = []): void`.

- [ ] **Step 1: Install dependencies in the worktree**

The worktree has no `node_modules` of its own. From the worktree root:

Run: `npm install --legacy-peer-deps`
Expected: exits 0.

- [ ] **Step 2: Write the failing test**

Create `ui/scripts/fetch-documentation.test.mjs`:

```js
import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const script = fileURLToPath(
  new URL("./fetch-documentation.mjs", import.meta.url),
);

const docs = {
  "docs/00__Overview/readme.md": "# Overview",
  "docs/01__Chains/readme.md": "# Chains",
  "docs/01__Chains/drafts/readme.md": "# Draft",
  "docs/01__Chains/diagram.drawio": "<mxfile/>",
};

// Runs the real script in a temporary working directory that holds a small
// doc tree and a config with the given documentation fields.
function runFetch(t, documentation) {
  const cwd = fs.mkdtempSync(path.join(os.tmpdir(), "qip-doc-"));
  t.after(() => fs.rmSync(cwd, { recursive: true, force: true }));

  for (const [file, content] of Object.entries(docs)) {
    fs.mkdirSync(path.dirname(path.join(cwd, file)), { recursive: true });
    fs.writeFileSync(path.join(cwd, file), content);
  }
  fs.writeFileSync(
    path.join(cwd, ".documentation-config.json"),
    JSON.stringify({
      documentation: { source: "local", path: "docs", ...documentation },
    }),
  );

  const result = spawnSync(process.execPath, [script], {
    cwd,
    encoding: "utf-8",
  });
  const out = (file) => path.join(cwd, "public/doc", file);
  return { result, out };
}

test("copies everything without exclude", (t) => {
  const { result, out } = runFetch(t, {});

  assert.equal(result.status, 0, result.stderr);
  assert.ok(fs.existsSync(out("01__Chains/drafts/readme.md")));
  assert.ok(fs.existsSync(out("01__Chains/diagram.drawio")));
  assert.deepEqual(JSON.parse(fs.readFileSync(out("paths.json"), "utf-8")), [
    "00__Overview/readme.md",
    "01__Chains/readme.md",
    "01__Chains/drafts/readme.md",
  ]);
});

test("skips files and directories that match exclude", (t) => {
  const { result, out } = runFetch(t, {
    exclude: ["**/drafts", "**/*.drawio"],
  });

  assert.equal(result.status, 0, result.stderr);
  assert.ok(fs.existsSync(out("00__Overview/readme.md")));
  assert.ok(fs.existsSync(out("01__Chains/readme.md")));
  assert.ok(!fs.existsSync(out("01__Chains/drafts")));
  assert.ok(!fs.existsSync(out("01__Chains/diagram.drawio")));
  assert.deepEqual(JSON.parse(fs.readFileSync(out("paths.json"), "utf-8")), [
    "00__Overview/readme.md",
    "01__Chains/readme.md",
  ]);
});

test("fails before fetching when exclude is not an array", (t) => {
  const { result, out } = runFetch(t, { exclude: "**/drafts" });

  assert.equal(result.status, 1);
  assert.match(
    result.stderr,
    /"exclude" must be an array of glob pattern strings/,
  );
  assert.ok(!fs.existsSync(out("")));
});
```

- [ ] **Step 3: Run the test and confirm it fails**

From `ui/`:

Run: `node --test scripts/fetch-documentation.test.mjs`
Expected: "copies everything without exclude" passes. The other two fail: `drafts` and `diagram.drawio` still exist,
and the invalid config exits with code 0.

- [ ] **Step 4: Filter the copy**

In `ui/scripts/fetch-documentation.mjs`, replace `copySourceToDest` with:

```js
function copySourceToDest(sourceDir, dest, exclude = []) {
  if (fs.existsSync(dest)) {
    fs.rmSync(dest, { recursive: true, force: true });
  }
  fs.mkdirSync(dest, { recursive: true });
  fs.cpSync(sourceDir, dest, {
    recursive: true,
    filter: (src) => {
      // Patterns see "/"-separated paths relative to the source root on every OS.
      const relativePath = path.relative(sourceDir, src).replaceAll("\\", "/");
      return (
        relativePath === "" ||
        !exclude.some((pattern) => path.posix.matchesGlob(relativePath, pattern))
      );
    },
  });
  console.log(`[Documentation] Copied to ${dest}`);
}
```

The `relativePath === ""` check keeps the source root itself: `path.posix.matchesGlob("", "**")` is `true`.

- [ ] **Step 5: Pass `exclude` from each fetcher**

In `fetchFromNpm`, `fetchFromGit`, and `fetchFromLocal`, change the single call

```js
    copySourceToDest(sourceDir, dest);
```

to

```js
    copySourceToDest(sourceDir, dest, config.exclude);
```

In `fetchFromLocal` the call has two-space indentation (`  copySourceToDest(sourceDir, dest);`); keep it.

- [ ] **Step 6: Validate `exclude` before fetching**

In `main()`, inside the `try` block, insert the check before the first `console.log`:

```js
  try {
    const { exclude = [] } = config;
    if (
      !Array.isArray(exclude) ||
      !exclude.every((pattern) => typeof pattern === "string")
    ) {
      throw new Error('"exclude" must be an array of glob pattern strings');
    }

    console.log(`[Documentation] Fetching from source: ${config.source}`);
```

It sits after the `source === "none"` early return, so `exclude` is ignored for `"none"`, and before
`fetchDocumentation`, so a `git` source fails before cloning.

- [ ] **Step 7: Run the test and confirm it passes**

From `ui/`:

Run: `node --test scripts/fetch-documentation.test.mjs`
Expected: `# pass 3`, `# fail 0`. One `ExperimentalWarning: glob is an experimental feature` per script run in the
output is expected on Node 22.

- [ ] **Step 8: Wire the test into npm, Jest, and CI**

In `ui/package.json`, add after `"test": "jest",`:

```json
    "test:scripts": "node --test scripts/fetch-documentation.test.mjs",
```

In `ui/jest.config.ts`, Jest's default `testMatch` would pick up `scripts/fetch-documentation.test.mjs`. Change

```ts
  testPathIgnorePatterns: ["\\\\node_modules\\\\"],
```

to

```ts
  testPathIgnorePatterns: ["\\\\node_modules\\\\", "<rootDir>/scripts/"],
```

In `.github/workflows/ui-build.yaml`, in the `npm-build` job, add after the "Test with coverage" step:

```yaml
      - name: Test build scripts
        working-directory: ui
        run: npm run test:scripts
```

- [ ] **Step 9: Raise the Node floor**

In the root `package.json` and in `package-lock.json` (the `packages[""].engines` entry at line 14), change
`"node": ">=22.0.0"` to `"node": ">=22.5.0"`.

- [ ] **Step 10: Verify**

From `ui/`:

Run: `npm run test:scripts`
Expected: `# pass 3`, `# fail 0`.

Run: `npx jest --listTests | grep scripts`
Expected: no output (Jest no longer sees the scripts directory).

Run: `npx eslint scripts/`
Expected: exits 0.

Run: `npm run fetch-docs`
Expected: exits 0 with the committed config (no `exclude`), and `public/doc/paths.json` has 90 entries, one per `*.md`
file under `help/docs` (`find ../help/docs -name '*.md' | wc -l`).

- [ ] **Step 11: Commit**

```bash
git add ui/scripts/fetch-documentation.mjs ui/scripts/fetch-documentation.test.mjs ui/package.json ui/jest.config.ts .github/workflows/ui-build.yaml package.json package-lock.json
git commit -m "feat(ui): exclude documentation files by glob pattern" -m "<Co-Authored-By trailer>"
```

---

### Task 2: Document the `exclude` field

**Files:**
- Modify: `ui/docs/DOCUMENTATION_INTEGRATION.md` (section "Config fields", lines 68-78)

**Interfaces:**
- Consumes: config field `documentation.exclude: string[]` from Task 1, with the semantics in the spec's
  "Configuration" section.
- Produces: nothing.

- [ ] **Step 1: Replace the config-fields table**

Replace the table under `### Config fields` with:

```markdown
| Field         | Required              | Description                                                         |
| ------------- | --------------------- | ------------------------------------------------------------------- |
| `source`      | Yes                   | `"git"`, `"npm"`, `"local"`, or `"none"`                            |
| `repository`  | For Git               | Git repository URL                                                  |
| `branch`      | For Git               | Branch name (default: `"master"`)                                   |
| `package`     | For npm               | npm package name                                                    |
| `version`     | For npm               | npm version range                                                   |
| `path`        | Yes (except `"none"`) | Path to docs directory within the source                            |
| `destination` | No                    | Output directory (default: `"public/doc"`)                          |
| `exclude`     | No                    | Glob patterns for files and directories to skip, relative to `path` |
```

- [ ] **Step 2: Add an "Excluding files" subsection after the table**

Insert before `## Step 2: Add build scripts`:

````markdown
### Excluding files

List glob patterns in `exclude` to keep files out of the destination directory, the table of contents, and the search
index:

```json
{
    "documentation": {
        "source": "local",
        "path": "../help/docs",
        "exclude": ["**/drafts", "**/*.drawio"]
    }
}
```

Patterns match paths relative to `path` and use `/` as the separator on every OS. A pattern that matches a directory
skips the whole directory: `**/drafts` removes every `drafts` directory, while `**/drafts/**` removes only the files
inside them. Negation patterns (`!pattern`) are not supported. The field requires Node.js 22.5 or later.
````

Keep the file's four-space JSON indentation. Do not run Prettier on this file: it would reformat the other code blocks.

- [ ] **Step 3: Verify**

Open `ui/docs/DOCUMENTATION_INTEGRATION.md` in a Markdown preview and check that the table renders with eight rows and
the new subsection appears between "Config fields" and "Step 2".

- [ ] **Step 4: Commit**

```bash
git add ui/docs/DOCUMENTATION_INTEGRATION.md
git commit -m "docs(ui): document the documentation exclude field" -m "<Co-Authored-By trailer>"
```
