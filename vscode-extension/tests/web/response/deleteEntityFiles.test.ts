// What a delete has to remove when a folder holds both names of one entity. A delete that removed
// only the file the precedence rule resolved left the sibling on disk, and the next read answered
// the same id from it: the entity came back, with its source files and its group link already gone.
// These cases run the real file api, the real scans and the real delete paths against an in-memory
// disk.

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

jest.mock("../../../src/web/response/apiRouter", () => ({
  SERVICE_ROUTES: [],
  CHAIN_ROUTES: [],
  CONTEXT_SERVICE_ROUTES: [],
  MCP_SERVICE_ROUTES: [],
}));
jest.mock("../../../src/web/extension", () => ({
  refreshQipExplorer: jest.fn(),
}));

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
  getSpecificationModel,
} from "../../../src/web/response/serviceApiRead";
import {
  deleteSpecificationGroup,
  deleteSpecificationModel,
} from "../../../src/web/response/serviceApiModify";
import { FileCacheService } from "../../../src/web/services/FileCacheService";

const SERVICE_ID = "11111111-1111-4111-8111-111111111111";
const GROUP_ID = `${SERVICE_ID}-orders`;
const API_ID = `${SERVICE_ID}-orders-v1`;

const uri = fileRef;
const folder = `/root/${SERVICE_ID}`;
const serviceUri = uri(`${folder}/${SERVICE_ID}${ext.externalService}`);
const apiGroupUri = uri(`${folder}/${GROUP_ID}${ext.apiGroup}`);
const legacyGroupUri = uri(`${folder}/${GROUP_ID}${ext.specificationGroup}`);
const apiUri = uri(`${folder}/${API_ID}${ext.api}`);
const legacyApiUri = uri(`${folder}/${API_ID}${ext.specification}`);
const apiSourceUri = uri(`${folder}/resources/current/orders.yaml`);
const legacySourceUri = uri(`${folder}/resources/superseded/orders.yaml`);

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
  disk.set(
    apiGroupUri.path,
    JSON.stringify({
      id: GROUP_ID,
      name: "current group",
      content: { parentId: SERVICE_ID },
    }),
  );
  // The legacy names come first in a directory listing, so a delete that took the first match
  // rather than the precedence rule would remove the wrong one.
  disk.set(
    legacyGroupUri.path,
    JSON.stringify({
      id: GROUP_ID,
      name: "superseded group",
      content: { parentId: SERVICE_ID },
    }),
  );
  disk.set(
    apiUri.path,
    JSON.stringify({
      id: API_ID,
      name: "current api",
      content: {
        parentId: GROUP_ID,
        specifications: [{ filePath: "current/orders.yaml", isRoot: true }],
        operations: [{ id: "op1", name: "current op", path: "/orders" }],
      },
    }),
  );
  disk.set(
    legacyApiUri.path,
    JSON.stringify({
      id: API_ID,
      name: "superseded api",
      content: {
        parentId: GROUP_ID,
        specificationSources: [
          { fileName: "superseded/orders.yaml", mainSource: true },
        ],
        operations: [{ id: "op1", name: "superseded op", path: "/orders" }],
      },
    }),
  );
  disk.set(apiSourceUri.path, "openapi: 3.0.0");
  disk.set(legacySourceUri.path, "openapi: 3.0.0");
});

describe("deleting an API stored under both names", () => {
  it("removes both files, so the id cannot come back from the sibling", async () => {
    await deleteSpecificationModel(serviceUri, API_ID);

    expect(disk.has(apiUri.path)).toBe(false);
    expect(disk.has(legacyApiUri.path)).toBe(false);
    expect(
      await getSpecificationModel(serviceUri, SERVICE_ID, GROUP_ID),
    ).toEqual([]);
  });

  it("removes the source files both of them reference", async () => {
    await deleteSpecificationModel(serviceUri, API_ID);

    expect(disk.has(apiSourceUri.path)).toBe(false);
    expect(disk.has(legacySourceUri.path)).toBe(false);
  });

  it("drops the id from the group it belonged to", async () => {
    await deleteSpecificationModel(serviceUri, API_ID);

    const groups = await getApiSpecifications(serviceUri, SERVICE_ID);
    expect(groups).toHaveLength(1);
    expect(groups[0].specifications).toEqual([]);
    expect(JSON.parse(disk.get(apiGroupUri.path) ?? "{}").content.apis).toEqual(
      [],
    );
  });
});

describe("deleting a group stored under both names", () => {
  it("removes both group files and both files of its API", async () => {
    await deleteSpecificationGroup(serviceUri, GROUP_ID);

    expect(disk.has(apiGroupUri.path)).toBe(false);
    expect(disk.has(legacyGroupUri.path)).toBe(false);
    expect(disk.has(apiUri.path)).toBe(false);
    expect(disk.has(legacyApiUri.path)).toBe(false);
    expect(await getApiSpecifications(serviceUri, SERVICE_ID)).toEqual([]);
  });
});
