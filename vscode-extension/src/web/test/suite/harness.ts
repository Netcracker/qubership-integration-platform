import * as assert from "assert";
import * as vscode from "vscode";
import * as yaml from "yaml";
import { setFileApi } from "../../response/file";
import { VSCodeFileApi } from "../../response/file/fileApiImpl";

const EXTENSION_NAME = "@netcracker/qip-vscode-extension";

// The `$schema` base every CIP document names.
export const SCHEMA = "http://netcracker.com/schemas/product/cloud-integration-platform/conf-model";

// Every suite's timeout: activating the extension and opening a webview take seconds each.
export const SUITE_TIMEOUT = 60000;

// Paths inside src/web/test/workspace, the folder test:integration mounts.
export const FIXTURES = {
  chain:
    "chains/1419390b-7423-4fab-853f-67c653e16a86/1419390b-7423-4fab-853f-67c653e16a86.chain.cip.yaml",
  service:
    "services/3b2e9742-139c-4750-bb76-adaf1cc9e3f0/3b2e9742-139c-4750-bb76-adaf1cc9e3f0.service.cip.yaml",
  contextService:
    "services/269327ed-e44d-491e-9f86-4a3010d63108/269327ed-e44d-491e-9f86-4a3010d63108.context-service.cip.yaml",
  mcpService:
    "services/0edab1e2-c311-4718-8411-adb16bfa5d54/0edab1e2-c311-4718-8411-adb16bfa5d54.mcp-service.cip.yaml",
};

export function workspaceRoot(): vscode.Uri {
  const folders = vscode.workspace.workspaceFolders;
  assert.ok(folders?.length, "test:integration mounts no workspace folder");
  return folders[0].uri;
}

export function workspaceUri(path: string): vscode.Uri {
  return vscode.Uri.joinPath(workspaceRoot(), path);
}

function extension(): vscode.Extension<unknown> {
  const found = vscode.extensions.all.find((candidate) => candidate.packageJSON.name === EXTENSION_NAME);
  assert.ok(found, `${EXTENSION_NAME} is not loaded`);
  return found;
}

export async function activateExtension(): Promise<void> {
  await extension().activate();
}

export function manifest(): any {
  return extension().packageJSON;
}

// The test bundle carries its own copy of the extension's modules, so their file API is configured
// here the way `activate` configures the extension's copy.
export function useExtensionModules(): vscode.ExtensionContext {
  const context = { extensionUri: extension().extensionUri } as vscode.ExtensionContext;
  setFileApi(new VSCodeFileApi(context));
  return context;
}

// Files the extension wrote, committed as the bytes it has to keep writing. The e2e suite imports
// them into the catalog (e2e/specs/api/extension-output.spec.ts). After a deliberate change, copy the
// text a failing comparison reports into the file. The path is `goldenDir` in webpack.config.js.
const goldenSources = require.context("../golden", false, /\.cip\.yaml$/);

export function golden(fileName: string): string {
  return goldenSources(`./${fileName}`);
}

export async function readText(uri: vscode.Uri): Promise<string> {
  return new TextDecoder().decode(await vscode.workspace.fs.readFile(uri));
}

export async function exists(uri: vscode.Uri): Promise<boolean> {
  try {
    await vscode.workspace.fs.stat(uri);
    return true;
  } catch {
    return false;
  }
}

export async function readYaml(uri: vscode.Uri): Promise<any> {
  return yaml.parse(await readText(uri));
}

export async function writeYaml(path: string, document: unknown): Promise<void> {
  await vscode.workspace.fs.writeFile(workspaceUri(path), new TextEncoder().encode(yaml.stringify(document)));
}

export async function copyFolder(from: string, to: string): Promise<void> {
  await vscode.workspace.fs.copy(workspaceUri(from), workspaceUri(to), { overwrite: true });
}

export function folderOf(path: string): string {
  return path.substring(0, path.lastIndexOf("/"));
}

export function fileNameOf(path: string): string {
  return path.substring(path.lastIndexOf("/") + 1);
}

// The file name with its id replaced by `*`, as a test title names a file type.
export function suffixOf(path: string): string {
  return fileNameOf(path).replace(/^[^.]*/, "*");
}

export async function listDirectory(uri: vscode.Uri): Promise<string[]> {
  const entries = await vscode.workspace.fs.readDirectory(uri);
  return entries.map(([name]) => name).sort();
}

// Lists every file under the folder, as paths relative to it.
export async function listTree(uri: vscode.Uri, prefix = ""): Promise<string[]> {
  const files: string[] = [];
  for (const [name, type] of await vscode.workspace.fs.readDirectory(uri)) {
    if (type === vscode.FileType.Directory) {
      files.push(...(await listTree(vscode.Uri.joinPath(uri, name), `${prefix}${name}/`)));
    } else {
      files.push(`${prefix}${name}`);
    }
  }
  return files.sort();
}

export async function closeAllEditors(): Promise<void> {
  await vscode.commands.executeCommand("workbench.action.closeAllEditors");
}

export function activeTab(): vscode.Tab | undefined {
  return vscode.window.tabGroups.activeTabGroup.activeTab;
}

export async function waitFor<T>(
  probe: () => T | undefined | Promise<T | undefined>,
  what: string,
  timeoutMs = 10000,
): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const value = await probe();
    if (value) {
      return value;
    }
    if (Date.now() > deadline) {
      throw new Error(`Timed out after ${timeoutMs} ms waiting for ${what}`);
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
}

// Fails if `probe` stops holding at any point within `ms`.
export async function holdsFor(probe: () => Promise<void>, ms: number): Promise<void> {
  const deadline = Date.now() + ms;
  while (Date.now() < deadline) {
    await probe();
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
}

// Waits until the active tab is the custom editor `viewType` showing `uri`.
export async function expectCustomEditor(viewType: string, uri: vscode.Uri): Promise<void> {
  const expected = `custom editor ${viewType} on ${uri}`;
  await waitFor(() => describeInput(activeTab()?.input) === expected, expected).catch(() =>
    assert.fail(`expected ${expected}, got ${describeInput(activeTab()?.input)}`),
  );
}

export function describeInput(input: unknown): string {
  if (input instanceof vscode.TabInputCustom) {
    return `custom editor ${input.viewType} on ${input.uri}`;
  }
  if (input instanceof vscode.TabInputText) {
    return `text editor on ${input.uri}`;
  }
  if (input instanceof vscode.TabInputWebview) {
    return `webview ${input.viewType}`;
  }
  return String(input);
}

type WindowStubs = {
  showInputBox?: (options?: vscode.InputBoxOptions) => string | undefined;
  showQuickPick?: (items: readonly any[]) => any;
  showWarningMessage?: (message: string, ...rest: unknown[]) => string | undefined;
};

// Answers the prompts a command raises. The test bundle and the extension share one `vscode` API object.
export async function withPrompts<T>(stubs: WindowStubs, run: () => Thenable<T>): Promise<T> {
  const window = vscode.window as any;
  const originals = Object.fromEntries(Object.keys(stubs).map((name) => [name, window[name]]));
  for (const [name, stub] of Object.entries(stubs)) {
    window[name] = async (...args: any[]) => (stub as (...a: any[]) => unknown)(...args);
  }
  try {
    return await run();
  } finally {
    Object.assign(window, originals);
  }
}
