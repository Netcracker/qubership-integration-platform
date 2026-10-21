// An in-memory `workspace.fs` for the Jest fake of `vscode`. The answers here were all measured
// against the real web host by `src/web/test/suite/file-system.test.ts`, whose header lists them,
// and `file-system.test.ts` beside this file holds the fake to each one. `copy` is left out,
// because the layer has no call site for it.
//
// `getParentDirectoryUri` and `findFileById` in `fileApiImpl.ts` catch the "FileNotFound" rejection
// a `stat` on a missing path gives, whose `message` is the uri's `toString()`. Only that error's
// `name` was measured; the other three names below follow its shape.
//
// Deleting a symbolic link removes the link alone, as it does on disk. The host cannot create one,
// so a case plants it with `seedEntry` and seeds the target's files under the link itself.

export const FileType = {
  Unknown: 0,
  File: 1,
  Directory: 2,
  SymbolicLink: 64,
} as const;

// The members the filesystem reads off a uri. The fake's `Uri` lives in `index.ts`.
export type FsUri = {
  scheme: string;
  authority: string;
  path: string;
  toString(): string;
};

type Entry = {
  type: number;
  bytes?: Uint8Array;
  ctime: number;
  mtime: number;
};

const ERROR_NAMES: Record<string, string> = {
  FileNotFound: "EntryNotFound",
  FileExists: "EntryExists",
  FileNotADirectory: "EntryNotADirectory",
  FileIsADirectory: "EntryIsADirectory",
};

export class FileSystemError extends Error {
  readonly code: string;

  private constructor(code: string, uri: FsUri) {
    super(uri.toString());
    this.code = code;
    this.name = `${ERROR_NAMES[code]} (FileSystemError)`;
  }

  static FileNotFound(uri: FsUri): FileSystemError {
    return new FileSystemError("FileNotFound", uri);
  }

  static FileExists(uri: FsUri): FileSystemError {
    return new FileSystemError("FileExists", uri);
  }

  static FileNotADirectory(uri: FsUri): FileSystemError {
    return new FileSystemError("FileNotADirectory", uri);
  }

  static FileIsADirectory(uri: FsUri): FileSystemError {
    return new FileSystemError("FileIsADirectory", uri);
  }
}

const entries = new Map<string, Entry>();

// The authority belongs in the key: the level-2 host mounts at `vscode-test-web://mount/`, so two
// trees would collapse into one without it.
export function uriKey(uri: FsUri): string {
  return `${uri.scheme}://${uri.authority}${uri.path.replace(/\/+$/, "")}`;
}

const isDirectory = (entry: Entry) => (entry.type & FileType.Directory) !== 0;

// Every ancestor of `uri` down to its root, nearest first. The root is `scheme://authority`, so the
// level-2 host's `vscode-test-web://mount/a/b` stops at `.../mount/a` and the mount root is never
// created, while `file:///workspace/a/b` has an empty authority and stops at `file:///workspace`.
function ancestorKeys(uri: FsUri): string[] {
  const root = `${uri.scheme}://${uri.authority}`;
  const segments = uriKey(uri)
    .slice(root.length)
    .split("/")
    .filter((segment) => segment !== "");
  const ancestors: string[] = [];
  for (let end = segments.length - 1; end > 0; end -= 1) {
    ancestors.push(`${root}/${segments.slice(0, end).join("/")}`);
  }
  return ancestors;
}

// Creates the missing ancestors of `uri`. An ancestor held by a file stops the call, as it does on
// the host, rather than leaving the file in place and writing the child beside it.
function createParents(uri: FsUri): void {
  for (const ancestor of ancestorKeys(uri).reverse()) {
    const existing = entries.get(ancestor);
    if (existing) {
      if (!isDirectory(existing)) {
        throw FileSystemError.FileExists(uri);
      }
      continue;
    }
    entries.set(ancestor, { type: FileType.Directory, ctime: Date.now(), mtime: Date.now() });
  }
}

function entryAt(uri: FsUri): Entry {
  const entry = entries.get(uriKey(uri));
  if (!entry) {
    throw FileSystemError.FileNotFound(uri);
  }
  return entry;
}

export const fileSystem = {
  async stat(uri: FsUri) {
    const entry = entryAt(uri);
    return {
      type: entry.type,
      ctime: entry.ctime,
      mtime: entry.mtime,
      size: entry.bytes?.length ?? 0,
      permissions: undefined,
    };
  },

  async readDirectory(uri: FsUri): Promise<[string, number][]> {
    const entry = entryAt(uri);
    if (!isDirectory(entry)) {
      throw FileSystemError.FileNotADirectory(uri);
    }
    const prefix = `${uriKey(uri)}/`;
    const children: [string, number][] = [];
    for (const [key, child] of entries) {
      const name = key.startsWith(prefix) ? key.slice(prefix.length) : "";
      if (name && !name.includes("/")) {
        children.push([name, child.type]);
      }
    }
    return children;
  },

  async createDirectory(uri: FsUri): Promise<void> {
    const key = uriKey(uri);
    const existing = entries.get(key);
    if (existing) {
      if (!isDirectory(existing)) {
        throw FileSystemError.FileExists(uri);
      }
      return;
    }
    createParents(uri);
    entries.set(key, { type: FileType.Directory, ctime: Date.now(), mtime: Date.now() });
  },

  async readFile(uri: FsUri): Promise<Uint8Array> {
    const entry = entryAt(uri);
    if (isDirectory(entry)) {
      throw FileSystemError.FileIsADirectory(uri);
    }
    return entry.bytes ?? new Uint8Array();
  },

  async writeFile(uri: FsUri, bytes: Uint8Array): Promise<void> {
    const key = uriKey(uri);
    const existing = entries.get(key);
    if (existing && isDirectory(existing)) {
      throw FileSystemError.FileIsADirectory(uri);
    }
    createParents(uri);
    entries.set(key, {
      type: FileType.File,
      bytes: new Uint8Array(bytes),
      ctime: existing?.ctime ?? Date.now(),
      mtime: Date.now(),
    });
  },

  // `recursive` is ignored, as the host ignores it, and a missing path resolves. A symbolic link is
  // removed on its own, leaving the entries seeded under it, as deleting a link does on disk.
  async delete(uri: FsUri, _options?: { recursive?: boolean }): Promise<void> {
    const key = uriKey(uri);
    const entry = entries.get(key);
    entries.delete(key);
    if (entry && (entry.type & FileType.SymbolicLink) !== 0) {
      return;
    }
    const prefix = `${key}/`;
    for (const existing of [...entries.keys()]) {
      if (existing.startsWith(prefix)) {
        entries.delete(existing);
      }
    }
  },
};

// Plants one entry and its missing parents. `type` is the bitmask `stat` then reports, which is the
// only way to put a symbolic link in front of the layer: the host cannot create one.
export function seedEntry(uri: FsUri, type: number, bytes?: Uint8Array): void {
  const key = uriKey(uri);
  createParents(uri);
  entries.set(key, { type, bytes, ctime: Date.now(), mtime: Date.now() });
}

export function allEntries(): [string, Entry][] {
  return [...entries.entries()];
}

export function clearEntries(): void {
  entries.clear();
}
