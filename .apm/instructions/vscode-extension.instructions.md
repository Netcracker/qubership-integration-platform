---
description: "VS Code extension: build chain, architecture, and test conventions."
applyTo: "vscode-extension/**"
---

### Project overview

`@netcracker/qip-vscode-extension` is an offline web extension for editing QIP chains and services stored as local
`*.cip.yaml` files. It runs in the `webworker` extension host, with no Node runtime and no network, and reaches the
files only through `vscode.workspace.fs`. The webview embeds `@netcracker/qip-ui`, and documents are validated against
`@netcracker/qip-schemas`. The package ships to GitHub Packages as an npm library, not to the VS Code Marketplace.

### Build and test commands

```bash
npm run build               # prepare-deps (schemas, qip-ui library bundle) + compile-web
npm run compile-web         # webpack alone, once the upstream workspaces are built
npm run watch-web
npm run test:unit
npm run test:unit:coverage
npm run test:integration
npm run lint
npm run check-types
npm run run-in-browser      # manual smoke run in a browser host
```

### Architecture

`webpack.config.js` exports two bundles. The web extension, `src/web/extension.ts` → `dist/web/extension.js`, targets
`webworker` and also bundles the integration-test entry. The library, `src/web/index.ts` → `dist/node/index.js`, is
CommonJS for downstream apps that embed the extension's modules; `src/web/index.ts` is its export list.

`copy-webpack-plugin` copies `ui/dist-lib` into `dist/web/qip-ui/`. `getWebviewContent` in `extension.ts` loads
`index.bundled.es.js`, which embeds React, and falls back to `index.es.js` with an esm.sh import map for React when the
bundled file is missing, so a build without `build:lib:bundled` needs network access.

`package.json` registers one custom editor per CIP file type. `getEditorViewTypeForUri` in `editorViewTypes.ts` maps a
URI to its view type for every caller that opens a document, so a new editor type needs an entry there only.

### Project structure

Domain types (`Chain`, `Element`, `LibraryData`, message envelopes) come from `@netcracker/qip-ui`; schema types from
`@netcracker/qip-schemas`. File extensions are the `FILE_EXTENSIONS` constant in
`src/web/response/file/fileExtensions.ts`, and schema URLs come from `src/web/services/cipSchemas.ts`.

### Tests

- Four test levels: **1** Jest over the `tests/fake-vscode/` fake, no host; **2** mocha in `@vscode/test-web` over the
  real `workspace.fs` (`src/web/test/suite/`); **3** the catalog importing a golden
  (`e2e/specs/api/extension-output.spec.ts`); **4** the webview DOM, out of scope. A behavior the host cannot produce,
  a symbolic link for one, can only be pinned at level 1.
- Jest cases live under `tests/` and beside their sources in `src/web/api-services/` and `src/web/services/`. Level-2
  specs live in `src/web/test/suite/`, which Jest ignores.
- Jest maps `vscode` to the fake in `tests/fake-vscode/`, an in-memory filesystem. Seed it with `seedWorkspace`,
  address it with `workspaceUri`, and assert the result with `workspaceTree`, so a case checks the file tree rather
  than the calls that produced it. Call `resetWorkspace()` from a `beforeEach`: `clearMocks` clears recorded calls,
  not module state. A suite that passes its own factory to `jest.mock("vscode", …)` replaces the fake entirely.
- A stub or a fake answer reproduces what the real code does. The real `getFileType` catches every error and returns
  `UNKNOWN`, so a stub that rejects tests a path no caller reaches. The fake's filesystem answers come from the host
  measurements in `src/web/test/suite/file-system.test.ts`, so a change to the fake starts there.
- `FileStat.type` is a bitmask: mask `File` (`(type & vscode.FileType.File) !== 0`) and compare `Directory` strictly,
  so a traversal does not descend into a linked directory.
- Level-2 specs use mocha's `tdd` interface: write `suite` and `test`, because a `describe` throws at import and aborts
  the whole run.
- `test:integration` serves the host on port 3000. Where that port is taken, the run hangs with `listen EADDRINUSE`
  after webpack finishes; run the script's command by hand with `--port <free>`.

When you write or change a Jest or integration test, a golden, or a specification import case, load the
`vscode-extension-testing` skill.
