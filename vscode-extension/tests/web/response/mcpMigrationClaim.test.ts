// Which migration list each kind of service document claims when the extension writes it back.
// The backend runs an MCP document through its own registry, and that registry holds version 100
// alone (`V100MCPServiceImportFileMigration`); a plain or context document runs through the service
// registry, which holds 100 to 105. A document claiming a version its registry does not hold is
// refused on import as exported from a newer version, so reading an MCP file through the context
// accessor — which repairs the claim with the service list — makes the file unimportable.
// These cases run the real `VSCodeFileApi` and the real write path against an in-memory disk.

import { joinUriPath, QIP_FILE_EXTENSIONS as ext } from "../../helpers/mocks";

/** The workspace: path → file text. */
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
  throw new Error(`EntryNotFound: ${fileUri.path}`);
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
          readDirectory: jest.fn().mockResolvedValue([]),
          readFile: async (fileUri: any) =>
            new TextEncoder().encode(disk.get(fileUri.path) ?? ""),
          writeFile: jest.fn(async (fileUri: any, bytes: Uint8Array) => {
            disk.set(fileUri.path, new TextDecoder().decode(bytes));
          }),
          delete: jest.fn(async (fileUri: any) => {
            disk.delete(fileUri.path);
          }),
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

jest.mock("../../../src/web/response/file/fileExtensions", () =>
  jest.requireActual("../../helpers/mocks").fileExtensionsMock(
    () => ext,
    () => undefined,
  ),
);

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
jest.mock("../../../src/web/api-services/ApiGroupService", () => ({
  ApiGroupService: {
    regenerateGroupApisSafely: jest.fn(),
    resolveGroupFile: jest.fn(),
  },
}));

// The parser the whole read path shares, reading the same in-memory disk.
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
  updateContextService,
  updateMcpService,
} from "../../../src/web/response/serviceApiModify";
import { FileCacheService } from "../../../src/web/services/FileCacheService";

const SERVICE_ID = "11111111-1111-4111-8111-111111111111";
const mcpUri = fileRef(`/root/${SERVICE_ID}/${SERVICE_ID}${ext.mcpService}`);
const contextUri = fileRef(
  `/root/${SERVICE_ID}/${SERVICE_ID}${ext.contextService}`,
);

function claimOf(fileUri: any): string | undefined {
  return JSON.parse(disk.get(fileUri.path) ?? "{}").content?.migrations;
}

beforeEach(() => {
  jest.clearAllMocks();
  disk.clear();
  FileCacheService.getInstance().clearAll();
  setFileApi(new VSCodeFileApi({} as any));
});

// A document exported before the claim was written, or written by an older extension as an empty
// array, is the one the repair applies to.
describe("a document with no usable migrations claim", () => {
  beforeEach(() => {
    disk.set(
      mcpUri.path,
      JSON.stringify({
        id: SERVICE_ID,
        name: "Orders MCP",
        content: { description: "MCP" },
      }),
    );
    disk.set(
      contextUri.path,
      JSON.stringify({
        id: SERVICE_ID,
        name: "Orders context",
        content: { description: "Context" },
      }),
    );
  });

  it("claims the MCP registry alone on an MCP edit", async () => {
    await updateMcpService(mcpUri, SERVICE_ID, { name: "Renamed" });

    expect(claimOf(mcpUri)).toBe("[100]");
  });

  it("claims the service registry on a context edit", async () => {
    await updateContextService(contextUri, SERVICE_ID, { name: "Renamed" });

    expect(claimOf(contextUri)).toBe("[100, 101, 102, 103, 104, 105]");
  });
});

// A claim the document already carries names the set the backend still has to migrate it through,
// so the repair leaves it alone.
describe("a document that already claims a version set", () => {
  it("keeps the MCP document's own claim", async () => {
    disk.set(
      mcpUri.path,
      JSON.stringify({
        id: SERVICE_ID,
        name: "Orders MCP",
        content: { description: "MCP", migrations: "[100]" },
      }),
    );

    await updateMcpService(mcpUri, SERVICE_ID, { name: "Renamed" });

    expect(claimOf(mcpUri)).toBe("[100]");
  });
});
