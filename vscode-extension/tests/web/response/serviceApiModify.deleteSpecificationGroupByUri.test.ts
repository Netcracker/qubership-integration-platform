import { Uri } from "vscode";
import * as vscode from "vscode";
import { deleteSpecificationGroupByUri } from "../../../src/web/response/serviceApiModify";
import { fileApi } from "../../../src/web/response/file/fileApiProvider";
import { ContentParser } from "../../../src/web/api-services/parsers/ContentParser";
import { getExtensionsForFile } from "../../../src/web/response/file/fileExtensions";
import * as serviceApiRead from "../../../src/web/response/serviceApiRead";

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

describe("deleteSpecificationGroupByUri", () => {
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
  const mockGetSpecGroupFiles = (fileApi as any)
    .getSpecificationGroupFiles as jest.Mock;
  const mockGetSpecFiles = (fileApi as any).getSpecificationFiles as jest.Mock;
  const mockParse = ContentParser.parseContentFromFile as jest.Mock;
  const mockGetExtensions = getExtensionsForFile as jest.Mock;
  const mockGetMainService = (serviceApiRead as any).getMainService as jest.Mock;
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
    mockGetMainService.mockResolvedValue({ id: "svc1", name: "svc" });
  });

  afterAll(() => {
    consoleErrorSpy.mockRestore();
  });

  function setupHappyPath() {
    mockParse.mockImplementation(async (uri: any) => {
      const p = uri.path as string;
      if (p.includes("group1.specification-group")) {
        return { id: "group1", name: "Group One", content: { parentId: "svc1" } };
      }
      if (p.includes(".specification.qip.yaml")) {
        return { id: "spec1", name: "S1", content: { parentId: "group1" } };
      }
      return { id: "unknown", name: "unknown" };
    });
    mockFindFileById.mockResolvedValue(serviceFileUri);
    mockGetSpecGroupFiles.mockResolvedValue(["group1.specification-group.qip.yaml"]);
    mockGetSpecFiles.mockResolvedValue(["spec1.specification.qip.yaml"]);
  }

  it("should resolve the parent service and delete the group silently", async () => {
    setupHappyPath();

    await deleteSpecificationGroupByUri(groupFileUri);

    expect(mockParse).toHaveBeenCalledWith(groupFileUri);
    expect(mockGetExtensions).toHaveBeenCalledWith("group1.specification-group.qip.yaml");
    expect(mockFindFileById).toHaveBeenCalledWith("svc1", extensions.service);
    expect(mockDeleteFile).toHaveBeenCalledWith(
      expect.objectContaining({ path: expect.stringContaining("group1.specification-group.qip.yaml") }),
    );
    // silent=true is forwarded to deleteSpecificationGroup, so no vscode messages
    expect(mockShowInfo).not.toHaveBeenCalled();
    expect(mockShowError).not.toHaveBeenCalled();
  });

  it("should throw when the group file does not contain an id", async () => {
    mockParse.mockResolvedValue({ name: "no-id", content: { parentId: "svc1" } });

    await expect(deleteSpecificationGroupByUri(groupFileUri)).rejects.toThrow(
      `Specification group file ${groupFileUri.path} does not contain an id`,
    );

    expect(mockFindFileById).not.toHaveBeenCalled();
    expect(mockDeleteFile).not.toHaveBeenCalled();
    expect(consoleErrorSpy).toHaveBeenCalledWith("deleteSpecificationGroup: Error:", expect.any(Error));
  });

  it("should throw when the group file does not contain a parent service id", async () => {
    mockParse.mockResolvedValue({ id: "group1", name: "G1", content: {} });

    await expect(deleteSpecificationGroupByUri(groupFileUri)).rejects.toThrow(
      `Specification group file ${groupFileUri.path} does not contain a parent service id`,
    );

    expect(mockFindFileById).not.toHaveBeenCalled();
    expect(mockDeleteFile).not.toHaveBeenCalled();
  });

  it("should throw when the service file lookup fails", async () => {
    mockParse.mockResolvedValue({
      id: "group1",
      name: "G1",
      content: { parentId: "svc1" },
    });
    mockFindFileById.mockRejectedValue(new Error("service file missing"));

    await expect(deleteSpecificationGroupByUri(groupFileUri)).rejects.toThrow("service file missing");

    expect(mockFindFileById).toHaveBeenCalledWith("svc1", extensions.service);
    expect(mockDeleteFile).not.toHaveBeenCalled();
  });

  it("should propagate the error when the group does not exist under the service", async () => {
    mockParse.mockImplementation(async (uri: any) => {
      if (uri === groupFileUri) {
        return { id: "missing", name: "Missing", content: { parentId: "svc1" } };
      }
      return { id: "group1", name: "Group One", content: { parentId: "svc1" } };
    });
    mockFindFileById.mockResolvedValue(serviceFileUri);
    mockGetSpecGroupFiles.mockResolvedValue(["group1.specification-group.qip.yaml"]);
    mockGetSpecFiles.mockResolvedValue([]);

    await expect(deleteSpecificationGroupByUri(groupFileUri)).rejects.toThrow(
      "Specification group with id missing not found",
    );

    expect(consoleErrorSpy).toHaveBeenCalledWith("deleteSpecificationGroup: Error:", expect.any(Error));
  });
});
