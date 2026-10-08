import { Uri } from "vscode";
import * as vscode from "vscode";
import { deleteSpecificationByUri } from "../../../src/web/response/serviceApiModify";
import { fileApi } from "../../../src/web/response/file/fileApiProvider";
import { ContentParser } from "../../../src/web/api-services/parsers/ContentParser";
import { getExtensionsForFile } from "../../../src/web/response/file/fileExtensions";

jest.mock("../../../src/web/response/file/fileApiProvider", () => ({
  fileApi: {
    getSpecificationGroupFiles: jest.fn(),
    getSpecificationFiles: jest.fn(),
    deleteFile: jest.fn(),
    findFileById: jest.fn(),
  },
}));

jest.mock("../../../src/web/response/file", () => ({
  fileApi: {
    getSpecificationGroupFiles: jest.fn(),
    getSpecificationFiles: jest.fn(),
    deleteFile: jest.fn(),
    findFileById: jest.fn(),
  },
}));

jest.mock("../../../src/web/api-services/parsers/ContentParser", () => ({
  ContentParser: {
    parseContentFromFile: jest.fn(),
  },
}));

jest.mock("../../../src/web/response/serviceApiRead", () => ({
  getMainService: jest.fn(),
  getService: jest.fn(),
  getContextService: jest.fn(),
  getMcpService: jest.fn(),
}));

jest.mock("../../../src/web/response/file/fileExtensions", () => ({
  getExtensionsForFile: jest.fn(),
}));

jest.mock(
  "vscode",
  () => {
    const mockUri = {
      joinPath: jest.fn((uri: any, ...parts: string[]) => ({
        path: `${uri.path}/${parts.join("/")}`,
        fsPath: `${uri.fsPath}/${parts.join("/")}`,
        toString: jest.fn(() => `${uri.path}/${parts.join("/")}`),
      })),
    };
    const mockWindow = {
      showInformationMessage: jest.fn(),
      showErrorMessage: jest.fn(),
    };
    const mockVscode = {
      Uri: mockUri,
      FileType: { File: 1, Directory: 2 },
      workspace: {
        workspaceFolders: [{ uri: { path: "/workspace", fsPath: "/workspace" } }],
      },
      window: mockWindow,
    };
    return {
      __esModule: true,
      ...mockVscode,
      default: mockVscode,
    };
  },
  { virtual: true },
);

const consoleErrorSpy = jest
  .spyOn(console, "error")
  .mockImplementation(() => {});

describe("deleteSpecificationByUri", () => {
  const specFileUri = {
    path: "/workspace/svc/spec1.specification.qip.yaml",
    fsPath: "/workspace/svc/spec1.specification.qip.yaml",
    toString: jest.fn().mockReturnValue("/workspace/svc/spec1.specification.qip.yaml"),
  } as unknown as Uri;

  const groupFileUri = {
    path: "/workspace/svc/group1.specification-group.qip.yaml",
    fsPath: "/workspace/svc/group1.specification-group.qip.yaml",
    toString: jest.fn().mockReturnValue("/workspace/svc/group1.specification-group.qip.yaml"),
  } as unknown as Uri;

  const serviceFileUri = {
    path: "/workspace/svc/svc.service.qip.yaml",
    fsPath: "/workspace/svc/svc.service.qip.yaml",
    toString: jest.fn().mockReturnValue("/workspace/svc/svc.service.qip.yaml"),
  } as unknown as Uri;

  const mockDeleteFile = (fileApi as any).deleteFile as jest.Mock;
  const mockFindFileById = (fileApi as any).findFileById as jest.Mock;
  const mockGetSpecFiles = (fileApi as any).getSpecificationFiles as jest.Mock;
  const mockParse = ContentParser.parseContentFromFile as jest.Mock;
  const mockGetExtensions = getExtensionsForFile as jest.Mock;
  const mockShowInfo = vscode.window
    .showInformationMessage as unknown as jest.Mock;
  const mockShowError = vscode.window.showErrorMessage as unknown as jest.Mock;

  const extensions = {
    appName: "qip",
    chain: ".chain.qip.yaml",
    service: ".service.qip.yaml",
    contextService: ".context-service.qip.yaml",
    mcpService: ".mcp-service.qip.yaml",
    specificationGroup: ".specification-group.qip.yaml",
    specification: ".specification.qip.yaml",
  };

  beforeEach(() => {
    jest.clearAllMocks();
    mockGetExtensions.mockReturnValue(extensions);
    mockDeleteFile.mockResolvedValue(undefined);
  });

  afterAll(() => {
    consoleErrorSpy.mockRestore();
  });

  function setupHappyPath() {
    mockParse.mockImplementation(async (uri: any) => {
      if (uri === specFileUri) {
        return {
          id: "spec1",
          name: "Spec One",
          content: { parentId: "group1", specificationSources: [] },
        };
      }
      if (uri === groupFileUri) {
        return { id: "group1", name: "Group One", content: { parentId: "svc1" } };
      }
      // findSpecificationFileById re-parses the spec file via a joined uri
      if ((uri.path as string).includes("spec1.specification")) {
        return {
          id: "spec1",
          name: "Spec One",
          content: { parentId: "group1", specificationSources: [] },
        };
      }
      return { id: "unknown", name: "unknown" };
    });
    mockFindFileById.mockImplementation(async (id: string) => {
      if (id === "group1") return groupFileUri;
      if (id === "svc1") return serviceFileUri;
      throw new Error(`not found ${id}`);
    });
    mockGetSpecFiles.mockResolvedValue(["spec1.specification.qip.yaml"]);
  }

  it("should resolve hierarchy and delete via model path without touching the uri directly and stay silent", async () => {
    setupHappyPath();

    await deleteSpecificationByUri(specFileUri);

    expect(mockGetExtensions).toHaveBeenCalledWith("spec1.specification.qip.yaml");
    expect(mockFindFileById).toHaveBeenCalledWith("group1", extensions.specificationGroup);
    expect(mockFindFileById).toHaveBeenCalledWith("svc1", extensions.service);
    // model path deletes the spec file discovered via getSpecificationFiles
    expect(mockGetSpecFiles).toHaveBeenCalledWith(serviceFileUri);
    expect(mockDeleteFile).toHaveBeenCalledWith(
      expect.objectContaining({ path: expect.stringContaining("spec1.specification.qip.yaml") }),
    );
    // early return: the passed-in uri object itself is never deleted directly
    expect(mockDeleteFile).not.toHaveBeenCalledWith(specFileUri);
    // silent=true is forwarded, so no vscode messages
    expect(mockShowInfo).not.toHaveBeenCalled();
    expect(mockShowError).not.toHaveBeenCalled();
  });

  it("should fall back to direct delete when specification has no parent group id", async () => {
    mockParse.mockResolvedValue({ id: "spec1", name: "S1", content: {} });

    await deleteSpecificationByUri(specFileUri);

    expect(consoleErrorSpy).toHaveBeenCalledWith(
      expect.stringContaining("Error deleting specification by id spec1"),
      expect.any(Error),
    );
    expect(mockFindFileById).not.toHaveBeenCalled();
    expect(mockDeleteFile).toHaveBeenCalledWith(specFileUri);
  });

  it("should fall back to direct delete when group has no parent service id", async () => {
    mockParse.mockImplementation(async (uri: any) => {
      if (uri === specFileUri) {
        return { id: "spec1", name: "S1", content: { parentId: "group1" } };
      }
      return { id: "group1", name: "G1", content: {} };
    });
    mockFindFileById.mockResolvedValue(groupFileUri);

    await deleteSpecificationByUri(specFileUri);

    expect(consoleErrorSpy).toHaveBeenCalledWith(
      expect.stringContaining("Error deleting specification by id spec1"),
      expect.any(Error),
    );
    expect(mockDeleteFile).toHaveBeenCalledWith(specFileUri);
  });

  it("should fall back to direct delete when group lookup fails", async () => {
    mockParse.mockResolvedValue({
      id: "spec1",
      name: "S1",
      content: { parentId: "group1" },
    });
    mockFindFileById.mockRejectedValue(new Error("group file missing"));

    await deleteSpecificationByUri(specFileUri);

    expect(consoleErrorSpy).toHaveBeenCalledWith(
      expect.stringContaining("Error deleting specification by id spec1"),
      expect.any(Error),
    );
    expect(mockDeleteFile).toHaveBeenCalledWith(specFileUri);
  });

  it("should just delete the file when the specification file cannot be parsed", async () => {
    mockParse.mockRejectedValue(new Error("unreadable yaml"));

    await deleteSpecificationByUri(specFileUri);

    expect(consoleErrorSpy).toHaveBeenCalledWith(
      expect.stringContaining("Error reading specification file"),
      expect.any(Error),
    );
    expect(mockFindFileById).not.toHaveBeenCalled();
    expect(mockDeleteFile).toHaveBeenCalledWith(specFileUri);
    expect(mockDeleteFile).toHaveBeenCalledTimes(1);
  });

  it("should delete source files on the fallback path when specification has no id", async () => {
    mockParse.mockResolvedValue({
      name: "Orphan",
      content: {
        specificationSources: [
          { fileName: "openapi.yaml", name: "src1" },
          { fileName: "folder/a.yaml", name: "src2" },
        ],
      },
    });

    await deleteSpecificationByUri(specFileUri);

    expect(mockFindFileById).not.toHaveBeenCalled();
    expect(mockDeleteFile).toHaveBeenCalledWith(
      expect.objectContaining({ path: expect.stringContaining("resources/openapi.yaml") }),
    );
    expect(mockDeleteFile).toHaveBeenCalledWith(
      expect.objectContaining({ path: expect.stringContaining("resources/folder/a.yaml") }),
    );
    expect(mockDeleteFile).toHaveBeenCalledWith(specFileUri);
  });

  it("should throw and log when the final file delete fails", async () => {
    mockParse.mockRejectedValue(new Error("unreadable yaml"));
    mockDeleteFile.mockRejectedValue(new Error("delete fail"));

    await expect(deleteSpecificationByUri(specFileUri)).rejects.toThrow("delete fail");

    expect(consoleErrorSpy).toHaveBeenCalledWith("[deleteSpecificationByUri] Error:", expect.any(Error));
  });
});
