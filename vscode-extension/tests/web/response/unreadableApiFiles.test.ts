// The three outcomes at the group and API level, where a folder can hold both names of one entity:
// `<id>.specification-group.` beside `<id>.api-group.`, `<id>.specification.` beside `<id>.api.`.
// Every scan here used to skip the file it could not parse and answer that id from its sibling —
// the superseded document served as the current one, and the edit that followed written to the file
// nobody is looking at. These cases run the real file api, the real scans and the real write paths
// against an in-memory disk.

import { joinUriPath, QIP_FILE_EXTENSIONS as ext } from "../../helpers/mocks";

/** The workspace: path → file text. Directories are the prefixes of these paths. */
const disk = new Map<string, string>();

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
import {
  getApiSpecifications,
  getOperationInfo,
  getOperations,
  getSpecificationModel,
} from "../../../src/web/response/serviceApiRead";
import {
  updateApiSpecificationGroup,
  updateSpecificationModel,
} from "../../../src/web/response/serviceApiModify";
import { ApiGroupService } from "../../../src/web/api-services/ApiGroupService";
import { FileCacheService } from "../../../src/web/services/FileCacheService";

const SERVICE_ID = "11111111-1111-4111-8111-111111111111";
const GROUP_ID = `${SERVICE_ID}-orders`;
const API_ID = `${SERVICE_ID}-orders-v1`;
const OPERATION_ID = `${API_ID}-op1`;

const uri = fileRef;
const folder = `/root/${SERVICE_ID}`;
const serviceUri = uri(`${folder}/${SERVICE_ID}${ext.externalService}`);
const apiGroupUri = uri(`${folder}/${GROUP_ID}${ext.apiGroup}`);
const legacyGroupUri = uri(`${folder}/${GROUP_ID}${ext.specificationGroup}`);
const apiUri = uri(`${folder}/${API_ID}${ext.api}`);
const legacyApiUri = uri(`${folder}/${API_ID}${ext.specification}`);

const UNREADABLE_TEXT = "id: broken\n  name: [broken";

function groupText(name: string): string {
  return JSON.stringify({
    id: GROUP_ID,
    name,
    content: { parentId: SERVICE_ID },
  });
}

function apiText(name: string, operationName: string): string {
  return JSON.stringify({
    id: API_ID,
    name,
    content: {
      parentId: GROUP_ID,
      operations: [{ id: "op1", name: operationName, path: "/orders" }],
    },
  });
}

beforeEach(() => {
  jest.clearAllMocks();
  disk.clear();
  FileCacheService.getInstance().clearAll();
  setFileApi(new VSCodeFileApi({} as any));

  disk.set(
    serviceUri.path,
    JSON.stringify({
      id: SERVICE_ID,
      name: "Orders",
      content: { protocol: "HTTP" },
    }),
  );
  // The legacy names come first, so a scan that took the first match rather than the precedence
  // rule would answer from them.
  disk.set(legacyGroupUri.path, groupText("superseded group"));
  disk.set(legacyApiUri.path, apiText("superseded api", "superseded op"));
});

describe("a group or API file that cannot be read, with its other-format sibling there", () => {
  beforeEach(() => {
    disk.set(apiGroupUri.path, UNREADABLE_TEXT);
    disk.set(apiUri.path, UNREADABLE_TEXT);
  });

  it("refuses to list the group from its sibling", async () => {
    await expect(getApiSpecifications(serviceUri, SERVICE_ID)).rejects.toThrow(
      apiGroupUri.path,
    );
  });

  it("refuses to list the API from its sibling", async () => {
    await expect(
      getSpecificationModel(serviceUri, SERVICE_ID, GROUP_ID),
    ).rejects.toThrow(apiUri.path);
  });

  it("refuses to read the model's operations from its sibling", async () => {
    await expect(getOperations(serviceUri, API_ID)).rejects.toThrow(
      apiUri.path,
    );
  });

  it("refuses to read an operation from its sibling", async () => {
    await expect(getOperationInfo(serviceUri, OPERATION_ID)).rejects.toThrow(
      apiUri.path,
    );
  });

  it("refuses to resolve the group file at all", async () => {
    await expect(
      ApiGroupService.resolveGroupFile(serviceUri, GROUP_ID),
    ).rejects.toThrow(apiGroupUri.path);
  });

  it("fails an API write and leaves both files exactly as they were", async () => {
    await expect(
      updateSpecificationModel(serviceUri, API_ID, { name: "Renamed" }),
    ).rejects.toThrow(apiUri.path);

    expect(disk.get(apiUri.path)).toBe(UNREADABLE_TEXT);
    expect(disk.get(legacyApiUri.path)).toBe(
      apiText("superseded api", "superseded op"),
    );
    expect(writeFile).not.toHaveBeenCalled();
  });

  it("fails a group write and leaves both files exactly as they were", async () => {
    await expect(
      updateApiSpecificationGroup(serviceUri, GROUP_ID, { name: "Renamed" }),
    ).rejects.toThrow(apiGroupUri.path);

    expect(disk.get(apiGroupUri.path)).toBe(UNREADABLE_TEXT);
    expect(disk.get(legacyGroupUri.path)).toBe(groupText("superseded group"));
    expect(writeFile).not.toHaveBeenCalled();
  });
});

// The same precedence with nothing broken: one entity, one file, and the read and the write pick
// the same one regardless of the order the directory listed them in.
describe("a group and an API stored under both names, both readable", () => {
  beforeEach(() => {
    disk.set(apiGroupUri.path, groupText("current group"));
    disk.set(apiUri.path, apiText("current api", "current op"));
  });

  it("lists each entity once, from the current name", async () => {
    const groups = await getApiSpecifications(serviceUri, SERVICE_ID);

    expect(groups).toHaveLength(1);
    expect(groups[0].name).toBe("current group");
    expect(groups[0].specifications.map((api) => api.name)).toEqual([
      "current api",
    ]);
  });

  it("reads the operations of the current file", async () => {
    const operations = await getOperations(serviceUri, API_ID);

    expect(operations.map((operation) => operation.name)).toEqual([
      "current op",
    ]);
  });

  it("reads an operation from the current file", async () => {
    const info = await getOperationInfo(serviceUri, OPERATION_ID);

    expect(info.id).toBe("op1");
  });

  it("writes the API edit to the file every read answers from", async () => {
    await updateSpecificationModel(serviceUri, API_ID, { name: "Renamed" });

    expect(JSON.parse(disk.get(apiUri.path) ?? "{}").name).toBe("Renamed");
    expect(disk.get(legacyApiUri.path)).toBe(
      apiText("superseded api", "superseded op"),
    );
  });
});

// The refusal reaches exactly as far as a write does: a file the scan cannot read is only a
// sibling when it shares the folder and the name the entity extension is stripped from.
describe("a file the scan cannot read that is nobody's sibling", () => {
  beforeEach(() => {
    disk.set(apiGroupUri.path, groupText("current group"));
    disk.set(apiUri.path, apiText("current api", "current op"));
    disk.set(uri(`${folder}/orphan${ext.api}`).path, UNREADABLE_TEXT);
    disk.set(uri(`${folder}/orphan${ext.apiGroup}`).path, UNREADABLE_TEXT);
  });

  it("still lists the group and its API", async () => {
    const groups = await getApiSpecifications(serviceUri, SERVICE_ID);

    expect(groups.map((group) => group.name)).toEqual(["current group"]);
    expect(groups[0].specifications.map((api) => api.name)).toEqual([
      "current api",
    ]);
  });

  it("still writes an API edit", async () => {
    await updateSpecificationModel(serviceUri, API_ID, { name: "Renamed" });

    expect(JSON.parse(disk.get(apiUri.path) ?? "{}").name).toBe("Renamed");
  });
});
