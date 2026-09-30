# Excluding files from the built-in documentation

## Goal

Let a `.documentation-config.json` list glob patterns for files that the UI build leaves out of the documentation. An
excluded file is not copied into the destination directory, so it appears in neither the table of contents nor the search
index.

## Current behavior

`ui/scripts/fetch-documentation.mjs` resolves the documentation source (`local`, `git`, or `npm`) to a directory and
passes it to `copySourceToDest()`, which copies the whole tree into `destination` (default `public/doc`) with
`fs.cpSync`. The script then runs `ui/scripts/build-doc-index.mjs` on that destination, which walks every `*.md` file
and writes `paths.json`, `names.json`, `toc.json`, and `search-index.json`. Nothing can be left out.

## Configuration

Add an optional `exclude` field to the `documentation` object:

```json
{
  "documentation": {
    "source": "local",
    "path": "../help/docs",
    "exclude": ["**/drafts", "**/*.drawio"]
  }
}
```

- `exclude` is an array of glob patterns. When it is absent or empty, the build behaves exactly as it does today.
- Each pattern is matched against the path of a file or directory relative to the documentation source root, which is
  the directory that `path` points to. Paths use `/` as the separator on every OS, so `00__Overview/readme.md` is the
  string a pattern sees on Windows too.
- A pattern that matches a directory excludes the directory and everything under it. `**/drafts` removes every
  `drafts` directory; `**/drafts/**` removes the files inside them but leaves the empty directories.
- Matching uses the glob syntax of Node's `path.matchesGlob`. Negation (`!pattern`) is not supported.
- `exclude` applies to all three sources and is ignored when `source` is `"none"`.

## Design

The change lives in `ui/scripts/fetch-documentation.mjs`. `build-doc-index.mjs` is not modified: it indexes what is in
the destination directory, and excluded files never get there.

1. `loadDocumentationConfig()` returns the `documentation` object unchanged. `exclude` travels with the rest of the
   config.
2. `copySourceToDest(sourceDir, dest, exclude = [])` passes a `filter` to `fs.cpSync`. For each entry, the filter
   computes `path.relative(sourceDir, src)`, replaces `\` with `/`, and returns `false` when any pattern matches through
   `path.posix.matchesGlob`. The source root itself has an empty relative path and is always copied.
3. `fetchFromLocal`, `fetchFromGit`, and `fetchFromNpm` pass `config.exclude` to `copySourceToDest`.

### Validation

When `exclude` is present and is not an array of strings, the script throws
`"exclude" must be an array of glob pattern strings`. The existing catch in `main()` logs it and exits with code 1, so a
malformed config fails the build instead of shipping documentation that was meant to be hidden.

### Node version

`path.matchesGlob` was added in Node 22.5.0. The `engines.node` range in the root `package.json` moves from `>=22.0.0`
to `>=22.5.0`. CI already uses `22.x`. On Node 22.15, the first match prints one `ExperimentalWarning: glob is an
experimental feature`; this is accepted in exchange for not adding a dependency.

## Testing

Add `ui/scripts/fetch-documentation.test.mjs`, run with `node --test`. It exercises the real script end to end, without
refactoring it for testability:

1. Create a temporary working directory with a small documentation tree: two kept `readme.md` files, one `readme.md`
   under a `drafts` directory, and one `.drawio` file.
2. Write a `.documentation-config.json` with `source: "local"`, `path` pointing at that tree, and
   `exclude: ["**/drafts", "**/*.drawio"]`.
3. Run `node <repo>/ui/scripts/fetch-documentation.mjs` with the temporary directory as `cwd`.
4. Assert that the excluded files are absent from `public/doc`, the kept files are present, and `paths.json` lists only
   the kept documents.

A second case writes `"exclude": "**/drafts"` (a string, not an array) and asserts that the script exits with code 1.

The test runs through a new `test:scripts` npm script in `ui/package.json`. The existing Jest suite covers `src/` and is
not involved.

## Documentation

`ui/docs/DOCUMENTATION_INTEGRATION.md` gets an `exclude` row in the *Config fields* table and a short example showing
the field. The *Documentation directory structure* section of that page still shows file names that this branch renamed
to `readme.md`; updating it is out of scope for this change.

## Out of scope

- Negation patterns and include lists.
- Excluding a file from the index while still copying it into the destination.
