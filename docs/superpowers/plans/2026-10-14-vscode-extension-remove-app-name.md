# Remove the app name from the VS Code extension: implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the app-name-keyed configuration in `vscode-extension/` with `cip` constants, delete the configuration system and its public API, and repair the two things the UI's `qip` to `cip` rename broke.

**Architecture:** One constant, `FILE_EXTENSIONS` in `src/web/response/file/fileExtensions.ts`, replaces every lookup of file extensions. Schema URLs come from the existing `QIP_SCHEMA_URLS` and `CONF_MODEL_BASE_URL` in `src/web/services/qipSchemas.ts`. Then `ProjectConfigService`, `ConfigApiProvider`, the app-name constant, the config files, and the `QipExtensionAPI` are deleted.

**Tech Stack:** TypeScript 5.9, VS Code web extension API, webpack 5, Jest 29.

**Spec:** `docs/superpowers/specs/2026-10-14-vscode-extension-remove-app-name-design.md`

## Global Constraints

- The file extensions are exactly `.chain.cip.yaml`, `.service.cip.yaml`, `.context-service.cip.yaml`, `.mcp-service.cip.yaml`, `.specification-group.cip.yaml`, `.specification.cip.yaml`.
- The extension writes byte-identical files: no golden file under `src/web/test/golden/` changes.
- Every `qip` identifier other than `qip.exportImages` keeps its name: commands, editor view types, the `qip-main` view, the `qipExtension.useDefaultDiffView` setting, `Qip*` classes, the npm package name.
- `QIP_SCHEMA_URLS` keeps its four entries; `SpecificationValidator` maps each entry to a `QipSchemaType`.
- The cache TTL is `60000` milliseconds.
- Do not run `prettier --write` on whole files: several files touched here are not Prettier-clean, and a full reformat buries the change. Format the lines you edit by hand to match their neighbors.
- Run every command from `vscode-extension/` unless the step says otherwise.
- Commit messages follow Conventional Commits and end with the `Co-Authored-By` trailer that Claude Code's attribution settings give. The commit commands below write it as `<Co-Authored-By trailer>`; put the real trailer there. The repository's CyberFerret commit hook rejects a committed file that contains an email address, so the trailer goes only in commit messages.
- The branch ends with two new commits: `fix(vscode-extension): load cip-ui.css` (Task 1) and `refactor(vscode-extension)!: remove the configurable app name` (Tasks 2 to 5, which amend one commit).

## Review Focus

1. A legacy `*.chain.qip.yaml` file reaching `getEditorViewTypeForUri`, through `qip.revealInExplorer` or `navigateInNewTab`. The old code worked out app `qip` from the name and returned the chain editor; now the call throws `Unable to find an editor for document`, which matches the README's statement that `*.qip.yaml` files do not open. Task 2 pins this with a test.
2. A workspace that still has a `.config.qip.yaml` with custom extensions. The extension must not read it and must create files with the `cip` extensions. Task 2 pins this in `createEmptyFiles.test.ts`.
3. A change to a `*.specification.cip.yaml` or `*.specification-group.cip.yaml` file must still invalidate the cache once the six per-extension watchers become one `**/*.cip.yaml` watcher. Task 3 pins the watcher glob.
4. `qip.exportImagesFromExplorer` must call the renamed `cip.exportImages`, or the explorer button fails with "command not found". Task 4 updates the existing test that pins the delegation.
5. A chain opened from a `git:` URI in the diff view resolved its extensions from that URI. The constant ignores the URI, and the existing `git` case in `fileApiImpl.test.ts` (around line 262) keeps covering it.

---

### Task 1: Load the renamed UI stylesheet

The UI library now writes `dist-lib/cip-ui.css` (`ui/vite.config.lib.ts:32`). `getWebviewContent` in `src/web/extension.ts:990` still asks for `qip-ui.css`, so the webview opens unstyled.

**Files:**
- Modify: `vscode-extension/src/web/extension.ts:990`
- Test: `vscode-extension/tests/extension.test.ts` (the `getWebviewContent - importMapScript` block, around line 214)

**Interfaces:**
- Consumes: nothing.
- Produces: nothing other tasks use.

- [ ] **Step 1: Write the failing test**

In `tests/extension.test.ts`, inside `describe("getWebviewContent - importMapScript", …)`, after the test `includes React importmap when bundled JS file is missing`, add:

```ts
    test("links the cip-ui stylesheet", async () => {
      mockStatBehavior = "resolve";
      const provider = activateAndGetProvider();

      await provider.resolveCustomTextEditor(
        validDocument as any,
        mockPanel as any,
        {} as any,
      );

      expect(mockWebview.asWebviewUri).toHaveBeenCalledWith(
        expect.objectContaining({ path: "dist/web/qip-ui/cip-ui.css" }),
      );
    });
```

The shared `vscode` mock's `Uri.joinPath` (`tests/helpers/mocks.ts:12`) drops the base URI and joins the segments with `/`, so the path is `dist/web/qip-ui/cip-ui.css`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `npx jest -c jest.config.cjs tests/extension.test.ts -t "links the cip-ui stylesheet"`
Expected: FAIL. The received calls include `path: "dist/web/qip-ui/qip-ui.css"`.

If the path in the failure message differs from `dist/web/qip-ui/qip-ui.css` only in its prefix, put that prefix in the expectation and keep `cip-ui.css` as the file name.

- [ ] **Step 3: Write the minimal implementation**

In `src/web/extension.ts`, change:

```ts
    const cssFileUri = vscode.Uri.joinPath(qipUiAssetsRoot, "qip-ui.css");
```

to:

```ts
    const cssFileUri = vscode.Uri.joinPath(qipUiAssetsRoot, "cip-ui.css");
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `npx jest -c jest.config.cjs tests/extension.test.ts`
Expected: PASS, every test in the file.

- [ ] **Step 5: Commit**

```bash
git add src/web/extension.ts tests/extension.test.ts
git commit -m "fix(vscode-extension): load cip-ui.css

The UI library renamed its stylesheet to cip-ui.css, and the webview
still linked qip-ui.css, so editors opened without styles.

<Co-Authored-By trailer>"
```

---

### Task 2: Replace extension lookups with the `FILE_EXTENSIONS` constant

Every call that resolved file extensions or schema URLs through the app name reads constants instead. `ProjectConfigService` still exists after this task. Only `extension.ts` and `FileCacheService`'s TTL still use it, and Task 3 deletes it.

**Files:**
- Modify: `src/web/response/file/fileExtensions.ts` (rewrite)
- Modify: `src/web/response/file/fileApiImpl.ts`
- Modify: `src/web/response/serviceApiRead.ts`, `src/web/response/serviceApiModify.ts`, `src/web/response/chainApiRead.ts`
- Modify: `src/web/qipExplorer.ts`, `src/web/editorViewTypes.ts`
- Modify: `src/web/api-services/EnvironmentService.ts`, `SystemService.ts`, `SpecificationGroupService.ts`, `SpecificationImportService.ts`
- Modify: `src/web/services/QipSpecificationGenerator.ts`, `src/web/services/FileCacheService.ts`
- Modify (tests): `tests/web/editorViewTypes.test.ts`, `tests/web/response/file/createEmptyFiles.test.ts`, `tests/web/response/file/fileApiImpl.test.ts`, `tests/web/response/chainApiRead.test.ts`, `tests/web/response/listChainExportTargets.test.ts`, `tests/web/response/apiRouter.exportImage.test.ts`, `tests/response/serviceApiModify.test.ts`, `tests/response/serviceApiRead.contextService.test.ts`, `tests/response/serviceApiRead.protocol.test.ts`, `tests/response/serviceProtocolRoundTrip.test.ts`, `tests/web/response/serviceApiModify.deleteSpecificationByUri.test.ts`, `tests/web/response/serviceApiModify.deleteSpecificationGroupByUri.test.ts`

**Interfaces:**
- Consumes: `QIP_SCHEMA_URLS` (`SERVICE`, `CHAIN`, `SPECIFICATION`, `SPECIFICATION_GROUP`) and `CONF_MODEL_BASE_URL` from `src/web/services/qipSchemas.ts`, unchanged.
- Produces: `FILE_EXTENSIONS` with keys `chain`, `service`, `contextService`, `mcpService`, `specificationGroup`, `specification`, and `extractFilename(fileUri: { path: string } | string): string`, both exported from `src/web/response/file/fileExtensions.ts`. Nothing else stays in that module.

- [ ] **Step 1: Write the two Review Focus tests**

In `tests/web/editorViewTypes.test.ts`, inside `describe("getEditorViewTypeForUri", …)`, after `throws when no editor matches the file extension`, add:

```ts
    test("throws for a legacy .qip.yaml chain file", () => {
      const uri = Uri.file("/workspace/chains/chain-1.chain.qip.yaml");

      expect(() => getEditorViewTypeForUri(uri)).toThrow(
        "Unable to find an editor for document",
      );
    });
```

Then delete the module mock in the same file, so the test runs against the real module:

```ts
jest.mock("../../src/web/response/file/fileExtensions", () => ({
  getExtensionsForUri: jest.fn(() => ({
    chain: ".chain.cip.yaml",
    service: ".service.cip.yaml",
    contextService: ".context-service.cip.yaml",
    mcpService: ".mcp-service.cip.yaml",
  })),
}));
```

In `tests/web/response/file/createEmptyFiles.test.ts`:

1. Change the comment in the `vscode` mock from `// The embedded config is read from disk; the workspace has no .config.qip.yaml.` to `// Records every read, so a test can show that a file was never read.`
2. Replace the `stat` mock, which rejects every call, with one that finds only a workspace `.config.qip.yaml`:

```ts
        stat: async (uri: any) => {
          if (uri.path === "/workspace/.config.qip.yaml") {
            return { type: 1 };
          }
          throw new Error("not found");
        },
```

3. Make `readFile` answer that path with a config that would rename every file, if the extension still read it:

```ts
        readFile: async (uri: any) => {
          read.push(uri.path);
          if (uri.path === "/workspace/.config.qip.yaml") {
            return new TextEncoder().encode(
              "version: '1.0'\nconfigs:\n  cip:\n    extensions:\n      chain: .chain.custom.yaml\n",
            );
          }
          return nodeFs.readFileSync(uri.path);
        },
```

4. Delete the `ProjectConfigService` import and the `configService` variable, and reduce `beforeEach` to:

```ts
  beforeEach(() => {
    written.clear();
    read.length = 0;
    const context = {
      extensionUri: createMockUri(EXTENSION_ROOT),
    } as unknown as ExtensionContext;
    api = new VSCodeFileApi(context);
    setFileApi(api);
  });
```

5. Replace the whole `it("defaults to the cip app the shipped config declares", …)` test with:

```ts
  it("ignores a workspace .config.qip.yaml", async () => {
    mockShowInputBox.mockResolvedValueOnce("new-chain");

    const result = await api.createEmptyChain();

    expect(read).not.toContain("/workspace/.config.qip.yaml");
    expect([...written.keys()]).toEqual([
      `/workspace/${result!.chainId}/${result!.chainId}.chain.cip.yaml`,
    ]);
  });
```

6. Rename the `describe` from `"new files under the embedded default config"` to `"new files"`. Drop the now-unused `yaml` import only if nothing else in the file uses it; `onlyWrittenFile` still calls `yaml.parse`, so it normally stays.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `npx jest -c jest.config.cjs tests/web/editorViewTypes.test.ts tests/web/response/file/createEmptyFiles.test.ts`
Expected: FAIL.
- `throws for a legacy .qip.yaml chain file` fails because the real `getExtensionsForUri` works out app `qip` and returns the chain editor.
- `ignores a workspace .config.qip.yaml` fails: `ProjectConfigService.loadWorkspaceConfig` reads `/workspace/.config.qip.yaml`, so `read` contains it. If the chain-creation path does not load the workspace config in this mock setup, the `read` assertion passes before the change. Record that and continue; the test still guards against the file being read later.

- [ ] **Step 3: Rewrite `fileExtensions.ts`**

Replace the whole content of `src/web/response/file/fileExtensions.ts` with:

```ts
export const FILE_EXTENSIONS = {
  chain: ".chain.cip.yaml",
  service: ".service.cip.yaml",
  contextService: ".context-service.cip.yaml",
  mcpService: ".mcp-service.cip.yaml",
  specificationGroup: ".specification-group.cip.yaml",
  specification: ".specification.cip.yaml",
} as const;

export function extractFilename(fileUri: { path: string } | string): string {
  if (typeof fileUri === "string") {
    return fileUri.split("/").pop() || "";
  }
  return fileUri.path.split("/").pop() || "";
}
```

- [ ] **Step 4: Rewrite the helper calls**

Run this from `vscode-extension/` (Git Bash). It replaces each `getExtensionsForUri(…)`, `getExtensionsForFile(…)`, and `this.getExtensionsForContext(…)` call that has no nested parentheses with `FILE_EXTENSIONS`:

```bash
sed -i -E 's/(getExtensionsForUri|getExtensionsForFile|this\.getExtensionsForContext)\([^()]*\)/FILE_EXTENSIONS/g' \
  src/web/response/serviceApiRead.ts \
  src/web/response/serviceApiModify.ts \
  src/web/response/chainApiRead.ts \
  src/web/response/file/fileApiImpl.ts \
  src/web/qipExplorer.ts \
  src/web/editorViewTypes.ts \
  src/web/api-services/EnvironmentService.ts \
  src/web/api-services/SystemService.ts
grep -rn "getExtensionsFor\|getExtensionsForContext" src
```

Expected `grep` output: only the import lines in those eight files, plus the private method in `fileApiImpl.ts`:

```ts
  private getExtensionsForContext(currentFileUri?: Uri) {
    if (currentFileUri) {
      return getExtensionsForFile(extractFilename(currentFileUri));
    }
    return FILE_EXTENSIONS;
  }
```

Delete that method. Then fix the imports:

| File | Old import | New import |
| --- | --- | --- |
| `src/web/response/serviceApiRead.ts` | `import { getExtensionsForUri } from "./file/fileExtensions";` | `import { FILE_EXTENSIONS } from "./file/fileExtensions";` |
| `src/web/response/chainApiRead.ts` | `import { getExtensionsForUri } from "./file/fileExtensions";` | `import { FILE_EXTENSIONS } from "./file/fileExtensions";` |
| `src/web/response/serviceApiModify.ts` | `import { getExtensionsForFile } from "./file/fileExtensions";` | `import { FILE_EXTENSIONS } from "./file/fileExtensions";` |
| `src/web/qipExplorer.ts` | `import { getExtensionsForFile } from "./response/file/fileExtensions";` | `import { FILE_EXTENSIONS } from "./response/file/fileExtensions";` |
| `src/web/editorViewTypes.ts` | `import { getExtensionsForUri } from "./response/file/fileExtensions";` | `import { FILE_EXTENSIONS } from "./response/file/fileExtensions";` |
| `src/web/api-services/EnvironmentService.ts` | `import { getExtensionsForFile } from "../response/file/fileExtensions";` | `import { FILE_EXTENSIONS } from "../response/file/fileExtensions";` |
| `src/web/api-services/SystemService.ts` | `import { getExtensionsForFile } from "../response/file/fileExtensions";` | `import { FILE_EXTENSIONS } from "../response/file/fileExtensions";` |

In `src/web/response/file/fileApiImpl.ts`, replace:

```ts
import {
  getExtensionsForFile,
  extractFilename,
  FileExtensionsConfig,
} from "./fileExtensions";
```

with:

```ts
import { FILE_EXTENSIONS, extractFilename } from "./fileExtensions";
import { CONF_MODEL_BASE_URL, QIP_SCHEMA_URLS } from "../../services/qipSchemas";
```

In `getFileType` in the same file, the `sed` left:

```ts
      const extensions: FileExtensionsConfig =
        FILE_EXTENSIONS;
```

Change it to:

```ts
      const extensions = FILE_EXTENSIONS;
```

In `src/web/editorViewTypes.ts`, `getEditorViewTypeForUri` now reads `const fileExtensions = FILE_EXTENSIONS;`. Keep the local name; it is one line.

- [ ] **Step 5: Rewrite the `ProjectConfigService.getConfig()` calls outside `extension.ts`**

`src/web/response/file/fileApiImpl.ts`, in `createEmptyChain` (around line 605):

```ts
      const config = ProjectConfigService.getConfig();
      const chainFileUri = vscode.Uri.joinPath(
        folderUri,
        `${chainId}${config.extensions.chain}`,
      );
      const chain = {
        $schema: config.schemaUrls.chain,
```

becomes:

```ts
      const chainFileUri = vscode.Uri.joinPath(
        folderUri,
        `${chainId}${FILE_EXTENSIONS.chain}`,
      );
      const chain = {
        $schema: QIP_SCHEMA_URLS.CHAIN,
```

`src/web/response/file/fileApiImpl.ts`, in `createEmptyService` (around line 727): delete `const config = ProjectConfigService.getConfig();` and replace, in the rest of that method:

| Old | New |
| --- | --- |
| `config.schemaUrls.contextService` | `` `${CONF_MODEL_BASE_URL}context-service` `` |
| `config.schemaUrls.mcpService` | `` `${CONF_MODEL_BASE_URL}mcp-service` `` |
| `config.schemaUrls.service` | `QIP_SCHEMA_URLS.SERVICE` |
| `config.extensions.<key>` | `FILE_EXTENSIONS.<key>` |

Then delete `import { ProjectConfigService } from "../../services/ProjectConfigService";` from `fileApiImpl.ts`, and confirm `grep -n "config\." src/web/response/file/fileApiImpl.ts` finds no leftover `config.extensions` or `config.schemaUrls`.

`src/web/response/serviceApiModify.ts` (around line 151): delete `const config = ProjectConfigService.getConfig();`, change `$schema: config.schemaUrls.service,` to `$schema: QIP_SCHEMA_URLS.SERVICE,`, replace any `config.extensions.<key>` in that function with `FILE_EXTENSIONS.<key>`, delete `import { ProjectConfigService } from "../services/ProjectConfigService";`, and add `import { QIP_SCHEMA_URLS } from "../services/qipSchemas";`.

`src/web/api-services/SpecificationGroupService.ts` (lines 29 and 96): delete both `const config = ProjectConfigService.getConfig();` lines, replace both `config.extensions.specificationGroup` with `FILE_EXTENSIONS.specificationGroup`, and replace `import { ProjectConfigService } from "../services/ProjectConfigService";` with `import { FILE_EXTENSIONS } from "../response/file/fileExtensions";`.

`src/web/api-services/SpecificationImportService.ts` (line 391): change

```ts
        const config = ProjectConfigService.getConfig();
        const specFileName = `${systemId}-${specificationGroup.name}-${specification.version}${config.extensions.specification}`;
```

to

```ts
        const specFileName = `${systemId}-${specificationGroup.name}-${specification.version}${FILE_EXTENSIONS.specification}`;
```

and replace `import { ProjectConfigService } from "../services/ProjectConfigService";` with `import { FILE_EXTENSIONS } from "../response/file/fileExtensions";`.

`src/web/services/QipSpecificationGenerator.ts` (line 27): change

```ts
    const config = ProjectConfigService.getConfig();
    return {
      $schema: config.schemaUrls.specification,
```

to

```ts
    return {
      $schema: QIP_SCHEMA_URLS.SPECIFICATION,
```

and replace `import { ProjectConfigService } from "./ProjectConfigService";` with `import { QIP_SCHEMA_URLS } from "./qipSchemas";`.

`src/web/services/FileCacheService.ts`: in `invalidateByUri` (line 154) and the two methods at lines 196 and 224, delete `const config = ProjectConfigService.getConfig();` and replace each `config.extensions.<key>` with `FILE_EXTENSIONS.<key>`. Change the import `import { extractFilename } from "../response/file/fileExtensions";` to `import { FILE_EXTENSIONS, extractFilename } from "../response/file/fileExtensions";`. Leave `getTTL` for Task 3.

Check:

```bash
grep -rn "ProjectConfigService" src
```

Expected: only `src/web/extension.ts`, `src/web/services/FileCacheService.ts` (`getTTL` and its import), `src/web/services/ProjectConfigService.ts`, `src/web/services/ProjectConfigService.test.ts`, and `src/web/services/ConfigApiProvider.ts`.

- [ ] **Step 6: Update the unit tests that mocked the old helpers**

Each module mock below replaced the real `fileExtensions` module. Delete it, so the code under test reads the real `FILE_EXTENSIONS`.

| File | Delete |
| --- | --- |
| `tests/web/response/chainApiRead.test.ts` | the `jest.mock(".../fileExtensions", () => ({ getExtensionsForUri: jest.fn() }))` block |
| `tests/web/response/listChainExportTargets.test.ts` | the `jest.mock(".../fileExtensions", …)` block returning `{ chain: "**/*.chain.cip.yaml" }` |
| `tests/web/response/apiRouter.exportImage.test.ts` | the same block |
| `tests/response/serviceApiModify.test.ts` | the `jest.mock(".../fileExtensions", …)` block returning `.qip-service.yaml` |
| `tests/response/serviceApiRead.protocol.test.ts` | the same block |
| `tests/response/serviceProtocolRoundTrip.test.ts` | the same block |
| `tests/response/serviceApiRead.contextService.test.ts` | the block returning `{ contextService: ".context-service.qip.yaml" }` |
| `tests/web/response/file/fileApiImpl.test.ts` | nothing yet; its `ProjectConfigService` mock goes in Task 3 |

Then fix the assertions that depended on the mocked values:

- `tests/web/response/listChainExportTargets.test.ts`: change `expect(findFiles).toHaveBeenCalledWith("**/*.chain.cip.yaml");` to `expect(findFiles).toHaveBeenCalledWith(".chain.cip.yaml");`. The mock returned a glob; the real constant is the suffix `fileApi.findFiles(extension)` takes.
- `tests/web/response/apiRouter.exportImage.test.ts`: run `grep -n "chain.cip.yaml\"" tests/web/response/apiRouter.exportImage.test.ts`, and change any `findFiles` expectation that names `"**/*.chain.cip.yaml"` to `".chain.cip.yaml"`.
- `tests/response/serviceApiRead.contextService.test.ts`: run `sed -i 's/\.qip\.yaml/.cip.yaml/g' tests/response/serviceApiRead.contextService.test.ts`. The expectation at line 55 becomes `".context-service.cip.yaml"`, and the fixture paths match.

In `tests/web/response/serviceApiModify.deleteSpecificationByUri.test.ts` and `tests/web/response/serviceApiModify.deleteSpecificationGroupByUri.test.ts`:

1. Run `sed -i 's/\.qip\.yaml/.cip.yaml/g'` on both files.
2. Replace `import { getExtensionsForFile } from "../../../src/web/response/file/fileExtensions";` with `import { FILE_EXTENSIONS } from "../../../src/web/response/file/fileExtensions";`.
3. Delete the `jest.mock(".../fileExtensions", () => ({ getExtensionsForFile: jest.fn() }));` block.
4. Delete `const mockGetExtensions = getExtensionsForFile as jest.Mock;` and the line `mockGetExtensions.mockReturnValue(extensions);` in `beforeEach`.
5. Replace the `const extensions = { appName: "qip", chain: …, specification: … };` object with `const extensions = FILE_EXTENSIONS;`.
6. Delete each `expect(mockGetExtensions).toHaveBeenCalledWith(…);` line: `deleteSpecificationByUri.test.ts:161` and `deleteSpecificationGroupByUri.test.ts:144`. The `findFileById` expectations next to them still check that the right extension was used.

- [ ] **Step 7: Run the unit suite and type check**

Run: `npx jest -c jest.config.cjs`
Expected: PASS, including the two tests from Step 1.

Run: `npm run check-types`
Expected: no errors. This needs `npm run prepare-deps` to have built `@netcracker/qip-ui` types. If `check-types` reports `Cannot find module '@netcracker/qip-ui'`, run `npm run prepare-deps` once and retry.

Run: `npx eslint src`
Expected: no errors. Do not use `npm run lint`: it passes `--fix` and rewrites files.

- [ ] **Step 8: Commit**

```bash
git add -A src tests
git commit -m "refactor(vscode-extension)!: remove the configurable app name

<Co-Authored-By trailer>"
```

Task 5 writes the final message body.

---

### Task 3: Delete the configuration system and the public API

**Files:**
- Delete: `src/web/constants/appName.ts`, `src/web/services/ProjectConfigService.ts`, `src/web/services/ProjectConfigService.test.ts`, `src/web/services/ConfigApiProvider.ts`, `configs/default.config.cip.yaml`, `.config.qip.yaml.example`
- Modify: `src/web/extension.ts`, `src/web/response/apiRouter.ts:527-528`, `src/web/services/FileCacheService.ts`, `src/web/services/index.ts`, `src/web/index.ts`, `package.json` (`files`)
- Modify (tests): `tests/helpers/mocks.ts`, `tests/extension.test.ts`, `tests/extension.exportImages.test.ts`, `tests/response/serviceApiModify.test.ts`, `tests/response/serviceProtocolRoundTrip.test.ts`, `tests/api-services/SpecificationImportService.test.ts`, `tests/web/response/file/fileApiImpl.test.ts`

**Interfaces:**
- Consumes: `FILE_EXTENSIONS` from Task 2.
- Produces: `activate(context: ExtensionContext): void`. The node library (`src/web/index.ts`) no longer exports `QipExtensionAPI`, `ConfigApi`, `ConfigApiProvider`, or `ProjectConfig`.

- [ ] **Step 1: Write the watcher test**

In `tests/extension.test.ts`, add a top-level `describe` after the existing `getWebviewContent - importMapScript` block:

```ts
  describe("file watchers", () => {
    test("watches every *.cip.yaml file with a single watcher", async () => {
      const vscode = require("vscode");
      vscode.workspace.createFileSystemWatcher.mockClear();

      activate(buildMockContext());
      await new Promise((resolve) => setImmediate(resolve));

      expect(
        vscode.workspace.createFileSystemWatcher.mock.calls.map(
          (call: unknown[]) => call[0],
        ),
      ).toEqual(["**/*.cip.yaml"]);
    });
  });
```

If the block containing the other tests is not a `describe`, put this `describe` at the same nesting level as `describe("getWebviewContent - importMapScript", …)`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `npx jest -c jest.config.cjs tests/extension.test.ts -t "single watcher"`
Expected: FAIL. With `stubProjectConfigService`, `getAllConfigs()` returns `[]` and `buildDefaultConfig()` returns two extensions, so the calls are `["**/*.qip-chain.yaml", "**/*.qip-service.yaml", "**/.config.qip.yaml"]`.

- [ ] **Step 3: Rewrite `setupFileWatchers` and `activate` in `src/web/extension.ts`**

Replace the whole `async function setupFileWatchers(context: ExtensionContext): Promise<void> { … }` with:

```ts
function setupFileWatchers(context: ExtensionContext): void {
  const cacheService = FileCacheService.getInstance();
  const watcher = vscode.workspace.createFileSystemWatcher("**/*.cip.yaml");

  watcher.onDidChange((uri) => cacheService.invalidateByUri(uri));
  watcher.onDidDelete((uri) => cacheService.invalidateByUri(uri));
  watcher.onDidCreate((uri) => cacheService.invalidateByUri(uri));

  context.subscriptions.push(watcher);
}
```

In `activate`:

1. Change the signature `export function activate(context: ExtensionContext): QipExtensionAPI {` to `export function activate(context: ExtensionContext): void {`.
2. Delete:

```ts
  const projectConfigService = ProjectConfigService.getInstance();
  projectConfigService.setContext(context);
```

3. Replace:

```ts
  setupFileWatchers(context).catch((error) => {
    console.error("[QIP] Failed to setup file watchers:", error);
  });
```

with:

```ts
  setupFileWatchers(context);
```

4. Delete the last statement of `activate`, `return ConfigApiProvider.getInstance();`.

In `enrichWebview`, delete:

```ts
  if (fileUri) {
    try {
      await initializeContextFromFile(fileUri);
    } catch (error) {
      console.error("Failed to initialize context from file:", error);
    }
  }
```

Leave the `fileUri` parameter; the rest of `enrichWebview` still passes it to `getApiResponse`.

Delete the `export interface QipExtensionAPI { … }` block near the top of the file. Then delete these imports:

```ts
import {
  DEFAULT_APP_NAME,
  initializeContextFromFile,
} from "./response/file/fileExtensions";
```

```ts
import {
  ProjectConfigService,
  CONFIG_FILENAME,
  ProjectConfig,
} from "./services/ProjectConfigService";
import { ConfigApiProvider } from "./services/ConfigApiProvider";
```

If `Uri` from the `vscode` import list becomes unused, `tsc` reports it in Step 7; remove it then. It is still used elsewhere in the file at the time of writing.

- [ ] **Step 4: Remove the other references**

`src/web/response/apiRouter.ts`, in `getExtensionConfiguration` (around line 527), delete these two lines:

```ts
    // Stays "qip": the UI builds the /qip-routes/ prefix from it, and app.prefix is still qip.
    appName: "qip",
```

`src/web/services/FileCacheService.ts`: replace `getTTL` with a module constant. Add below the imports:

```ts
const CACHE_TTL_MS = 60000;
```

and replace:

```ts
  private getTTL(): number {
    try {
      const config = ProjectConfigService.getConfig();
      return config.cache?.ttl ?? 60000;
    } catch {
      return 60000;
    }
  }

  private isExpired(entry: CacheEntry): boolean {
    const ttl = this.getTTL();
    return Date.now() - entry.timestamp > ttl;
  }
```

with:

```ts
  private isExpired(entry: CacheEntry): boolean {
    return Date.now() - entry.timestamp > CACHE_TTL_MS;
  }
```

Delete `import { ProjectConfigService } from "./ProjectConfigService";`.

`src/web/services/index.ts`: delete these two lines:

```ts
export { ConfigApiProvider, ConfigApi } from "./ConfigApiProvider";
export { ProjectConfig } from "./ProjectConfigService";
```

`src/web/index.ts`: delete `export { QipExtensionAPI } from "./extension";`.

`package.json`: delete the `"configs",` entry from `files`.

Delete the files:

```bash
git rm src/web/constants/appName.ts \
  src/web/services/ProjectConfigService.ts \
  src/web/services/ProjectConfigService.test.ts \
  src/web/services/ConfigApiProvider.ts \
  configs/default.config.cip.yaml \
  .config.qip.yaml.example
```

If `src/web/constants/` is now empty, Git drops it on its own.

- [ ] **Step 5: Remove the test stubs for the deleted modules**

`tests/helpers/mocks.ts`: delete the whole `export function stubProjectConfigService(…) { … }`.

In each of these files, delete the `stubProjectConfigService` import specifier and the `jest.mock(".../services/ProjectConfigService", () => stubProjectConfigService());` block:
- `tests/extension.test.ts`
- `tests/extension.exportImages.test.ts`
- `tests/response/serviceApiModify.test.ts`
- `tests/response/serviceProtocolRoundTrip.test.ts`
- `tests/api-services/SpecificationImportService.test.ts`

In `tests/extension.test.ts` and `tests/extension.exportImages.test.ts`, also delete:
- the `jest.mock(".../services/ConfigApiProvider", () => ({ ConfigApiProvider: { getInstance: jest.fn().mockReturnValue({}) } }));` block;
- the `jest.mock(".../response/file/fileExtensions", () => ({ getExtensionsForUri: …, getExtensionsForFile: …, setCurrentFileContext: …, extractFilename: …, initializeContextFromFile: … }));` block. `extension.ts` no longer imports that module, and the modules it does import read the real constant.

`tests/web/response/file/fileApiImpl.test.ts`: delete the whole `jest.mock("../../../../src/web/services/ProjectConfigService", () => ({ … }));` block (lines 75 to about 95).

Check:

```bash
grep -rn "ProjectConfigService\|ConfigApi\|stubProjectConfigService\|DEFAULT_APP_NAME\|appName\|initializeContextFromFile\|getExtensionsFor\|config\.qip\|QipExtensionAPI" src tests
```

Expected: no output.

- [ ] **Step 6: Run the watcher test to verify it passes**

Run: `npx jest -c jest.config.cjs tests/extension.test.ts`
Expected: PASS, including `watches every *.cip.yaml file with a single watcher`.

- [ ] **Step 7: Run the whole unit suite, the type check, and lint**

Run: `npx jest -c jest.config.cjs`
Expected: PASS.

Run: `npm run check-types`
Expected: no errors.

Run: `npx eslint src`
Expected: no errors.

- [ ] **Step 8: Amend the commit**

```bash
git add -A src tests package.json configs .config.qip.yaml.example
git commit --amend --no-edit
```

---

### Task 4: Rename `qip.exportImages` to `cip.exportImages`

`BatchExportRunner` in the UI (`ui/src/batch/export/BatchExportRunner.tsx:173`) tells the user to call `cip.exportImages`. Only this command changes; `qip.exportImagesFromExplorer` keeps its ID.

**Files:**
- Modify: `src/web/extension.ts` (around lines 703 and 741), `package.json` (`activationEvents`, `contributes.commands`)
- Test: `tests/extension.exportImages.test.ts`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: the VS Code command `cip.exportImages(filePath?: string | Uri, config?: ExportImagesCommandConfig)`.

- [ ] **Step 1: Update the tests to the new ID**

In `tests/extension.exportImages.test.ts`, replace every `qip.exportImages` that is not followed by `FromExplorer`:

```bash
sed -i -E 's/qip\.exportImages([^F]|$)/cip.exportImages\1/g' tests/extension.exportImages.test.ts
grep -n "exportImages\"" tests/extension.exportImages.test.ts
```

Expected `grep` output: `registeredCommands.get("cip.exportImages")`, `registeredCommands.get("qip.exportImagesFromExplorer")`, and the delegation expectation `"cip.exportImages",` in `qip.exportImagesFromExplorer delegates to cip.exportImages with discovered targets`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `npx jest -c jest.config.cjs tests/extension.exportImages.test.ts`
Expected: FAIL with `cip.exportImages command was not registered`, and the delegation test fails because `executeCommand` received `"qip.exportImages"`.

- [ ] **Step 3: Rename the command**

In `src/web/extension.ts`, change the registration:

```ts
    vscode.commands.registerCommand(
      "qip.exportImages",
```

to:

```ts
    vscode.commands.registerCommand(
      "cip.exportImages",
```

and the call in the `qip.exportImagesFromExplorer` handler:

```ts
        await vscode.commands.executeCommand("qip.exportImages", undefined, {
```

to:

```ts
        await vscode.commands.executeCommand("cip.exportImages", undefined, {
```

In `package.json`, change `"qip.exportImages",` in `activationEvents` to `"cip.exportImages",`, and in `contributes.commands` change:

```json
        "command": "qip.exportImages",
        "title": "QIP Export Chain Images"
```

to:

```json
        "command": "cip.exportImages",
        "title": "QIP Export Chain Images"
```

Check: `grep -rn "qip\.exportImages\b[^F]" src package.json` returns nothing.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `npx jest -c jest.config.cjs tests/extension.exportImages.test.ts`
Expected: PASS.

- [ ] **Step 5: Amend the commit**

```bash
git add src/web/extension.ts package.json tests/extension.exportImages.test.ts
git commit --amend --no-edit
```

---

### Task 5: Documentation, full verification, final commit message

Load the `english-developer-style` and `change-description-authoring` skills, if installed, before writing the README text and the commit message.

**Files:**
- Modify: `README.md` (repository root, "VS Code extension" section, around line 91)
- Modify: `.apm/instructions/vscode-extension.instructions.md:67`
- Regenerated by `apm install`: `vscode-extension/AGENTS.md`, `.claude/rules/vscode-extension.md`, `.cursor/rules/vscode-extension.mdc`

**Interfaces:**
- Consumes: the finished code from Tasks 2 to 4.
- Produces: nothing.

- [ ] **Step 1: Update `README.md`**

In the "VS Code extension" section, replace the paragraph that starts with `If a workspace \`.config.qip.yaml\` has a \`configs.qip\` block` and runs to `its editors do not open them.` with:

```markdown
The extension no longer reads `.config.qip.yaml`. File extensions are always `*.cip.yaml`, so you can delete the file.
```

Keep the paragraph before it (`*.qip.yaml files from earlier versions do not open in the extension. Rename them to *.cip.yaml.`).

- [ ] **Step 2: Update the module instruction**

In `.apm/instructions/vscode-extension.instructions.md`, line 67 reads:

```markdown
Domain types (`Chain`, `Element`, `LibraryData`, message envelopes) come from `@netcracker/qip-ui`; schema types from `@netcracker/qip-schemas`. File extensions and schema URLs are configurable per app via a `.config.qip.yaml` (see `.config.qip.yaml.example`; defaults to the `cip` app from `configs/default.config.cip.yaml`).
```

Change it to:

```markdown
Domain types (`Chain`, `Element`, `LibraryData`, message envelopes) come from `@netcracker/qip-ui`; schema types from `@netcracker/qip-schemas`. File extensions are the `FILE_EXTENSIONS` constant in `src/web/response/file/fileExtensions.ts`, and schema URLs come from `src/web/services/qipSchemas.ts`.
```

In the same file, the "Node library" bullet mentions `QipExtensionAPI`. Change `exposing the response/services/api-services modules and \`QipExtensionAPI\` for programmatic embedding` to `exposing the response/services/api-services modules for programmatic embedding`. Confirm with `grep -n "QipExtensionAPI\|config.qip" .apm/instructions/vscode-extension.instructions.md` that nothing is left.

From the repository root, run `apm install` to regenerate the derived files. If `apm` is not installed, make the same two edits in `vscode-extension/AGENTS.md`, `.claude/rules/vscode-extension.md`, and `.cursor/rules/vscode-extension.mdc`, and say so in the handoff.

Check from the repository root:

```bash
git grep -n "config\.qip\|QipExtensionAPI\|default\.config\.cip" -- . ':!docs/superpowers' ':!.idea'
```

Expected: no output.

- [ ] **Step 3: Run the full verification**

From `vscode-extension/`:

```bash
npm run prepare-deps
npx jest -c jest.config.cjs
npm run check-types
npx eslint src
npm run compile-web
```

Expected: every command exits 0. `compile-web` copies `ui/dist-lib/cip-ui.css` into `dist/web/qip-ui/`; confirm with `ls dist/web/qip-ui/cip-ui.css`.

Then, where a headless Chromium is available:

```bash
npm run test:integration
```

Expected: PASS. The suite compares the files the extension writes against `src/web/test/golden/`. A golden-file failure means a call site in Task 2 picked up a different extension or schema URL; fix the call site, not the golden file. If Chromium cannot start here, record that the integration suite was not run.

Finally confirm the golden files are untouched: `git diff HEAD~2 --stat -- src/web/test/golden` prints nothing.

- [ ] **Step 4: Write the final commit message**

```bash
git add -A ../README.md ../.apm ../.claude/rules ../.cursor/rules AGENTS.md
git commit --amend -F - <<'EOF'
refactor(vscode-extension)!: remove the configurable app name

The extension keyed its file extensions and schema URLs by app name,
read them from a workspace .config.qip.yaml, and let other extensions
register more apps through the API that activate() returned. The
backend and the UI dropped the app name, and every deployment uses cip.
The extensions are now the FILE_EXTENSIONS constant and the schema URLs
come from qipSchemas.ts. The files the extension writes do not change.

One *.cip.yaml file watcher replaces the six per-extension watchers.

BREAKING CHANGE: the extension ignores .config.qip.yaml, and
activate() returns nothing. The node library no longer exports
QipExtensionAPI, ConfigApi, ConfigApiProvider, ProjectConfig, or the
getExtensionsFor* helpers; use FILE_EXTENSIONS instead. The
qip.exportImages command is now cip.exportImages, the name the UI's
export page gives.

<Co-Authored-By trailer>
EOF
git log --oneline -3
```

Expected: the top two commits are `refactor(vscode-extension)!: remove the configurable app name` and `fix(vscode-extension): load cip-ui.css`.
