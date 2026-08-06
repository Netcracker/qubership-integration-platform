// A typed service file that cannot be parsed, with its legacy sibling still on disk. The scan
// skips what it cannot read, so the lookup used to answer with the legacy file; the read then
// served the superseded body, and the write — which recomputes the name from the type — put that
// body over the unreadable typed file and deleted the legacy one. Everything saved since the
// conversion went with it. These cases run the real `VSCodeFileApi`, the real lookup and the real
// read and write paths against an in-memory disk.

import { joinUriPath, QIP_FILE_EXTENSIONS as ext } from "../../helpers/mocks";

/** The workspace: path → file text. Directories are the prefixes of these paths. */
const disk = new Map<string, string>();

/** What the code under test gets handed instead of a real `vscode.Uri`. */
function fileRef(path: string): any {
  return {
    path,
    fsPath: path,
    with: (change: { path?: string }) => fileRef(change.path ?? path),
  };
}

const stat = jest.fn(async (fileUri: any) => {
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

const readDirectory = jest.fn(async (folderUri: any) => {
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

const writeFile = jest.fn(async (fileUri: any, bytes: Uint8Array) => {
  disk.set(fileUri.path, new TextDecoder().decode(bytes));
});

const deleteFile = jest.fn(async (fileUri: any) => {
  disk.delete(fileUri.path);
});

jest.mock(
  "vscode",
  () => {
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
          readFile: async (fileUri: any) =>
            new TextEncoder().encode(disk.get(fileUri.path) ?? ""),
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
  },
  { virtual: true },
);

jest.mock("@netcracker/qip-ui", () => ({}), { virtual: true });
jest.mock("@netcracker/qip-schemas", () => ({}), { virtual: true });
jest.mock("yaml", () => ({
  stringify: (value: any) => JSON.stringify(value),
  parse: (text: string) => JSON.parse(text),
}));

jest.mock("../../../src/web/response/file/fileExtensions", () => ({
  getExtensionsForFile: jest.fn(() => ext),
  getExtensionsForUri: jest.fn(() => ext),
  extractFilename: (fileRef: any) =>
    (typeof fileRef === "string" ? fileRef : fileRef.path).split("/").pop() ??
    "",
}));

jest.mock("../../../src/web/services/ProjectConfigService", () => ({
  ProjectConfigService: {
    getConfig: () => ({ extensions: ext, schemaUrls: {} }),
    getInstance: () => undefined,
  },
}));

// The scan never routes a message, and pulling the router in drags the whole sibling graph along.
jest.mock("../../../src/web/response/apiRouter", () => ({
  SERVICE_ROUTES: [],
  CHAIN_ROUTES: [],
  CONTEXT_SERVICE_ROUTES: [],
  MCP_SERVICE_ROUTES: [],
}));
jest.mock("../../../src/web/extension", () => ({
  refreshQipExplorer: jest.fn(),
}));
jest.mock("../../../src/web/api-services/ApiGroupService", () => ({
  ApiGroupService: {
    regenerateGroupApisSafely: jest.fn(),
    resolveGroupFile: jest.fn(),
  },
}));

// The parser the whole read path shares. It reads the same in-memory disk, so a file holding text
// it cannot parse fails here exactly as a malformed document does.
jest.mock("../../../src/web/api-services/parsers/ContentParser", () => ({
  ContentParser: {
    parseContentFromFile: async (fileUri: any) => {
      const text = disk.get(fileUri.path);
      if (text === undefined) {
        throw new Error(`EntryNotFound: ${fileUri.path}`);
      }
      return JSON.parse(text);
    },
  },
}));

import { VSCodeFileApi } from "../../../src/web/response/file/fileApiImpl";
import { setFileApi } from "../../../src/web/response/file/fileApiProvider";
import { findServiceFileById } from "../../../src/web/response/file/serviceFileLookup";
import { getService } from "../../../src/web/response/serviceApiRead";
import { updateService } from "../../../src/web/response/serviceApiModify";
import { FileCacheService } from "../../../src/web/services/FileCacheService";

const SERVICE_ID = "svc-1";
const OTHER_ID = "svc-2";

const uri = fileRef;

const typedUri = uri(`/root/${SERVICE_ID}/${SERVICE_ID}${ext.externalService}`);
const legacyUri = uri(`/root/${SERVICE_ID}/${SERVICE_ID}${ext.service}`);
const otherTypedUri = uri(
  `/root/${OTHER_ID}/${OTHER_ID}${ext.externalService}`,
);
const otherLegacyUri = uri(`/root/${OTHER_ID}/${OTHER_ID}${ext.service}`);

const UNREADABLE_TEXT = "id: svc-1\n  name: [broken";

function serviceText(id: string, content: Record<string, unknown>): string {
  return JSON.stringify({
    id,
    name: "Orders",
    content: { protocol: "HTTP", ...content },
  });
}

beforeEach(() => {
  jest.clearAllMocks();
  disk.clear();
  FileCacheService.getInstance().clearAll();
  setFileApi(new VSCodeFileApi({} as any));
});

describe("a service whose typed file cannot be read, with the legacy sibling still there", () => {
  beforeEach(() => {
    disk.set(typedUri.path, UNREADABLE_TEXT);
    disk.set(
      legacyUri.path,
      serviceText(SERVICE_ID, {
        description: "superseded",
        integrationSystemType: "EXTERNAL",
      }),
    );
  });

  it("refuses to resolve the id rather than answering with the sibling", async () => {
    await expect(findServiceFileById(SERVICE_ID, ext)).rejects.toThrow(
      typedUri.path,
    );
  });

  it("fails the read rather than serving the superseded body", async () => {
    await expect(getService(legacyUri, SERVICE_ID)).rejects.toThrow(
      typedUri.path,
    );
  });

  it("fails the write and leaves both files exactly as they were", async () => {
    await expect(
      updateService(legacyUri, SERVICE_ID, { name: "Renamed" }),
    ).rejects.toThrow(typedUri.path);

    expect(disk.get(typedUri.path)).toBe(UNREADABLE_TEXT);
    expect(disk.get(legacyUri.path)).toBe(
      serviceText(SERVICE_ID, {
        description: "superseded",
        integrationSystemType: "EXTERNAL",
      }),
    );
    expect(writeFile).not.toHaveBeenCalled();
    expect(deleteFile).not.toHaveBeenCalled();
  });
});

// The refusal is scoped to the folder the service lives in: a file the scan cannot read elsewhere
// says nothing about this service, and a write lands beside the file the lookup resolved, so it
// cannot reach that file either.
describe("a file the scan cannot read in another service's folder", () => {
  beforeEach(() => {
    disk.set(otherTypedUri.path, UNREADABLE_TEXT);
  });

  it("still resolves a service stored under the typed name", async () => {
    disk.set(typedUri.path, serviceText(SERVICE_ID, { description: "typed" }));

    const fileUri = await findServiceFileById(SERVICE_ID, ext);

    expect(fileUri.path).toBe(typedUri.path);
  });

  it("still resolves a service stored under the legacy name alone", async () => {
    disk.set(
      legacyUri.path,
      serviceText(SERVICE_ID, { integrationSystemType: "EXTERNAL" }),
    );

    const fileUri = await findServiceFileById(SERVICE_ID, ext);

    expect(fileUri.path).toBe(legacyUri.path);
  });

  it("still converts that service on its next write", async () => {
    disk.set(
      legacyUri.path,
      serviceText(SERVICE_ID, {
        description: "only",
        integrationSystemType: "EXTERNAL",
      }),
    );

    const updated = await updateService(legacyUri, SERVICE_ID, {
      name: "Renamed",
    });

    expect(updated.name).toBe("Renamed");
    expect(disk.has(legacyUri.path)).toBe(false);
    expect(JSON.parse(disk.get(typedUri.path) ?? "{}")).toMatchObject({
      name: "Renamed",
      content: { description: "only" },
    });
    expect(disk.get(otherTypedUri.path)).toBe(UNREADABLE_TEXT);
  });

  it("still refuses the id whose own sibling that broken file may be", async () => {
    disk.set(
      otherLegacyUri.path,
      serviceText(OTHER_ID, { integrationSystemType: "EXTERNAL" }),
    );

    await expect(findServiceFileById(OTHER_ID, ext)).rejects.toThrow(
      otherTypedUri.path,
    );
  });
});
