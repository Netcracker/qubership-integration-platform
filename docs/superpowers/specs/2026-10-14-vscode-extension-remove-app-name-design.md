# Remove the configurable app name from the VS Code extension

## Goal

The backend services and the UI no longer take an application name: every deployment uses `cip`. The VS Code extension
still keys its file extensions and schema URLs by app name, reads them from a workspace `.config.qip.yaml`, and exposes
that configuration through a public API. This change makes the `cip` values constants and deletes the rest.

Success means:

- the extension writes byte-identical files, so the golden files under `src/web/test/golden/` and the e2e spec
  `e2e/specs/api/extension-output.spec.ts` need no change;
- no source file under `vscode-extension/` refers to an app name;
- the webview loads its stylesheet again, which the UI rename broke on this branch.

## Decisions

- **Delete the configuration system outright.** Nothing in the monorepo calls the public config API, and the UI and
  backend dropped the same setting. No override file and no compatibility shim remain.
- **Keep the `qip` brand everywhere else.** Command IDs, editor view types, the `qip-main` view, the
  `qipExtension.useDefaultDiffView` setting, `Qip*` identifiers, and the npm package name keep `qip`. A full
  `qip` to `cip` rename is a separate change.
- **Fix only what the UI rename already broke:** the stylesheet name and the `exportImages` command ID.

## What exists today

| Piece | Location | Role |
| --- | --- | --- |
| `DEFAULT_APP_NAME = "cip"` | `src/web/constants/appName.ts` | Default app name |
| `ProjectConfigService` | `src/web/services/ProjectConfigService.ts` | Loads `configs/default.config.cip.yaml` and the workspace `.config.qip.yaml`, keys configs by app name, substitutes `${appName}`, and tracks a "current" app per opened file |
| `ConfigApiProvider` | `src/web/services/ConfigApiProvider.ts` | Backs the public `QipExtensionAPI` (`loadConfigFromPath`, `registerConfig`, `unregisterConfig`, `getConfig`) that `activate()` returns |
| App detection | `src/web/response/file/fileExtensions.ts` | Works out the app from a file suffix such as `.chain.<app>.yaml`, and serves `getExtensionsForFile`, `getExtensionsForUri`, `getDefaultExtensions`, `getExtensionsFromConfig`, and `initializeContextFromFile` |
| Watchers | `setupFileWatchers` in `src/web/extension.ts` | One file watcher per configured extension, plus a `.config.qip.yaml` watcher that offers a window reload |
| Webview prop | `getAppExtensionProps` in `src/web/response/apiRouter.ts` | Sends `appName: "qip"`, which `AppExtensionProps` in the UI no longer declares |
| Config files | `configs/default.config.cip.yaml`, `.config.qip.yaml.example` | Embedded defaults and an example workspace file; `package.json` ships `configs` in `files` |

## Design

### Constants

`src/web/response/file/fileExtensions.ts` keeps `extractFilename` and gains one constant in place of everything else:

```ts
export const FILE_EXTENSIONS = {
  chain: ".chain.cip.yaml",
  service: ".service.cip.yaml",
  contextService: ".context-service.cip.yaml",
  mcpService: ".mcp-service.cip.yaml",
  specificationGroup: ".specification-group.cip.yaml",
  specification: ".specification.cip.yaml",
} as const;
```

Schema URLs come from `src/web/services/qipSchemas.ts`. `QIP_SCHEMA_URLS` already holds the chain, service,
specification, and specification-group URLs. The context-service and MCP-service URLs are used once each, in
`fileApiImpl.ts`, and are written there as `${CONF_MODEL_BASE_URL}context-service` and
`${CONF_MODEL_BASE_URL}mcp-service`. `QIP_SCHEMA_URLS` stays as it is, because `SpecificationValidator` maps every
entry in it to a `QipSchemaType`.

`FileCacheService` takes its TTL from a module constant of `60000` milliseconds, the value every config file and default
used.

### Call sites

Each call below reads the constant instead. The argument, a file name or URI that selected the app, is dropped.

| File | Calls replaced |
| --- | --- |
| `src/web/response/serviceApiRead.ts` | 12 `getExtensionsForUri(…)` |
| `src/web/response/chainApiRead.ts` | 4 `getExtensionsForUri(…)` |
| `src/web/response/serviceApiModify.ts` | 3 `getExtensionsForFile(…)`, 1 `ProjectConfigService.getConfig().schemaUrls.service` |
| `src/web/response/file/fileApiImpl.ts` | the private `getExtensionsForContext` and its 6 callers, 1 `getExtensionsForFile()`, 2 `ProjectConfigService.getConfig()` |
| `src/web/qipExplorer.ts` | 2 `getExtensionsForFile()` |
| `src/web/editorViewTypes.ts` | 1 `getExtensionsForUri(uri)` |
| `src/web/api-services/EnvironmentService.ts`, `SystemService.ts` | 1 `getExtensionsForFile()` each |
| `src/web/api-services/SpecificationGroupService.ts` | 2 `ProjectConfigService.getConfig().extensions.specificationGroup` |
| `src/web/api-services/SpecificationImportService.ts` | 1 `ProjectConfigService.getConfig().extensions.specification` |
| `src/web/services/QipSpecificationGenerator.ts` | 1 `ProjectConfigService.getConfig().schemaUrls.specification` |
| `src/web/services/FileCacheService.ts` | 4 `ProjectConfigService.getConfig()`, for the TTL and the service extensions |

### Deletions

- `src/web/constants/appName.ts`
- `src/web/services/ProjectConfigService.ts` and `ProjectConfigService.test.ts`
- `src/web/services/ConfigApiProvider.ts`
- `configs/default.config.cip.yaml`, `.config.qip.yaml.example`, and `configs` in the `package.json` `files` list
- in `fileExtensions.ts`: `FileExtensionsConfig`, `buildDefaultExtensions`, `DEFAULT_APP_NAME`, `setDefaultAppName`,
  `getDefaultAppName`, `getDefaultExtensions`, `setCurrentFileContext`, `getCurrentFileContext`,
  `extractAppNameFromExtension`, `getExtensionsForFile`, `getExtensionsForUri`, `initializeContextFromFile`, and
  `getExtensionsFromConfig`
- in `extension.ts`: the `QipExtensionAPI` interface, the `initializeContextFromFile` call when an editor opens, the
  `.config.qip.yaml` watcher, and the per-app watcher loop. `activate()` returns `void`.
- in `apiRouter.ts`: `appName: "qip"` and the comment above it
- in the barrels: `ConfigApi`, `ConfigApiProvider`, and `ProjectConfig` from `src/web/services/index.ts`, and
  `QipExtensionAPI` from `src/web/index.ts`

### File watching

`setupFileWatchers` creates one watcher for `**/*.cip.yaml` in place of six, one per extension. Every file the
extension reads ends in `.cip.yaml`, so the watcher invalidates the same cache entries. `invalidateByUri` already
ignores a file name that matches none of the cached types.

### Fixes the UI rename broke

The UI commit `4c40945c0` renamed the library stylesheet and the command name in one of its messages.

- `getWebviewContent` in `extension.ts` loads `cip-ui.css` in place of `qip-ui.css`. Without this the webview opens
  unstyled.
- `qip.exportImages` becomes `cip.exportImages` in its `registerCommand` call, in the `executeCommand` call that
  `qip.exportImagesFromExplorer` makes, in `package.json` `contributes.commands` and `activationEvents`, and in
  `tests/extension.exportImages.test.ts`. `BatchExportRunner` in the UI tells the user to call `cip.exportImages`.

### Documentation

- `README.md`: the paragraph about moving a `configs.qip` block to `configs.cip` is replaced by one stating that the
  extension no longer reads `.config.qip.yaml`, that file extensions are always `*.cip.yaml`, and that the file can be
  deleted.
- `.apm/instructions/vscode-extension.instructions.md`: the sentence about `.config.qip.yaml` changes the same way.
  `apm install` then regenerates `vscode-extension/AGENTS.md`, `.claude/rules/vscode-extension.md`, and
  `.cursor/rules/vscode-extension.mdc`.

## Testing

- Unit tests that mock `fileExtensions`, `ProjectConfigService`, or `ConfigApiProvider` drop those mocks and read the
  real `FILE_EXTENSIONS`. Their mocked values are already the `cip` ones. The affected files are
  `tests/extension.test.ts`, `tests/extension.exportImages.test.ts`, `tests/response/serviceApiModify.test.ts`,
  `tests/response/serviceApiRead.contextService.test.ts`, `tests/response/serviceApiRead.protocol.test.ts`,
  `tests/response/serviceProtocolRoundTrip.test.ts`, `tests/api-services/SpecificationImportService.test.ts`,
  `tests/web/editorViewTypes.test.ts`, `tests/web/response/apiRouter.exportImage.test.ts`,
  `tests/web/response/chainApiRead.test.ts`, `tests/web/response/listChainExportTargets.test.ts`,
  `tests/web/response/file/fileApiImpl.test.ts`, and the two `serviceApiModify.delete*ByUri` tests.
- `tests/web/response/file/createEmptyFiles.test.ts` keeps its file-creation assertions and drops the ones about loaded
  configs.
- The constant gets no test of its own.
- Verification: `npm run test:unit`, `npm run check-types`, `npm run lint`, and `npm run compile-web` in
  `vscode-extension/`, after `npm run prepare-deps` builds the UI library. `npm run test:integration` runs where a
  headless Chromium is available. It covers the golden files.
- A repository grep for `appName`, `DEFAULT_APP_NAME`, `ProjectConfigService`, `ConfigApi`, and `config.qip` under
  `vscode-extension/` returns nothing.

## Commits

1. `fix(vscode-extension): load cip-ui.css`, which repairs the webview styling on its own.
2. `refactor(vscode-extension)!: remove the configurable app name`, with a `BREAKING CHANGE` footer:
   - the node library no longer exports `QipExtensionAPI`, `ConfigApi`, `ConfigApiProvider`, `ProjectConfig`, or the
     `getExtensionsFor*` helpers, and `activate()` returns nothing;
   - the extension ignores `.config.qip.yaml`;
   - `qip.exportImages` is now `cip.exportImages`.

## Out of scope

- Renaming the other `qip` identifiers: commands, view types, the explorer view, settings, class names, and the package
  name.
- Any change to the UI, the schemas, or the backend.
