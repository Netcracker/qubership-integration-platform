const mockStat = jest.fn();
const mockReadFile = jest.fn();
const mockWriteFile = jest.fn();
const mockDelete = jest.fn();
const mockReadDirectory = jest.fn();
const mockCreateDirectory = jest.fn();
const mockJoinPath = jest.fn();
const mockShowInformationMessage = jest.fn();
const mockShowErrorMessage = jest.fn();
const mockShowWarningMessage = jest.fn();
const mockGetConfiguration = jest.fn();

// Use path module for join logic inside mock
const nodePath = require("path") as typeof import("path");

function createMockUri(p: string, scheme = "file", query?: string): any {
  const uri: any = {
    path: p,
    fsPath: p,
    scheme,
    query,
    toString: jest.fn(function (this: any) {
      return this.path;
    }),
    with: jest.fn(function (this: any, change: any) {
      return createMockUri(change.path ?? this.path, change.scheme ?? this.scheme, change.query ?? this.query);
    }),
  };
  return uri;
}

jest.mock("vscode", () => {
  return {
    __esModule: true,
    Uri: {
      file: jest.fn((p: string) => createMockUri(p)),
      parse: jest.fn((p: string) => createMockUri(p)),
      joinPath: mockJoinPath,
    },
    workspace: {
      get workspaceFolders() {
        return [{ uri: createMockUri("/workspace") }];
      },
      getConfiguration: mockGetConfiguration,
      createFileSystemWatcher: jest.fn(),
      onDidChangeConfiguration: jest.fn(),
      fs: {
        stat: mockStat,
        readFile: mockReadFile,
        writeFile: mockWriteFile,
        readDirectory: mockReadDirectory,
        delete: mockDelete,
        createDirectory: mockCreateDirectory,
      },
    },
    window: {
      showInformationMessage: mockShowInformationMessage,
      showErrorMessage: mockShowErrorMessage,
      showWarningMessage: mockShowWarningMessage,
      showInputBox: jest.fn(),
      showQuickPick: jest.fn(),
      withProgress: jest.fn(),
      activeColorTheme: { kind: 2 },
      onDidChangeActiveColorTheme: jest.fn(),
    },
    FileType: { File: 1, Directory: 2, SymbolicLink: 64 },
    ConfigurationTarget: { Global: 1, Workspace: 2, WorkspaceFolder: 3 },
    ProgressLocation: { Notification: 1 },
    ViewColumn: { One: 1 },
    version: "1.90.0",
    default: {},
  };
}, { virtual: true });

jest.mock("../../../../src/web/services/ProjectConfigService", () => ({
  ProjectConfigService: {
    getConfig: jest.fn().mockReturnValue({
      extensions: {
        chain: ".chain.qip.yaml",
        service: ".service.qip.yaml",
        contextService: ".context-service.qip.yaml",
        mcpService: ".mcp-service.qip.yaml",
        specificationGroup: ".specification-group.qip.yaml",
        specification: ".specification.qip.yaml",
      },
      schemaUrls: {},
      cache: { ttl: 60000 },
    }),
    getInstance: jest.fn().mockReturnValue({
      setContext: jest.fn(),
      loadWorkspaceConfig: jest.fn().mockResolvedValue(undefined),
    }),
  },
  CONFIG_FILENAME: "qip-config.yaml",
}));

jest.mock("../../../../src/web/services/FileCacheService", () => ({
  FileCacheService: {
    getInstance: jest.fn().mockReturnValue({
      getFileUri: jest.fn().mockReturnValue(null),
      setFileUri: jest.fn(),
      invalidateByUri: jest.fn(),
      invalidateService: jest.fn(),
      getServiceUri: jest.fn(),
      setServiceUri: jest.fn(),
      getChainUri: jest.fn(),
      setChainUri: jest.fn(),
    }),
  },
}));

import * as vscode from "vscode";
import { VSCodeFileApi } from "../../../../src/web/response/file/fileApiImpl";
import { QipFileType } from "../../../../src/web/response/serviceApiUtils";

describe("VSCodeFileApi – new functionality from 91a8a539", () => {
  let api: VSCodeFileApi;
  const extensionUri = createMockUri("/ext");
  const mockContext: any = {
    extensionUri,
    subscriptions: [],
    workspaceState: { get: jest.fn(), update: jest.fn() },
    globalState: { get: jest.fn(), update: jest.fn() },
  };

  beforeEach(() => {
    jest.clearAllMocks();
    api = new VSCodeFileApi(mockContext);

    // Default mockJoinPath implementation: join base.path with segments
    mockJoinPath.mockImplementation((base: any, ...segments: string[]) => {
      const joined = nodePath.join(base.path, ...segments);
      return createMockUri(joined, base.scheme, base.query);
    });

    // Default: getConfiguration returns empty
    mockGetConfiguration.mockReturnValue({
      get: jest.fn((_key: string, def: any) => def),
      inspect: jest.fn(() => undefined),
      update: jest.fn().mockResolvedValue(undefined),
      has: jest.fn(),
    });

    // Default stat: file exists
    mockStat.mockResolvedValue({ type: 1, ctime: 0, size: 0, mtime: 0 });
    mockReadFile.mockResolvedValue(new Uint8Array(Buffer.from("file content")));
    mockDelete.mockResolvedValue(undefined);
    mockReadDirectory.mockResolvedValue([]);
    mockCreateDirectory.mockResolvedValue(undefined);
  });

  describe("getFileUri fallback via readFile", () => {
    const baseFileUri = createMockUri("/workspace/chains/my-chain/my-chain.chain.qip.yaml");
    const baseDirUri = createMockUri("/workspace/chains/my-chain");

    beforeEach(() => {
      // getParentDirectoryUri will stat baseFileUri and return parent
      // We control mockStat to differentiate baseUri vs fileUri checks
    });

    test("reads from direct path when file exists at baseFolder/filename", async () => {
      // baseFileUri is a file -> parent is /workspace/chains/my-chain
      // first fileUri = /workspace/chains/my-chain/script.groovy exists
      mockStat.mockImplementation(async (uri: any) => {
        if (uri.path === baseFileUri.path) {
          return { type: 1 } as any; // base is file
        }
        if (uri.path === "/workspace/chains/my-chain/script.groovy") {
          return { type: 1 } as any;
        }
        throw new Error("not found");
      });
      mockReadFile.mockResolvedValue(new Uint8Array(Buffer.from("hello")));

      const result = await api.readFile(baseFileUri, "script.groovy");

      expect(result).toBe("hello");
      // should have checked direct path, not fallback
      expect(mockStat).toHaveBeenCalledWith(expect.objectContaining({ path: "/workspace/chains/my-chain/script.groovy" }));
      // readFileContent called with direct uri
      expect(mockReadFile).toHaveBeenCalledWith(expect.objectContaining({ path: "/workspace/chains/my-chain/script.groovy" }));
    });

    test("fallbacks to resources/ when direct path missing", async () => {
      mockStat.mockImplementation(async (uri: any) => {
        if (uri.path === baseFileUri.path) {
          return { type: 1 } as any;
        }
        if (uri.path === "/workspace/chains/my-chain/missing.json") {
          throw new Error("ENOENT");
        }
        if (uri.path === "/workspace/chains/my-chain/resources/missing.json") {
          return { type: 1 } as any;
        }
        throw new Error("not found");
      });
      mockReadFile.mockResolvedValue(new Uint8Array(Buffer.from('{"a":1}')));

      const result = await api.readFile(baseFileUri, "missing.json");

      expect(result).toBe('{"a":1}');
      expect(mockStat).toHaveBeenCalledWith(expect.objectContaining({ path: "/workspace/chains/my-chain/missing.json" }));
      expect(mockStat).toHaveBeenCalledWith(expect.objectContaining({ path: "/workspace/chains/my-chain/resources/missing.json" }));
      expect(mockReadFile).toHaveBeenCalledWith(expect.objectContaining({ path: "/workspace/chains/my-chain/resources/missing.json" }));
    });

    test("does not fallback when filename already contains resources/", async () => {
      mockStat.mockImplementation(async (uri: any) => {
        if (uri.path === baseFileUri.path) {
          return { type: 1 } as any;
        }
        // direct path with resources/ fails
        if (uri.path === "/workspace/chains/my-chain/resources/already.json") {
          throw new Error("ENOENT");
        }
        throw new Error("not found");
      });

      await expect(api.readFile(baseFileUri, "resources/already.json")).rejects.toThrow("ENOENT");

      // should only have tried once (no recursive fallback)
      const calls = mockStat.mock.calls.filter((c: any) => c[0].path.includes("already.json"));
      expect(calls).toHaveLength(1);
      expect(calls[0][0].path).toBe("/workspace/chains/my-chain/resources/already.json");
    });

    test("throws when both direct and fallback missing", async () => {
      mockStat.mockImplementation(async (uri: any) => {
        if (uri.path === baseFileUri.path) {
          return { type: 1 } as any;
        }
        throw new Error("ENOENT");
      });

      await expect(api.readFile(baseFileUri, "notfound.json")).rejects.toThrow("ENOENT");

      expect(mockStat).toHaveBeenCalledWith(expect.objectContaining({ path: "/workspace/chains/my-chain/notfound.json" }));
      expect(mockStat).toHaveBeenCalledWith(expect.objectContaining({ path: "/workspace/chains/my-chain/resources/notfound.json" }));
    });

    test("handles baseUri being a directory (stat returns Directory)", async () => {
      const dirUri = createMockUri("/workspace/chains/my-chain");
      mockStat.mockImplementation(async (uri: any) => {
        if (uri.path === dirUri.path) {
          return { type: 2 } as any; // Directory
        }
        if (uri.path === "/workspace/chains/my-chain/file.txt") {
          return { type: 1 } as any;
        }
        throw new Error("not found");
      });
      mockReadFile.mockResolvedValue(new Uint8Array(Buffer.from("dir file")));

      const result = await api.readFile(dirUri, "file.txt");

      expect(result).toBe("dir file");
      expect(mockStat).toHaveBeenCalledWith(dirUri);
      expect(mockReadFile).toHaveBeenCalledWith(expect.objectContaining({ path: "/workspace/chains/my-chain/file.txt" }));
    });

    test("handles git URI scheme – preserves query path", async () => {
      const gitBaseUri = createMockUri("/workspace/chains/my-chain/my-chain.chain.qip.yaml", "git", JSON.stringify({ path: "C:\\workspace\\chains\\my-chain\\my-chain.chain.qip.yaml" }));
      // gitBaseUri scheme git, query contains windows path
      // getParentDirectoryUri will handle query, addToPath also
      mockStat.mockImplementation(async (uri: any) => {
        // first stat for getParentDirectoryUri on gitBaseUri
        if (uri.path === gitBaseUri.path) {
          return { type: 1 } as any;
        }
        // fileUri checks – direct
        if (uri.path.includes("script.groovy")) {
          return { type: 1 } as any;
        }
        throw new Error("not found");
      });
      mockReadFile.mockResolvedValue(new Uint8Array(Buffer.from("git content")));

      const result = await api.readFile(gitBaseUri, "script.groovy");

      expect(result).toBe("git content");
      // verify that the resolved fileUri preserved git scheme and query handling
      const readCall = mockReadFile.mock.calls[0][0] as any;
      expect(readCall.scheme).toBe("git");
    });
  });

  describe("removeFile – getFileUri fallback", () => {
    const baseFileUri = createMockUri("/workspace/chains/my-chain/my-chain.chain.qip.yaml");

    test("removes direct file when exists", async () => {
      mockStat.mockImplementation(async (uri: any) => {
        if (uri.path === baseFileUri.path) return { type: 1 } as any;
        if (uri.path === "/workspace/chains/my-chain/toDelete.json") return { type: 1 } as any;
        // deleteFile internal stat on fileUri
        if (uri.path === "/workspace/chains/my-chain/toDelete.json") return { type: 1 } as any;
        return { type: 1 } as any;
      });

      await api.removeFile(baseFileUri, "toDelete.json");

      expect(mockDelete).toHaveBeenCalledWith(expect.objectContaining({ path: "/workspace/chains/my-chain/toDelete.json" }));
    });

    test("fallbacks to resources/ when direct missing", async () => {
      mockStat.mockImplementation(async (uri: any) => {
        if (uri.path === baseFileUri.path) return { type: 1 } as any;
        if (uri.path === "/workspace/chains/my-chain/missing.json") throw new Error("ENOENT");
        if (uri.path === "/workspace/chains/my-chain/resources/missing.json") return { type: 1 } as any;
        // deleteFile stat on the resolved fileUri
        if (uri.path === "/workspace/chains/my-chain/resources/missing.json") return { type: 1 } as any;
        return { type: 1 } as any;
      });

      await api.removeFile(baseFileUri, "missing.json");

      expect(mockStat).toHaveBeenCalledWith(expect.objectContaining({ path: "/workspace/chains/my-chain/missing.json" }));
      expect(mockStat).toHaveBeenCalledWith(expect.objectContaining({ path: "/workspace/chains/my-chain/resources/missing.json" }));
      expect(mockDelete).toHaveBeenCalledWith(expect.objectContaining({ path: "/workspace/chains/my-chain/resources/missing.json" }));
    });

    test("does not fallback when filename already contains resources/", async () => {
      mockStat.mockImplementation(async (uri: any) => {
        if (uri.path === baseFileUri.path) return { type: 1 } as any;
        if (uri.path === "/workspace/chains/my-chain/resources/already.json") throw new Error("ENOENT");
        return { type: 1 } as any;
      });

      // getFileUri does not fallback when filename already contains resources/, so it throws
      await expect(api.removeFile(baseFileUri, "resources/already.json")).rejects.toThrow("ENOENT");

      const statCalls = mockStat.mock.calls.filter((c: any) => c[0].path.includes("already.json"));
      expect(statCalls).toHaveLength(1);
    });

    test("logs error but does not throw when delete fails", async () => {
      mockStat.mockImplementation(async (uri: any) => {
        if (uri.path === baseFileUri.path) return { type: 1 } as any;
        return { type: 1 } as any;
      });
      mockDelete.mockRejectedValue(new Error("delete failed"));
      const consoleErrorSpy = jest.spyOn(console, "error").mockImplementation(() => {});

      await expect(api.removeFile(baseFileUri, "file.json")).resolves.toBeUndefined();

      expect(consoleErrorSpy).toHaveBeenCalledWith("Error deleting property file", expect.any(Object));
      consoleErrorSpy.mockRestore();
    });

    test("handles both direct and fallback missing – propagates error", async () => {
      mockStat.mockImplementation(async (uri: any) => {
        if (uri.path === baseFileUri.path) return { type: 1 } as any;
        throw new Error("ENOENT");
      });

      await expect(api.removeFile(baseFileUri, "notfound.json")).rejects.toThrow("ENOENT");
    });
  });

  describe("getDirectoriesToRemove", () => {
    test("returns [resources, serviceDirectory] for SERVICE not at workspace root", async () => {
      const svcUri = createMockUri("/workspace/service/service.service.qip.yaml");
      const serviceDir = createMockUri("/workspace/service");
      const resourcesDir = createMockUri("/workspace/service/resources");
      jest.spyOn(api as any, "getFileType").mockResolvedValue(QipFileType.SERVICE);
      jest.spyOn(api as any, "getParentDirectoryUri").mockResolvedValue(serviceDir);
      jest.spyOn(api, "getRootDirectory").mockReturnValue(createMockUri("/workspace"));
      mockJoinPath.mockReturnValue(resourcesDir);

      const result = await api.getDirectoriesToRemove(svcUri);

      expect(mockJoinPath).toHaveBeenCalledWith(serviceDir, "resources");
      expect(result).toEqual([resourcesDir, serviceDir]);
    });

    test("returns [] when file type is UNKNOWN", async () => {
      const unknownUri = createMockUri("/workspace/unknown.txt");
      jest.spyOn(api as any, "getFileType").mockResolvedValue(QipFileType.UNKNOWN);

      const result = await api.getDirectoriesToRemove(unknownUri);

      expect(result).toEqual([]);
    });

    test("returns [resources, chainDirectory] for CHAIN not at workspace root", async () => {
      const chainFileUri = createMockUri("/workspace/chains/my-chain/my-chain.chain.qip.yaml");
      const chainDir = createMockUri("/workspace/chains/my-chain");
      const resourcesDir = createMockUri("/workspace/chains/my-chain/resources");

      jest.spyOn(api as any, "getFileType").mockResolvedValue(QipFileType.CHAIN);
      // getParentDirectoryUri returns chainDir
      jest.spyOn(api as any, "getParentDirectoryUri").mockResolvedValue(chainDir);
      // getRootDirectory returns /workspace (different object)
      jest.spyOn(api, "getRootDirectory").mockReturnValue(createMockUri("/workspace"));
      mockJoinPath.mockReturnValue(resourcesDir);

      const result = await api.getDirectoriesToRemove(chainFileUri);

      expect(mockJoinPath).toHaveBeenCalledWith(chainDir, "resources");
      expect(result).toEqual([resourcesDir, chainDir]);
    });

    test("returns [] when chain directory is at workspace root (reference equality path)", async () => {
      const chainFileUri = createMockUri("/workspace/my.chain.qip.yaml");
      const rootUri = createMockUri("/workspace");

      jest.spyOn(api as any, "getFileType").mockResolvedValue(QipFileType.CHAIN);
      // Make getParentDirectoryUri return same reference as getRootDirectory
      // To achieve reference equality, mock getRootDirectory to return rootUri and getParentDirectoryUri to return same object
      jest.spyOn(api, "getRootDirectory").mockReturnValue(rootUri);
      jest.spyOn(api as any, "getParentDirectoryUri").mockResolvedValue(rootUri);

      const result = await api.getDirectoriesToRemove(chainFileUri);

      expect(result).toEqual([]);
      expect(mockJoinPath).not.toHaveBeenCalled();
    });

    test("uses getParentDirectoryUri and getRootDirectory correctly", async () => {
      const chainFileUri = createMockUri("/workspace/a/b/c.chain.qip.yaml");
      const parentDir = createMockUri("/workspace/a/b");
      const root = createMockUri("/workspace");

      const getParentSpy = jest.spyOn(api as any, "getParentDirectoryUri").mockResolvedValue(parentDir);
      jest.spyOn(api as any, "getFileType").mockResolvedValue(QipFileType.CHAIN);
      jest.spyOn(api, "getRootDirectory").mockReturnValue(root);

      await api.getDirectoriesToRemove(chainFileUri);

      expect(getParentSpy).toHaveBeenCalledWith(chainFileUri);
      expect(api.getRootDirectory).toHaveBeenCalled();
    });

    test("returns [directory] for CONTEXT_SERVICE not at workspace root (without resources)", async () => {
      const svcUri = createMockUri("/workspace/context/my-ctx.context-service.qip.yaml");
      const serviceDir = createMockUri("/workspace/context/my-ctx");
      jest.spyOn(api as any, "getFileType").mockResolvedValue(QipFileType.CONTEXT_SERVICE);
      jest.spyOn(api as any, "getParentDirectoryUri").mockResolvedValue(serviceDir);
      jest.spyOn(api, "getRootDirectory").mockReturnValue(createMockUri("/workspace"));

      const result = await api.getDirectoriesToRemove(svcUri);

      expect(mockJoinPath).not.toHaveBeenCalled();
      expect(result).toEqual([serviceDir]);
    });

    test("returns [directory] for MCP_SERVICE not at workspace root (without resources)", async () => {
      const svcUri = createMockUri("/workspace/mcp/my-mcp.mcp-service.qip.yaml");
      const serviceDir = createMockUri("/workspace/mcp/my-mcp");
      jest.spyOn(api as any, "getFileType").mockResolvedValue(QipFileType.MCP_SERVICE);
      jest.spyOn(api as any, "getParentDirectoryUri").mockResolvedValue(serviceDir);
      jest.spyOn(api, "getRootDirectory").mockReturnValue(createMockUri("/workspace"));

      const result = await api.getDirectoriesToRemove(svcUri);

      expect(mockJoinPath).not.toHaveBeenCalled();
      expect(result).toEqual([serviceDir]);
    });
  });
});
