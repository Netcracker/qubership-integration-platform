import { Uri } from "vscode";
import * as vscode from "vscode";
import { deleteSpecificationGroup } from "../../../src/web/response/serviceApiModify";
import { fileApi } from "../../../src/web/response/file/fileApiProvider";
import { ContentParser } from "../../../src/web/api-services/parsers/ContentParser";
import * as serviceApiRead from "../../../src/web/response/serviceApiRead";

jest.mock("../../../src/web/response/file/fileApiProvider", () => ({
  fileApi: {
    getSpecificationGroupFiles: jest.fn(),
    getSpecificationFiles: jest.fn(),
    deleteFile: jest.fn(),
  },
}));

jest.mock("../../../src/web/response/file", () => ({
  fileApi: {
    getSpecificationGroupFiles: jest.fn(),
    getSpecificationFiles: jest.fn(),
    deleteFile: jest.fn(),
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

jest.mock("vscode", () => {
  const mockUri = {
    joinPath: jest.fn((uri: any, ...parts: string[]) => ({
      path: `${uri.path}/${parts.join("/")}`,
      fsPath: `${uri.fsPath}/${parts.join("/")}`,
      toString: jest.fn(() => `${uri.path}/${parts.join("/")}`),
    })),
  };
  const mockWindow = { showInformationMessage: jest.fn(), showErrorMessage: jest.fn() };
  const mockVscode = {
    Uri: mockUri,
    FileType: { File: 1, Directory: 2 },
    workspace: { workspaceFolders: [{ uri: { path: "/workspace", fsPath: "/workspace" } }] },
    window: mockWindow,
  };
  return {
    __esModule: true,
    ...mockVscode,
    default: mockVscode,
  };
}, { virtual: true });

const consoleErrorSpy = jest.spyOn(console, "error").mockImplementation(() => {});

describe("deleteSpecificationGroup", () => {
  const serviceFileUri = {
    path: "/workspace/svc/svc.service.qip.yaml",
    fsPath: "/workspace/svc/svc.service.qip.yaml",
    toString: jest.fn().mockReturnValue("/workspace/svc/svc.service.qip.yaml"),
  } as unknown as Uri;

  const mockGetSpecGroupFiles = (fileApi as any).getSpecificationGroupFiles as jest.Mock;
  const mockGetSpecFiles = (fileApi as any).getSpecificationFiles as jest.Mock;
  const mockDeleteFile = (fileApi as any).deleteFile as jest.Mock;
  const mockParse = ContentParser.parseContentFromFile as jest.Mock;
  const mockGetMainService = (serviceApiRead as any).getMainService as jest.Mock;
  const mockShowInfo = (vscode.window.showInformationMessage as unknown as jest.Mock);
  const mockShowError = (vscode.window.showErrorMessage as unknown as jest.Mock);

  beforeEach(() => {
    jest.clearAllMocks();
    mockGetMainService.mockResolvedValue({ id: "svc", name: "svc" });
    mockGetSpecGroupFiles.mockResolvedValue(["g1.specification-group.qip.yaml", "g2.specification-group.qip.yaml"]);
    mockGetSpecFiles.mockResolvedValue(["s1.specification.qip.yaml", "s2.specification.qip.yaml", "s3.specification.qip.yaml"]);
    mockDeleteFile.mockResolvedValue(undefined);
    mockParse.mockImplementation(async (uri: any) => {
      const p = uri.path as string;
      if (p.includes("g1.specification-group")) return { id: "g1", name: "Group One" };
      if (p.includes("g2.specification-group")) return { id: "g2", name: "Group Two" };
      if (p.includes("s1.specification")) return { id: "s1", name: "S1", content: { parentId: "g1", specificationSources: [{ fileName: "openapi.yaml", name: "src1" }] } };
      if (p.includes("s2.specification")) return { id: "s2", name: "S2", content: { parentId: "g1", specificationSources: [{ fileName: "folder/a.yaml", name: "src2" }] } };
      if (p.includes("s3.specification")) return { id: "s3", name: "S3", content: { parentId: "g2" } };
      return { id: "unknown", name: "unknown" };
    });
  });

  afterAll(() => {
    consoleErrorSpy.mockRestore();
  });

  it("should delete spec sources, spec files and group file and show info when silent false", async () => {
    await deleteSpecificationGroup(serviceFileUri, "g1");

    expect(mockGetMainService).toHaveBeenCalledWith(serviceFileUri);
    expect(mockDeleteFile).toHaveBeenCalledWith(expect.objectContaining({ path: expect.stringContaining("resources/openapi.yaml") }));
    expect(mockDeleteFile).toHaveBeenCalledWith(expect.objectContaining({ path: expect.stringContaining("resources/folder/a.yaml") }));
    expect(mockDeleteFile).toHaveBeenCalledWith(expect.objectContaining({ path: expect.stringContaining("resources/folder") }));
    expect(mockDeleteFile).toHaveBeenCalledWith(expect.objectContaining({ path: expect.stringContaining("s1.specification.qip.yaml") }));
    expect(mockDeleteFile).toHaveBeenCalledWith(expect.objectContaining({ path: expect.stringContaining("s2.specification.qip.yaml") }));
    expect(mockDeleteFile).not.toHaveBeenCalledWith(expect.objectContaining({ path: expect.stringContaining("s3.specification.qip.yaml") }));
    expect(mockDeleteFile).toHaveBeenCalledWith(expect.objectContaining({ path: expect.stringContaining("g1.specification-group.qip.yaml") }));
    expect(mockShowInfo).toHaveBeenCalledWith('Specification group "Group One" has been deleted successfully!');
    expect(mockShowError).not.toHaveBeenCalled();
  });

  it("should not show info when silent true", async () => {
    await deleteSpecificationGroup(serviceFileUri, "g1", true);

    expect(mockShowInfo).not.toHaveBeenCalled();
    expect(mockShowError).not.toHaveBeenCalled();
    expect(mockDeleteFile).toHaveBeenCalledWith(expect.objectContaining({ path: expect.stringContaining("g1.specification-group.qip.yaml") }));
  });

  it("should delete only group file when no specs belong to group", async () => {
    mockGetSpecFiles.mockResolvedValue(["s3.specification.qip.yaml"]);

    await deleteSpecificationGroup(serviceFileUri, "g1");

    expect(mockDeleteFile).not.toHaveBeenCalledWith(expect.objectContaining({ path: expect.stringContaining("s3.specification") }));
    expect(mockDeleteFile).toHaveBeenCalledWith(expect.objectContaining({ path: expect.stringContaining("g1.specification-group.qip.yaml") }));
    expect(mockDeleteFile).toHaveBeenCalledTimes(1);
  });

  it("should skip group files that fail to parse and still find correct group", async () => {
    mockGetSpecGroupFiles.mockResolvedValue(["bad.specification-group.qip.yaml", "g1.specification-group.qip.yaml"]);
    mockParse.mockImplementation(async (uri: any) => {
      if (uri.path.includes("bad.specification-group")) throw new Error("parse fail");
      if (uri.path.includes("g1.specification-group")) return { id: "g1", name: "Group One" };
      return { id: "s1", name: "S1", content: { parentId: "g1" } };
    });
    mockGetSpecFiles.mockResolvedValue([]);

    await deleteSpecificationGroup(serviceFileUri, "g1");

    expect(consoleErrorSpy).toHaveBeenCalledWith(expect.stringContaining("Error reading specification group file"), expect.any(Error));
    expect(mockDeleteFile).toHaveBeenCalledWith(expect.objectContaining({ path: expect.stringContaining("g1.specification-group.qip.yaml") }));
  });

  it("should skip spec files that fail to parse", async () => {
    mockParse.mockImplementation(async (uri: any) => {
      if (uri.path.includes("g1.specification-group")) return { id: "g1", name: "Group One" };
      if (uri.path.includes("s1.specification")) throw new Error("bad spec");
      if (uri.path.includes("s2.specification")) return { id: "s2", name: "S2", content: { parentId: "g1" } };
      return { id: "unknown", name: "unknown" };
    });

    await deleteSpecificationGroup(serviceFileUri, "g1");

    expect(consoleErrorSpy).toHaveBeenCalledWith(expect.stringContaining("Error reading specification file"), expect.any(Error));
    expect(mockDeleteFile).toHaveBeenCalledWith(expect.objectContaining({ path: expect.stringContaining("s2.specification.qip.yaml") }));
    expect(mockDeleteFile).not.toHaveBeenCalledWith(expect.objectContaining({ path: expect.stringContaining("s1.specification.qip.yaml") }));
  });

  it("should swallow error deleting source file with not empty and still delete group", async () => {
    mockDeleteFile.mockImplementation((uri: any) => {
      if (uri.path.includes("resources/folder")) return Promise.reject(new Error("Directory folder is not empty"));
      if (uri.path.includes("resources/openapi.yaml")) return Promise.resolve(undefined);
      return Promise.resolve(undefined);
    });

    await deleteSpecificationGroup(serviceFileUri, "g1");

    expect(consoleErrorSpy).not.toHaveBeenCalledWith(expect.stringContaining("Error deleting source file"), expect.anything());
    expect(mockDeleteFile).toHaveBeenCalledWith(expect.objectContaining({ path: expect.stringContaining("g1.specification-group.qip.yaml") }));
  });

  it("should log error when deleting source file fails with other error", async () => {
    mockDeleteFile.mockImplementation((uri: any) => {
      if (uri.path.includes("resources/openapi.yaml")) return Promise.reject(new Error("EBUSY"));
      return Promise.resolve(undefined);
    });

    await deleteSpecificationGroup(serviceFileUri, "g1");

    expect(consoleErrorSpy).toHaveBeenCalledWith(expect.stringContaining("Error deleting source file"), expect.any(Error));
    expect(mockDeleteFile).toHaveBeenCalledWith(expect.objectContaining({ path: expect.stringContaining("s1.specification.qip.yaml") }));
  });

  it("should swallow error deleting spec file and continue", async () => {
    mockDeleteFile.mockImplementation((uri: any) => {
      if (uri.path.includes("s1.specification.qip.yaml")) return Promise.reject(new Error("delete fail"));
      return Promise.resolve(undefined);
    });

    await deleteSpecificationGroup(serviceFileUri, "g1");

    expect(consoleErrorSpy).toHaveBeenCalledWith(expect.stringContaining("Error deleting specification file"), expect.any(Error));
    expect(mockDeleteFile).toHaveBeenCalledWith(expect.objectContaining({ path: expect.stringContaining("s2.specification.qip.yaml") }));
    expect(mockDeleteFile).toHaveBeenCalledWith(expect.objectContaining({ path: expect.stringContaining("g1.specification-group.qip.yaml") }));
  });

  it("should throw and show error when group file delete fails and silent false", async () => {
    mockDeleteFile.mockImplementation((uri: any) => {
      if (uri.path.includes("g1.specification-group.qip.yaml")) return Promise.reject(new Error("group delete fail"));
      return Promise.resolve(undefined);
    });

    await expect(deleteSpecificationGroup(serviceFileUri, "g1")).rejects.toThrow("group delete fail");

    expect(consoleErrorSpy).toHaveBeenCalledWith("deleteSpecificationGroup: Error:", expect.any(Error));
    expect(mockShowError).toHaveBeenCalledWith(expect.stringContaining("Failed to delete specification group"));
    expect(mockShowInfo).not.toHaveBeenCalled();
  });

  it("should throw but not show error when silent true and group file delete fails", async () => {
    mockDeleteFile.mockImplementation((uri: any) => {
      if (uri.path.includes("g1.specification-group.qip.yaml")) return Promise.reject(new Error("group delete fail"));
      return Promise.resolve(undefined);
    });

    await expect(deleteSpecificationGroup(serviceFileUri, "g1", true)).rejects.toThrow();

    expect(consoleErrorSpy).toHaveBeenCalledWith("deleteSpecificationGroup: Error:", expect.any(Error));
    expect(mockShowError).not.toHaveBeenCalled();
    expect(mockShowInfo).not.toHaveBeenCalled();
  });

  it("should throw when group not found and show error when not silent", async () => {
    await expect(deleteSpecificationGroup(serviceFileUri, "missing")).rejects.toThrow("Specification group with id missing not found");

    expect(consoleErrorSpy).toHaveBeenCalledWith("deleteSpecificationGroup: Error:", expect.any(Error));
    expect(mockShowError).toHaveBeenCalled();
  });

  it("should throw when group not found and not show error when silent", async () => {
    await expect(deleteSpecificationGroup(serviceFileUri, "missing", true)).rejects.toThrow();

    expect(mockShowError).not.toHaveBeenCalled();
  });

  it("should throw when service not found", async () => {
    mockGetMainService.mockResolvedValue(null);

    await expect(deleteSpecificationGroup(serviceFileUri, "g1")).rejects.toThrow("Service not found");
  });
});
