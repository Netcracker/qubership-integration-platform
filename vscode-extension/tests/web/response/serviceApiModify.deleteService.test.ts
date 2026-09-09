import { Uri } from "vscode";
import { deleteService } from "../../../src/web/response/serviceApiModify";
import { fileApi } from "../../../src/web/response/file/fileApiProvider";
import { ContentParser } from "../../../src/web/api-services/parsers/ContentParser";
import { QipFileType } from "../../../src/web/response/serviceApiUtils";

jest.mock("../../../src/web/response/file/fileApiProvider", () => ({
  fileApi: {
    getFileType: jest.fn(),
    getSpecificationGroupFiles: jest.fn(),
    getSpecificationFiles: jest.fn(),
    getDirectoriesToRemove: jest.fn(),
    deleteFile: jest.fn(),
  },
}));

jest.mock("../../../src/web/response/file", () => ({
  fileApi: {
    getFileType: jest.fn(),
    getSpecificationGroupFiles: jest.fn(),
    getSpecificationFiles: jest.fn(),
    getDirectoriesToRemove: jest.fn(),
    deleteFile: jest.fn(),
  },
}));

jest.mock("../../../src/web/api-services/parsers/ContentParser", () => ({
  ContentParser: {
    parseContentFromFile: jest.fn(),
  },
}));

jest.mock("vscode", () => ({
  Uri: {
    joinPath: jest.fn((uri: any, ...parts: string[]) => ({
      path: `${uri.path}/${parts.join("/")}`,
      fsPath: `${uri.fsPath}/${parts.join("/")}`,
      toString: jest.fn(() => `${uri.path}/${parts.join("/")}`),
    })),
  },
  FileType: { File: 1, Directory: 2 },
  workspace: { workspaceFolders: [{ uri: { path: "/workspace", fsPath: "/workspace" } }] },
  window: { showInformationMessage: jest.fn(), showErrorMessage: jest.fn() },
}), { virtual: true });

const consoleErrorSpy = jest.spyOn(console, "error").mockImplementation(() => {});

describe("deleteService", () => {
  const serviceFileUri = {
    path: "/workspace/service/svc.service.qip.yaml",
    fsPath: "/workspace/service/svc.service.qip.yaml",
    toString: jest.fn().mockReturnValue("/workspace/service/svc.service.qip.yaml"),
  } as unknown as Uri;

  const serviceFolderUri = {
    path: "/workspace/service",
    fsPath: "/workspace/service",
    toString: jest.fn().mockReturnValue("/workspace/service"),
  } as unknown as Uri;

  const resourcesUri = {
    path: "/workspace/service/resources",
    fsPath: "/workspace/service/resources",
    toString: jest.fn().mockReturnValue("/workspace/service/resources"),
  } as unknown as Uri;

  const mockGetFileType = (fileApi as any).getFileType as jest.Mock;
  const mockGetSpecGroupFiles = (fileApi as any).getSpecificationGroupFiles as jest.Mock;
  const mockGetSpecFiles = (fileApi as any).getSpecificationFiles as jest.Mock;
  const mockGetDirectoriesToRemove = (fileApi as any).getDirectoriesToRemove as jest.Mock;
  const mockDeleteFile = (fileApi as any).deleteFile as jest.Mock;
  const mockParse = ContentParser.parseContentFromFile as jest.Mock;

  beforeEach(() => {
    jest.clearAllMocks();
    mockGetDirectoriesToRemove.mockResolvedValue([resourcesUri, serviceFolderUri]);
    mockDeleteFile.mockResolvedValue(undefined);
    mockGetFileType.mockResolvedValue(QipFileType.SERVICE);
    mockGetSpecGroupFiles.mockResolvedValue([]);
    mockGetSpecFiles.mockResolvedValue([]);
    mockParse.mockResolvedValue({ id: "group-1", name: "g", content: {} });
  });

  afterAll(() => {
    consoleErrorSpy.mockRestore();
  });

  describe("resource cleanup for SERVICE", () => {
    it("should attempt to delete spec groups when fileType is SERVICE", async () => {
      mockGetSpecGroupFiles.mockResolvedValue(["a.specification-group.qip.yaml", "b.specification-group.qip.yaml"]);
      mockParse.mockResolvedValue({ id: "g1", name: "g1", content: {} });
      mockGetSpecFiles.mockResolvedValue([]);

      await deleteService(serviceFileUri);

      expect(mockGetFileType).toHaveBeenCalledWith(serviceFileUri);
      expect(mockGetSpecGroupFiles).toHaveBeenCalledWith(serviceFileUri);
      expect(mockDeleteFile).toHaveBeenCalledWith(serviceFileUri);
    });

    it("should not call getSpecificationGroupFiles when fileType is CONTEXT_SERVICE", async () => {
      mockGetFileType.mockResolvedValue(QipFileType.CONTEXT_SERVICE);

      await deleteService(serviceFileUri);

      expect(mockGetSpecGroupFiles).not.toHaveBeenCalled();
      expect(mockDeleteFile).toHaveBeenCalledWith(serviceFileUri);
    });

    it("should not call getSpecificationGroupFiles when fileType is MCP_SERVICE", async () => {
      mockGetFileType.mockResolvedValue(QipFileType.MCP_SERVICE);

      await deleteService(serviceFileUri);

      expect(mockGetSpecGroupFiles).not.toHaveBeenCalled();
    });

    it("should not call getSpecificationGroupFiles when fileType is UNKNOWN", async () => {
      mockGetFileType.mockResolvedValue(QipFileType.UNKNOWN);

      await deleteService(serviceFileUri);

      expect(mockGetSpecGroupFiles).not.toHaveBeenCalled();
    });

    it("should swallow error from getFileType catch and still delete files (UNKNOWN fallback)", async () => {
      mockGetFileType.mockRejectedValue(new Error("stat failed"));

      await deleteService(serviceFileUri);

      expect(mockGetSpecGroupFiles).not.toHaveBeenCalled();
      expect(mockGetDirectoriesToRemove).toHaveBeenCalledWith(serviceFileUri);
      expect(mockDeleteFile).toHaveBeenCalledWith(serviceFileUri);
    });

    it("should swallow error from getSpecificationGroupFiles and still delete files", async () => {
      mockGetSpecGroupFiles.mockRejectedValue(new Error("read failed"));

      await deleteService(serviceFileUri);

      expect(mockDeleteFile).toHaveBeenCalledWith(serviceFileUri);
    });

    it("should skip group when parse returns null or missing id and continue", async () => {
      mockGetSpecGroupFiles.mockResolvedValue(["a.specification-group.qip.yaml", "b.specification-group.qip.yaml", "c.specification-group.qip.yaml"]);
      mockParse.mockResolvedValueOnce(null).mockResolvedValueOnce({ name: "no-id" }).mockResolvedValueOnce({ id: "g3", name: "g3", content: {} });
      mockGetSpecFiles.mockResolvedValue([]);

      await deleteService(serviceFileUri);

      expect(mockDeleteFile).toHaveBeenCalledWith(serviceFileUri);
    });

    it("should swallow error from deleteSpecificationGroup and still delete service", async () => {
      mockGetSpecGroupFiles.mockResolvedValue(["a.specification-group.qip.yaml", "b.specification-group.qip.yaml"]);
      mockParse.mockResolvedValue({ id: "g1", name: "g1", content: {} });
      const { fileApi: innerFileApi } = await import("../../../src/web/response/file/fileApiProvider");
      (innerFileApi.getSpecificationFiles as jest.Mock).mockRejectedValue(new Error("fail group"));
      await deleteService(serviceFileUri);

      expect(mockDeleteFile).toHaveBeenCalledWith(serviceFileUri);
    });

    it("should log cleanup error with original exception from outer block on sync throw", async () => {
      const cause = new Error("ENOENT");
      mockGetFileType.mockImplementation(() => { throw cause; });

      await deleteService(serviceFileUri);

      expect(consoleErrorSpy).toHaveBeenCalledWith("Failed to cleanup resources before deleting service", cause);
      expect(mockDeleteFile).toHaveBeenCalledWith(serviceFileUri);
    });
  });

  describe("deletion sequence", () => {
    it("should resolve directories to remove and delete service file", async () => {
      await deleteService(serviceFileUri);

      expect(mockGetDirectoriesToRemove).toHaveBeenCalledWith(serviceFileUri);
      expect(mockDeleteFile).toHaveBeenCalledWith(serviceFileUri);
    });

    it("should delete directories after deleting service file", async () => {
      const order: string[] = [];
      mockDeleteFile.mockImplementation(async (uri: Uri) => {
        if ((uri as unknown as typeof resourcesUri) === resourcesUri) order.push("resources");
        else if ((uri as unknown as typeof serviceFileUri) === serviceFileUri) order.push("serviceFile");
        else if ((uri as unknown as typeof serviceFolderUri) === serviceFolderUri) order.push("folder");
      });

      await deleteService(serviceFileUri);

      expect(order[0]).toBe("serviceFile");
      expect(order[1]).toBe("resources");
      expect(order[2]).toBe("folder");
    });

    it("should swallow error from deleting directory", async () => {
      mockDeleteFile.mockImplementation((uri: Uri) => {
        if ((uri as unknown as typeof resourcesUri) === resourcesUri) {
          return Promise.reject(new Error("resources not found"));
        }
        return Promise.resolve(undefined);
      });

      await expect(deleteService(serviceFileUri)).resolves.toBeUndefined();

      expect(mockDeleteFile).toHaveBeenCalledWith(resourcesUri);
      expect(mockDeleteFile).toHaveBeenCalledWith(serviceFileUri);
      expect(mockDeleteFile).toHaveBeenCalledWith(serviceFolderUri);
    });

    it("should propagate error when deleting service file fails", async () => {
      mockDeleteFile.mockImplementation((uri: Uri) => {
        if ((uri as unknown as typeof serviceFileUri) === serviceFileUri) {
          return Promise.reject(new Error("delete service failed"));
        }
        return Promise.resolve(undefined);
      });

      await expect(deleteService(serviceFileUri)).rejects.toThrow("delete service failed");
    });

    it("should not attempt directory deletion when service file deletion fails", async () => {
      mockDeleteFile.mockImplementation((uri: Uri) => {
        if ((uri as unknown as typeof serviceFileUri) === serviceFileUri) {
          return Promise.reject(new Error("delete service failed"));
        }
        return Promise.resolve(undefined);
      });

      await expect(deleteService(serviceFileUri)).rejects.toThrow("delete service failed");

      expect(mockDeleteFile).not.toHaveBeenCalledWith(resourcesUri);
      expect(mockDeleteFile).not.toHaveBeenCalledWith(serviceFolderUri);
    });
  });

  describe("directory deletion via getDirectoriesToRemove", () => {
    it("should delete all directories returned by getDirectoriesToRemove", async () => {
      mockGetDirectoriesToRemove.mockResolvedValue([resourcesUri, serviceFolderUri]);

      await deleteService(serviceFileUri);

      expect(mockDeleteFile).toHaveBeenCalledWith(resourcesUri);
      expect(mockDeleteFile).toHaveBeenCalledWith(serviceFolderUri);
    });

    it("should not delete any directory when getDirectoriesToRemove returns empty", async () => {
      mockGetDirectoriesToRemove.mockResolvedValue([]);

      await deleteService(serviceFileUri);

      expect(mockDeleteFile).toHaveBeenCalledWith(serviceFileUri);
      expect(mockDeleteFile).toHaveBeenCalledTimes(1);
    });

    it("should swallow error from deleting directory and still delete remaining", async () => {
      mockGetDirectoriesToRemove.mockResolvedValue([resourcesUri, serviceFolderUri]);
      mockDeleteFile.mockImplementation((uri: Uri) => {
        if ((uri as unknown as typeof serviceFolderUri) === serviceFolderUri) {
          return Promise.reject(new Error("folder not empty"));
        }
        return Promise.resolve(undefined);
      });

      await expect(deleteService(serviceFileUri)).resolves.toBeUndefined();

      expect(mockDeleteFile).toHaveBeenCalledWith(resourcesUri);
      expect(mockDeleteFile).toHaveBeenCalledWith(serviceFolderUri);
    });

    it("should handle CONTEXT_SERVICE directory without resources", async () => {
      mockGetFileType.mockResolvedValue(QipFileType.CONTEXT_SERVICE);
      mockGetDirectoriesToRemove.mockResolvedValue([serviceFolderUri]);

      await deleteService(serviceFileUri);

      expect(mockDeleteFile).toHaveBeenCalledWith(serviceFileUri);
      expect(mockDeleteFile).toHaveBeenCalledWith(serviceFolderUri);
      expect(mockDeleteFile).toHaveBeenCalledTimes(2);
    });

    it("should handle single directory (MCP without resources)", async () => {
      mockGetFileType.mockResolvedValue(QipFileType.MCP_SERVICE);
      mockGetDirectoriesToRemove.mockResolvedValue([serviceFolderUri]);

      await deleteService(serviceFileUri);

      expect(mockDeleteFile).toHaveBeenCalledWith(serviceFolderUri);
      expect(mockDeleteFile).toHaveBeenCalledTimes(2);
    });
  });
});
