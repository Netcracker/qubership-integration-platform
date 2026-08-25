// The in-memory workspace four suites share: `currentNameWins`, `unreadableCanonicalFile`,
// `unreadableApiFiles` and `deleteEntityFiles`. Each runs the real `VSCodeFileApi`, the real
// lookups and the real write and delete paths against this disk, so the only thing stubbed is
// `vscode.workspace.fs` itself.
//
// SCOPE. Only the `vscode` factory body lives here. Those suites share eight further `jest.mock`
// calls, and `jest.mock` resolves module paths relative to the calling file, so those calls stay in
// the suites that make them.
//
// `fileApiImpl.brokenScan.test.ts` deliberately stays outside this helper: it has no disk at all,
// only `jest.fn()` pairs over a static tree.
//
// USE. In a suite:
//
//   jest.mock("vscode", () => require("../../helpers/serviceDisk").vscodeApi(), { virtual: true });
//   import { disk, writeFile, deleteFile } from "../../helpers/serviceDisk";
//
// The `require` inside the factory is what keeps this order-independent: a `jest.mock` factory runs
// when the mocked module is first required, which is during the suite's own imports, so an eager
// read of a `const` declared in the suite body would land in its temporal dead zone.
//
// The spies are exported objects, so `expect(writeFile).not.toHaveBeenCalled()` compares the same
// `jest.fn()` the file api called. `clearMocks: true` resets calls between tests and keeps the
// implementations, which is what makes that safe.

import { joinUriPath } from "./mocks";

/** The workspace: path → file text. Directories are the prefixes of these paths. */
export const disk = new Map<string, string>();

/** What the code under test gets handed instead of a real `vscode.Uri`. */
export function fileRef(path: string): any {
  return {
    path,
    fsPath: path,
    with: (change: { path?: string }) => fileRef(change.path ?? path),
  };
}

export const stat = jest.fn(async (fileUri: any) => {
  if (disk.has(fileUri.path)) {
    return { type: 1, ctime: 0 };
  }
  for (const filePath of disk.keys()) {
    if (filePath.startsWith(`${fileUri.path}/`)) {
      return { type: 2, ctime: 0 };
    }
  }
  throw new Error(`EntryNotFound: ${fileUri.path}`);
});

export const readDirectory = jest.fn(async (folderUri: any) => {
  const prefix = `${folderUri.path}/`;
  const entries = new Map<string, number>();
  for (const filePath of disk.keys()) {
    if (!filePath.startsWith(prefix)) {
      continue;
    }
    const rest = filePath.slice(prefix.length);
    const slash = rest.indexOf("/");
    entries.set(slash < 0 ? rest : rest.slice(0, slash), slash < 0 ? 1 : 2);
  }
  if (entries.size === 0) {
    throw new Error(`EntryNotFound: ${folderUri.path}`);
  }
  return [...entries.entries()];
});

export const writeFile = jest.fn(async (fileUri: any, bytes: Uint8Array) => {
  disk.set(fileUri.path, new TextDecoder().decode(bytes));
});

/** Reads the way `vscode.workspace.fs` does: a missing path rejects rather than answering empty. */
export const readFile = jest.fn(async (fileUri: any) => {
  const text = disk.get(fileUri.path);
  if (text === undefined) {
    throw new Error(`EntryNotFound: ${fileUri.path}`);
  }
  return new TextEncoder().encode(text);
});

/**
 * Deletes the way `vscode.workspace.fs` does, including the failures: a directory still holding
 * files answers `Directory not empty`, anything else `EntryNotFound`. Only `deleteEntityFiles`
 * asserts on those, but the other three suites are measured green under them too, so there is no
 * lenient mode to opt out of — a delete that silently succeeds on a missing path is the same
 * infidelity as a stub that cannot fail.
 */
export const deleteFile = jest.fn(async (fileUri: any) => {
  if (!disk.has(fileUri.path)) {
    for (const filePath of disk.keys()) {
      if (filePath.startsWith(`${fileUri.path}/`)) {
        throw new Error(`Directory not empty: ${fileUri.path}`);
      }
    }
    throw new Error(`EntryNotFound: ${fileUri.path}`);
  }
  disk.delete(fileUri.path);
});

/** The module object a `jest.mock("vscode", …)` factory returns. */
export function vscodeApi() {
  const api = {
    FileType: { File: 1, Directory: 2 },
    Uri: {
      joinPath: jest.fn((base: any, ...segments: string[]) =>
        fileRef(joinUriPath(base, ...segments).path),
      ),
    },
    workspace: {
      workspaceFolders: [{ uri: { path: "/root" } }],
      fs: {
        stat: (...args: any[]) => stat(args[0]),
        readDirectory: (...args: any[]) => readDirectory(args[0]),
        readFile: (...args: any[]) => readFile(args[0]),
        writeFile: (...args: any[]) => writeFile(args[0], args[1]),
        delete: (...args: any[]) => deleteFile(args[0]),
        createDirectory: jest.fn(),
      },
    },
    window: {
      showInformationMessage: jest.fn(),
      showWarningMessage: jest.fn(),
      showErrorMessage: jest.fn(),
    },
  };
  return { __esModule: true, default: api, ...api };
}
