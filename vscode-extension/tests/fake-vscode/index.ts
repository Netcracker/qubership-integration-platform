// The fake `vscode` module the Jest suite runs against: the in-memory filesystem from
// `file-system.ts`, a `Uri` that really joins paths, the window prompts the layer raises, and the
// tree-view types `cipExplorer.ts` builds its items from. `jest.config.cjs` maps `^vscode$` to
// `tests/__mocks__/vscode.ts`, which re-exports this module. The suites that pass their own factory
// to `jest.mock("vscode", …)` override that mapping and never load it.
//
// Deliberately left out, so that a case which needs one adds it. `workspace.fs.copy` has no call
// site in `src/web`; only the level-2 harness and its specs call it, which is what the header of
// `file-system.ts` says. `workspace.getConfiguration` is called from `extension.ts` and
// `window.withProgress` with `ProgressLocation` from `apiRouter.ts:107`, and every suite over those
// two passes its own factory to `jest.mock("vscode", …)`, so neither one reaches this module.
//
// The five prompts are plain `jest.fn`s resolving undefined, the answer a dismissed prompt gives; a
// case that needs an answer stubs it with `mockResolvedValueOnce`, which `resetWorkspace` then
// drops.
//
// Reset contract: a file that uses the fake calls `resetWorkspace()` from a `beforeEach`.
// `clearMocks` clears recorded calls, not module state, and Jest gives each file its own module
// registry, so the filesystem and the prompt answers would otherwise leak between the cases of one
// file.
import * as path from "path";
import type { Uri as ApiUri } from "vscode";
import {
  allEntries,
  clearEntries,
  FileSystemError,
  FileType,
  fileSystem,
  seedEntry,
  uriKey,
} from "./file-system";

export { FileSystemError, FileType };

const WORKSPACE_ROOT = "/workspace";

// `query` is here because the layer reads `uri.query` and `uri.with({ query })` for the `git` scheme
// at `fileApiImpl.ts:98-127`. A fragment is not, since nothing in `src/web` reads one.
export type UriParts = {
  scheme?: string;
  authority?: string;
  path?: string;
  query?: string;
};

export type FakeUri = {
  readonly scheme: string;
  readonly authority: string;
  readonly path: string;
  readonly query: string;
  readonly fsPath: string;
  with(change: UriParts): FakeUri;
  toString(): string;
};

function createUri(parts: UriParts): FakeUri {
  const scheme = parts.scheme ?? "file";
  const authority = parts.authority ?? "";
  const uriPath = parts.path ?? "";
  const query = parts.query ?? "";
  return {
    scheme,
    authority,
    path: uriPath,
    query,
    fsPath: uriPath,
    with: (change: UriParts) => createUri({ scheme, authority, path: uriPath, query, ...change }),
    toString: () => `${scheme}://${authority}${uriPath}` + (query ? `?${query}` : ""),
  };
}

const URI_PATTERN = /^([a-zA-Z][\w+.-]*):(?:\/\/([^/?#]*))?([^?#]*)(?:\?([^#]*))?/;

// A string with no scheme is a plain path, which is what `Uri.parse` falls back to.
function parseUri(value: string): FakeUri {
  const match = URI_PATTERN.exec(value);
  if (!match) {
    return createUri({ path: value });
  }
  const [, scheme, authority, uriPath, query] = match;
  return createUri({ scheme, authority, path: uriPath, query });
}

const join = (base: FakeUri, segments: string[]) =>
  base.with({ path: path.posix.join(base.path, ...segments) });

export const Uri = {
  file: jest.fn((filePath: string) => createUri({ path: filePath })),
  parse: jest.fn((value: string) => parseUri(value)),
  joinPath: jest.fn((base: FakeUri, ...segments: string[]) => join(base, segments)),
};

const workspaceRoot = createUri({ path: WORKSPACE_ROOT });

// The uri of a path relative to the workspace root, the counterpart of `seedWorkspace`. The fake's
// uri type is structural, so the one cast to the API's `Uri` lives here rather than in every caller
// that hands the result to the layer.
export function workspaceUri(relativePath = ""): ApiUri {
  const uri = relativePath ? join(workspaceRoot, [relativePath]) : workspaceRoot;
  return uri as unknown as ApiUri;
}

const defaultWorkspaceFolders = () => [{ uri: workspaceRoot, name: "workspace", index: 0 }];

export const workspace: {
  fs: typeof fileSystem;
  workspaceFolders: { uri: FakeUri; name: string; index: number }[] | undefined;
} = {
  fs: fileSystem,
  workspaceFolders: defaultWorkspaceFolders(),
};

// The declared answer type is `any` rather than `undefined`, so a case can stub an answer.
export const window = {
  showErrorMessage: jest.fn(async (_message: string, ..._items: any[]): Promise<any> => undefined),
  showInformationMessage: jest.fn(
    async (_message: string, ..._items: any[]): Promise<any> => undefined,
  ),
  showWarningMessage: jest.fn(
    async (_message: string, ..._items: any[]): Promise<any> => undefined,
  ),
  showInputBox: jest.fn(async (_options?: any): Promise<any> => undefined),
  showQuickPick: jest.fn(async (_items: readonly any[], _options?: any): Promise<any> => undefined),
};

const prompts: jest.Mock[] = [
  window.showErrorMessage,
  window.showInformationMessage,
  window.showWarningMessage,
  window.showInputBox,
  window.showQuickPick,
];

// The tree-view surface `cipExplorer.ts` builds its items from. The provider's constructor makes an
// `EventEmitter`, `refresh` fires it, and each item carries a `ThemeIcon` and a collapsible state.
export class EventEmitter<T> {
  private listeners: ((value: T) => void)[] = [];

  readonly event = (listener: (value: T) => void) => {
    this.listeners.push(listener);
    return {
      dispose: () => {
        this.listeners = this.listeners.filter((each) => each !== listener);
      },
    };
  };

  fire(value: T): void {
    for (const listener of [...this.listeners]) {
      listener(value);
    }
  }

  dispose(): void {
    this.listeners = [];
  }
}

export class ThemeIcon {
  constructor(readonly id: string) {}
}

export const TreeItemCollapsibleState = { None: 0, Collapsed: 1, Expanded: 2 } as const;

export type Seed = string | { content?: string; type?: number };

// Plants files under the workspace root, keyed by path relative to it. A seed carrying `type` sets
// the bitmask `stat` reports, so `FileType.File | FileType.SymbolicLink` plants a symbolic link,
// which no host call can create. A seed with no content is a directory. `readDirectory` reports the
// entries in the order they were planted, so a case whose reader returns on the first match plants
// that file first.
export function seedWorkspace(files: Record<string, Seed>): void {
  for (const [relativePath, seed] of Object.entries(files)) {
    const descriptor = typeof seed === "string" ? { content: seed } : seed;
    const bytes =
      descriptor.content === undefined
        ? undefined
        : new TextEncoder().encode(descriptor.content);
    const type = descriptor.type ?? (bytes === undefined ? FileType.Directory : FileType.File);
    seedEntry(workspaceUri(relativePath), type, bytes);
  }
}

const TYPE_NAMES: [number, string][] = [
  [FileType.Directory, "Directory"],
  [FileType.File, "File"],
  [FileType.SymbolicLink, "SymbolicLink"],
];

function typeName(type: number): string {
  const names = TYPE_NAMES.filter(([bit]) => (type & bit) !== 0).map(([, name]) => name);
  return names.length > 0 ? names.join("|") : "Unknown";
}

// Every entry under the workspace root, keyed by path relative to it: a file maps to its decoded
// text, and an entry with no content to its type, such as "<Directory>". A case asserts this rather
// than the calls that produced it.
export function workspaceTree(): Record<string, string> {
  const prefix = `${uriKey(workspaceRoot)}/`;
  const tree: Record<string, string> = {};
  for (const [key, entry] of allEntries()) {
    if (key.startsWith(prefix)) {
      tree[key.slice(prefix.length)] = entry.bytes
        ? new TextDecoder().decode(entry.bytes)
        : `<${typeName(entry.type)}>`;
    }
  }
  return tree;
}

export function resetWorkspace(): void {
  clearEntries();
  // The workspace root itself stats as a directory on every real host, so a case that seeds nothing
  // still finds one.
  seedEntry(workspaceRoot, FileType.Directory);
  workspace.workspaceFolders = defaultWorkspaceFolders();
  // `clearMocks` runs `mockClear`, which keeps a stubbed answer. Only `mockReset` drops one, and it
  // drops the implementation with it.
  for (const prompt of prompts) {
    prompt.mockReset();
    prompt.mockImplementation(async () => undefined);
  }
}
