// getFileType and navigation-path resolution over the four plain-service names. A typed file that
// getFileType reads as UNKNOWN, or a service folder it reads as a plain FOLDER, breaks the spec-import
// base folder and the navigation target without raising anything.

import { QIP_FILE_EXTENSIONS as ext } from "../../helpers/mocks";

const stat = jest.fn();
const readDirectory = jest.fn();

jest.mock(
  "vscode",
  () => ({
    __esModule: true,
    FileType: { File: 1, Directory: 2 },
    Uri: {
      joinPath: jest.fn((_base: any, ...segments: string[]) => ({
        path: segments.join("/"),
        fsPath: segments.join("/"),
      })),
    },
    workspace: {
      workspaceFolders: [{ uri: { path: "/root" } }],
      fs: { stat, readDirectory },
    },
  }),
  { virtual: true },
);

// Keep the heavy sibling graph out of module load. The route patterns themselves are unchanged by
// this task — what is under test is the extension fan-out a matching service route triggers.
jest.mock("../../../src/web/response/apiRouter", () => ({
  SERVICE_ROUTES: [/^\/services\/systems\/[^/]+\/parameters$/],
  CHAIN_ROUTES: [/^\/chains\/[^/]+$/],
  CONTEXT_SERVICE_ROUTES: [/^\/services\/context\/[^/]+\/parameters$/],
  MCP_SERVICE_ROUTES: [/^\/services\/mcp\/[^/]+\/parameters$/],
}));
jest.mock("../../../src/web/response/serviceApiUtils", () => ({
  QipFileType: {
    CHAIN: "CHAIN",
    SERVICE: "SERVICE",
    CONTEXT_SERVICE: "CONTEXT_SERVICE",
    MCP_SERVICE: "MCP_SERVICE",
    FOLDER: "FOLDER",
    UNKNOWN: "UNKNOWN",
  },
}));
jest.mock("../../../src/web/response/file/fileExtensions", () => ({
  getExtensionsForFile: jest.fn(() => ext),
  getExtensionsForUri: jest.fn(() => ext),
  extractFilename: jest.fn((fileRef: any) =>
    (typeof fileRef === "string" ? fileRef : fileRef.path).split("/").pop(),
  ),
}));
jest.mock("@netcracker/qip-ui", () => ({}), { virtual: true });
jest.mock("@netcracker/qip-schemas", () => ({}), { virtual: true });

import { VSCodeFileApi } from "../../../src/web/response/file/fileApiImpl";

// extractEntityId reads a uuid out of the path, so the ids here have to be real ones.
const SERVICE_ID = "7331eb14-1a2b-4c3d-8e9f-0123456789ab";
const CHAIN_ID = "c32207d0-1a2b-4c3d-8e9f-0123456789ab";
const CONTEXT_ID = "2924e5cf-1a2b-4c3d-8e9f-0123456789ab";
const MCP_ID = "1111aaaa-1a2b-4c3d-8e9f-0123456789ab";

function makeUri(path: string): any {
  return {
    path,
    scheme: "file",
    with: (patch: { path?: string }) => makeUri(patch.path ?? path),
  };
}

beforeEach(() => {
  jest.clearAllMocks();
});

describe("VSCodeFileApi.getFileType - files", () => {
  const api = new VSCodeFileApi({} as any);

  beforeEach(() => {
    stat.mockResolvedValue({ type: 1 });
  });

  it.each([
    ext.service,
    ext.externalService,
    ext.internalService,
    ext.implementedService,
  ])("reads a file named %s as a service", async (extension) => {
    const type = await api.getFileType(makeUri(`/root/svc/svc${extension}`));

    expect(type).toBe("SERVICE");
  });

  it("still tells a context and an MCP service apart from a plain one", async () => {
    expect(
      await api.getFileType(makeUri(`/root/ctx/ctx${ext.contextService}`)),
    ).toBe("CONTEXT_SERVICE");
    expect(
      await api.getFileType(makeUri(`/root/mcp/mcp${ext.mcpService}`)),
    ).toBe("MCP_SERVICE");
  });

  it("reads a chain file as a chain and anything else as unknown", async () => {
    expect(await api.getFileType(makeUri(`/root/c1/c1${ext.chain}`))).toBe(
      "CHAIN",
    );
    expect(await api.getFileType(makeUri("/root/notes.md"))).toBe("UNKNOWN");
  });
});

describe("VSCodeFileApi.getFileType - directories", () => {
  const api = new VSCodeFileApi({} as any);

  beforeEach(() => {
    stat.mockResolvedValue({ type: 2 });
  });

  it.each([
    ext.service,
    ext.externalService,
    ext.internalService,
    ext.implementedService,
  ])(
    "reads a folder holding only %s as a service folder",
    async (extension) => {
      readDirectory.mockResolvedValue([
        [`svc${extension}`, 1],
        ["resources", 2],
      ]);

      expect(await api.getFileType(makeUri("/root/svc"))).toBe("SERVICE");
    },
  );

  it("reads a folder holding no entity file as a plain folder", async () => {
    readDirectory.mockResolvedValue([["notes.md", 1]]);

    expect(await api.getFileType(makeUri("/root/docs"))).toBe("FOLDER");
  });

  it("keeps reading a context-service folder as a context-service folder", async () => {
    readDirectory.mockResolvedValue([[`ctx${ext.contextService}`, 1]]);

    expect(await api.getFileType(makeUri("/root/ctx"))).toBe("CONTEXT_SERVICE");
  });
});

describe("VSCodeFileApi.findFileByNavigationPath", () => {
  const api = new VSCodeFileApi({} as any);

  // Only the named extension is on disk; the rest reject the way findFileById does for a miss.
  function onlyOnDisk(api: VSCodeFileApi, extension: string) {
    return jest
      .spyOn(api, "findFileById")
      .mockImplementation((id: string, requested?: string) =>
        requested === extension
          ? Promise.resolve(makeUri(`/root/${id}/${id}${requested}`))
          : Promise.reject(new Error("not found")),
      );
  }

  it.each([
    ext.externalService,
    ext.internalService,
    ext.implementedService,
    ext.service,
  ])("resolves a service route to a file named %s", async (extension) => {
    onlyOnDisk(api, extension);

    const fileUri = await api.findFileByNavigationPath(
      `/services/systems/${SERVICE_ID}/parameters`,
    );

    expect(fileUri.path).toBe(`/root/${SERVICE_ID}/${SERVICE_ID}${extension}`);
  });

  it("tries the typed names before the legacy one", async () => {
    const findFileById = onlyOnDisk(api, ext.implementedService);

    await api.findFileByNavigationPath(
      `/services/systems/${SERVICE_ID}/parameters`,
    );

    expect(findFileById.mock.calls.map((call) => call[1])).toEqual([
      ext.externalService,
      ext.internalService,
      ext.implementedService,
    ]);
  });

  it("throws when no plain-service name carries the id", async () => {
    onlyOnDisk(api, ext.chain);

    await expect(
      api.findFileByNavigationPath(
        `/services/systems/${SERVICE_ID}/parameters`,
      ),
    ).rejects.toThrow("not found");
  });

  it("still resolves chain, context and MCP routes to their single extension", async () => {
    const findFileById = jest
      .spyOn(api, "findFileById")
      .mockImplementation((id: string, requested?: string) =>
        Promise.resolve(makeUri(`/root/${id}/${id}${requested}`)),
      );

    await api.findFileByNavigationPath(`/chains/${CHAIN_ID}`);
    await api.findFileByNavigationPath(
      `/services/context/${CONTEXT_ID}/parameters`,
    );
    await api.findFileByNavigationPath(`/services/mcp/${MCP_ID}/parameters`);

    expect(findFileById.mock.calls.map((call) => call[1])).toEqual([
      ext.chain,
      ext.contextService,
      ext.mcpService,
    ]);
  });

  it("rejects a path no route claims", async () => {
    await expect(api.findFileByNavigationPath("/nowhere")).rejects.toThrow(
      "Invalid navigation path",
    );
  });
});
