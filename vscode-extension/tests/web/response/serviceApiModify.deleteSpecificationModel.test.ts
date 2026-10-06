import { Uri } from "vscode";
import * as vscode from "vscode";
import { deleteSpecificationModel } from "../../../src/web/response/serviceApiModify";
import { fileApi } from "../../../src/web/response/file/fileApiProvider";
import { ContentParser } from "../../../src/web/api-services/parsers/ContentParser";

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

describe("deleteSpecificationModel silent flag", () => {
  const serviceFileUri = {
    path: "/workspace/svc/svc.service.qip.yaml",
    fsPath: "/workspace/svc/svc.service.qip.yaml",
    toString: jest.fn().mockReturnValue("/workspace/svc/svc.service.qip.yaml"),
  } as unknown as Uri;

  const mockDeleteFile = (fileApi as any).deleteFile as jest.Mock;
  const mockGetSpecFiles = (fileApi as any).getSpecificationFiles as jest.Mock;
  const mockParse = ContentParser.parseContentFromFile as jest.Mock;
  const mockShowInfo = vscode.window
    .showInformationMessage as unknown as jest.Mock;
  const mockShowError = vscode.window.showErrorMessage as unknown as jest.Mock;

  beforeEach(() => {
    jest.clearAllMocks();
    mockDeleteFile.mockResolvedValue(undefined);
    mockGetSpecFiles.mockResolvedValue(["spec1.specification.qip.yaml"]);
    mockParse.mockResolvedValue({
      id: "spec1",
      name: "Spec One",
      content: {
        parentId: "group1",
        specificationSources: [{ fileName: "openapi.yaml", name: "src1" }],
      },
    });
  });

  afterAll(() => {
    consoleErrorSpy.mockRestore();
  });

  it("should delete sources and spec file and show info when silent is false (default)", async () => {
    await deleteSpecificationModel(serviceFileUri, "spec1");

    expect(mockGetSpecFiles).toHaveBeenCalledWith(serviceFileUri);
    expect(mockDeleteFile).toHaveBeenCalledWith(
      expect.objectContaining({ path: expect.stringContaining("resources/openapi.yaml") }),
    );
    expect(mockDeleteFile).toHaveBeenCalledWith(
      expect.objectContaining({ path: expect.stringContaining("spec1.specification.qip.yaml") }),
    );
    expect(mockShowInfo).toHaveBeenCalledWith('Specification "Spec One" has been deleted successfully!');
    expect(mockShowError).not.toHaveBeenCalled();
  });

  it("should not show info when silent is true", async () => {
    await deleteSpecificationModel(serviceFileUri, "spec1", true);

    expect(mockDeleteFile).toHaveBeenCalledWith(
      expect.objectContaining({ path: expect.stringContaining("spec1.specification.qip.yaml") }),
    );
    expect(mockShowInfo).not.toHaveBeenCalled();
    expect(mockShowError).not.toHaveBeenCalled();
  });

  it("should show error when specification is not found and silent is false", async () => {
    mockGetSpecFiles.mockResolvedValue(["other.specification.qip.yaml"]);
    mockParse.mockResolvedValue({ id: "other", name: "Other" });

    await expect(deleteSpecificationModel(serviceFileUri, "missing")).rejects.toThrow(
      "Specification with id missing not found",
    );

    expect(consoleErrorSpy).toHaveBeenCalledWith("[deleteSpecificationModel] Error:", expect.any(Error));
    expect(mockShowError).toHaveBeenCalledWith(expect.stringContaining("Failed to delete specification"));
    expect(mockShowInfo).not.toHaveBeenCalled();
  });

  it("should throw without showing error when silent is true", async () => {
    mockGetSpecFiles.mockResolvedValue(["other.specification.qip.yaml"]);
    mockParse.mockResolvedValue({ id: "other", name: "Other" });

    await expect(deleteSpecificationModel(serviceFileUri, "missing", true)).rejects.toThrow(
      "Specification with id missing not found",
    );

    expect(consoleErrorSpy).toHaveBeenCalledWith("[deleteSpecificationModel] Error:", expect.any(Error));
    expect(mockShowError).not.toHaveBeenCalled();
    expect(mockShowInfo).not.toHaveBeenCalled();
  });

  it("should show error when spec file delete fails and silent is false", async () => {
    mockDeleteFile.mockImplementation((uri: any) => {
      if ((uri.path as string).includes("spec1.specification.qip.yaml")) {
        return Promise.reject(new Error("spec delete fail"));
      }
      return Promise.resolve(undefined);
    });

    await expect(deleteSpecificationModel(serviceFileUri, "spec1")).rejects.toThrow("spec delete fail");

    expect(mockShowError).toHaveBeenCalledWith(expect.stringContaining("Failed to delete specification"));
    expect(mockShowInfo).not.toHaveBeenCalled();
  });

  it("should throw without showing error when spec file delete fails and silent is true", async () => {
    mockDeleteFile.mockImplementation((uri: any) => {
      if ((uri.path as string).includes("spec1.specification.qip.yaml")) {
        return Promise.reject(new Error("spec delete fail"));
      }
      return Promise.resolve(undefined);
    });

    await expect(deleteSpecificationModel(serviceFileUri, "spec1", true)).rejects.toThrow("spec delete fail");

    expect(mockShowError).not.toHaveBeenCalled();
    expect(mockShowInfo).not.toHaveBeenCalled();
  });
});
