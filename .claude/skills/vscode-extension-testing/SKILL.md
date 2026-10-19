---
name: vscode-extension-testing
description: Writes and changes the VS Code extension's tests, covering Jest cases over the fake `vscode`, integration specs in the web host, golden files, and specification import cases. Use when adding or editing a test, a golden, or a fixture under vscode-extension/.
---

# Testing the VS Code extension

The `vscode-extension` instruction defines the four test levels, the fake's reset contract, and the rule that a stub
reproduces what the real code does. This skill covers what a case at each level owes.

## Jest over the fake

- `tests/fake-vscode/index.ts` and `file-system.ts` list in their headers what the fake implements and what it leaves
  out. A case that needs a missing member adds it to the fake. A filesystem answer it adds is one that
  `src/web/test/suite/file-system.test.ts` measures in the host.
- `FileType.SymbolicLink` is reachable only here: a seed entry carries its own `FileType`, and the host cannot create a
  link.
- `bindFileApi()` from `tests/fake-vscode/fileApi.ts` binds the extension's file API over the fake.
- The prompts resolve `undefined`, the answer of a dismissed prompt. Stub an answer with `mockResolvedValueOnce`.

## Integration specs

- The host opens `src/web/test/workspace/` and keeps writes in memory. A spec that writes or deletes works on its own
  files, a `copyFolder` copy or new files from `writeYaml` in `harness.ts`, and removes them in a teardown hook or a
  `finally`.
- The test bundle carries its own copy of the extension's modules. Call `useExtensionModules()` before calling one,
  such as `getApiResponse`. The `vscode` API object is shared, so a stub on `vscode.window` supplies a prompt's answer.
- A spec sends `getApiResponse` the message the webview would send. The webview-to-router wiring in `enrichWebview`
  and the webview DOM stay untested.
- The host is the current insiders build (`--quality insiders`), because `customEditorDiffs` is a proposed API. When
  the gate fails on the host download, a host measurement, or a chromium launch, follow the comment above the
  `Run integration tests` step in `.github/workflows/vscode-extension-build.yaml`.

## Golden files

Two suites write `src/web/test/golden/`, and the `e2e` instruction says how the end-to-end suite imports it. Regenerate
a golden in the suite that writes it, in the same commit as the change to what the extension writes, and run
`e2e/specs/api/extension-output.spec.ts` too. On a cluster whose istiod lacks `PILOT_ENABLE_ALPHA_GATEWAY_API=true`, run
that spec with `E2E_PROVISION=never`.

- **Top level:** level-2 specs compare bytes. Copy the text a failing comparison reports into the file. The
  `require.context` in `harness.ts` is non-recursive, so `golden/import/` never reaches the level-2 bundle.
- **`golden/import/<protocol>/`:** the Jest cases in `tests/web/import/` write these, one tree per protocol. Print
  `workspaceTree()` after the import and write each entry at its path relative to the service folder. The copies
  under `resources/` stay byte-identical to `tests/fixtures/specifications/`, so editing a fixture also rewrites that
  copy and the `sourceHash` beside it.

## Specification import cases

The import is tested at level 1 only, by the cases in `tests/web/import/`. Its fixtures live in
`tests/fixtures/specifications/`, and level 3 builds its archive from the golden trees, so nothing goes under
`e2e/fixtures/`. Read the header of the file you extend first: `soap.test.ts` for a golden case, `failures.test.ts`
for a refusal, `importSession.test.ts` for the session cases.

- Route the webview's message through `getApiResponse(message, serviceFileUri, context)` rather than constructing
  `SpecificationImportService`. The second argument is the service **file**, not its folder, and the third is
  required; `postImportGroup` in `tests/helpers/importHarness.ts` supplies both.
- Call `resetImportWorkspace(prefix)` from `beforeEach`. It resets the fake, clears the `FileCacheService` singleton,
  binds the file API, and freezes `crypto.randomUUID` with `freezeSourceIds`.
- Give each test file its own service id and prefix, because level 3 imports every tree into one catalog.
- Post the files in a fixed order: one counter serves every minted id, so the order decides which id each file gets.
- Seed the service document with `tests/helpers/seedServiceDocument.ts`; its header gives the contract a seed keeps.
- A golden is the document `ServiceNormalizer` writes, defaults included, not the seed plus the imported fields.
- `runImport` resolves with a `warningMessage` for most refusals and shows the text through
  `window.showErrorMessage`. Assert the rejection only for the three messages raised ahead of it, which
  `failures.test.ts` lists. Assert an empty dialog list on success, because a regression can raise a dialog without
  changing a byte on disk.
- `ImportProgressTracker` has no reset method, and needs none: the frozen `importId` makes each case overwrite the
  previous case's session.
- A case about what the user is told or what lands on disk goes in `tests/web/import/`. A case about which
  collaborator the service calls, an answer only a stub can produce, goes in
  `tests/api-services/SpecificationImportService.test.ts`.
- A parser unit case goes beside its neighbors, under `src/web/api-services/parsers/` or `tests/api-services/parsers/`.
