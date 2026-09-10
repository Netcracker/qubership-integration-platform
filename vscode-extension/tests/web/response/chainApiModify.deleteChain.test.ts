import { Uri } from "vscode";
import { deleteChain } from "../../../src/web/response/chainApiModify";
import { fileApi } from "../../../src/web/response/file";
import { deleteElementsPropertyFiles } from "../../../src/web/response/resourceUtils";

jest.mock("../../../src/web/response/file", () => ({
  fileApi: {
    getMainChain: jest.fn(),
    getDirectoriesToRemove: jest.fn(),
    deleteFile: jest.fn(),
  },
}));

jest.mock("../../../src/web/response/resourceUtils", () => ({
  deleteElementsPropertyFiles: jest.fn(),
  collectFilenamesFromElementTree: jest.fn(),
  cleanupOrphanPropertyFiles: jest.fn(),
}));

// Keep real global console but spy
const consoleErrorSpy = jest.spyOn(console, "error").mockImplementation(() => {});

describe("deleteChain", () => {
  const chainFileUri = {
    path: "/workspace/chains/my-chain/my-chain.chain.qip.yaml",
    fsPath: "/workspace/chains/my-chain/my-chain.chain.qip.yaml",
    toString: jest.fn().mockReturnValue("/workspace/chains/my-chain/my-chain.chain.qip.yaml"),
  } as unknown as Uri;

  const chainFolderUri = {
    path: "/workspace/chains/my-chain",
    fsPath: "/workspace/chains/my-chain",
    toString: jest.fn().mockReturnValue("/workspace/chains/my-chain"),
  } as unknown as Uri;

  const resourcesUri = {
    path: "/workspace/chains/my-chain/resources",
    fsPath: "/workspace/chains/my-chain/resources",
    toString: jest.fn().mockReturnValue("/workspace/chains/my-chain/resources"),
  } as unknown as Uri;

  const mockGetMainChain = fileApi.getMainChain as jest.Mock;
  const mockGetDirectoriesToRemove = fileApi.getDirectoriesToRemove as jest.Mock;
  const mockDeleteFile = fileApi.deleteFile as jest.Mock;
  const mockDeleteElementsPropertyFiles = deleteElementsPropertyFiles as jest.Mock;

  beforeEach(() => {
    jest.clearAllMocks();
    mockGetDirectoriesToRemove.mockResolvedValue([resourcesUri, chainFolderUri]);
    mockDeleteFile.mockResolvedValue(undefined);
    mockGetMainChain.mockResolvedValue({
      id: "chain-1",
      content: { elements: [] },
    });
    mockDeleteElementsPropertyFiles.mockResolvedValue(undefined);
  });

  afterAll(() => {
    consoleErrorSpy.mockRestore();
  });

  describe("resource cleanup", () => {
    it("should call deleteElementsPropertyFiles when chain has elements", async () => {
      const elements = [
        { id: "el-1", type: "service" } as unknown as never,
        { id: "el-2", type: "http-trigger" } as unknown as never,
      ];
      mockGetMainChain.mockResolvedValue({
        id: "chain-1",
        content: { elements },
      });

      await deleteChain(chainFileUri);

      expect(mockGetMainChain).toHaveBeenCalledWith(chainFileUri);
      expect(mockDeleteElementsPropertyFiles).toHaveBeenCalledWith(chainFileUri, elements);
    });

    it("should not call deleteElementsPropertyFiles when elements is undefined", async () => {
      mockGetMainChain.mockResolvedValue({
        id: "chain-1",
        content: {},
      } as never);

      await deleteChain(chainFileUri);

      expect(mockDeleteElementsPropertyFiles).not.toHaveBeenCalled();
    });

    it("should not call deleteElementsPropertyFiles when elements is empty", async () => {
      mockGetMainChain.mockResolvedValue({
        id: "chain-1",
        content: { elements: [] },
      } as never);

      await deleteChain(chainFileUri);

      expect(mockDeleteElementsPropertyFiles).not.toHaveBeenCalled();
    });

    it("should swallow error from getMainChain and still delete files", async () => {
      mockGetMainChain.mockRejectedValue(new Error("read failed"));

      await deleteChain(chainFileUri);

      expect(consoleErrorSpy).toHaveBeenCalledWith("Failed to cleanup resources before deleting chain", expect.any(Error));
      expect(mockGetDirectoriesToRemove).toHaveBeenCalledWith(chainFileUri);
      expect(mockDeleteFile).toHaveBeenCalledWith(chainFileUri);
    });

    it("should swallow error from deleteElementsPropertyFiles and still delete files", async () => {
      const elements = [{ id: "el-1" } as unknown as never];
      mockGetMainChain.mockResolvedValue({
        id: "chain-1",
        content: { elements },
      });
      mockDeleteElementsPropertyFiles.mockRejectedValue(new Error("cleanup failed"));

      await deleteChain(chainFileUri);

      expect(consoleErrorSpy).toHaveBeenCalledWith("Failed to cleanup resources before deleting chain", expect.any(Error));
      expect(mockDeleteFile).toHaveBeenCalledWith(chainFileUri);
    });

    it("should log cleanup error with original exception", async () => {
      const cause = new Error("ENOENT");
      mockGetMainChain.mockRejectedValue(cause);

      await deleteChain(chainFileUri);

      expect(consoleErrorSpy).toHaveBeenCalledWith("Failed to cleanup resources before deleting chain", cause);
    });
  });

  describe("deletion sequence", () => {
    it("should resolve directories to remove and delete chain file", async () => {
      await deleteChain(chainFileUri);

      expect(mockGetDirectoriesToRemove).toHaveBeenCalledWith(chainFileUri);
      expect(mockDeleteFile).toHaveBeenCalledWith(chainFileUri);
    });

    it("should delete directories after deleting chain file", async () => {
      const order: string[] = [];
      mockDeleteFile.mockImplementation(async (uri: Uri) => {
        if ((uri as unknown as typeof resourcesUri) === resourcesUri) order.push("resources");
        else if ((uri as unknown as typeof chainFileUri) === chainFileUri) order.push("chainFile");
        else if ((uri as unknown as typeof chainFolderUri) === chainFolderUri) order.push("folder");
      });

      await deleteChain(chainFileUri);

      expect(order[0]).toBe("chainFile");
      expect(order[1]).toBe("resources");
      expect(order[2]).toBe("folder");
    });

    it("should always delete chain file", async () => {
      await deleteChain(chainFileUri);

      expect(mockDeleteFile).toHaveBeenCalledWith(chainFileUri);
    });

    it("should swallow error from deleting directory", async () => {
      mockDeleteFile.mockImplementation((uri: Uri) => {
        if ((uri as unknown as typeof resourcesUri) === resourcesUri) {
          return Promise.reject(new Error("resources not found"));
        }
        return Promise.resolve(undefined);
      });

      await expect(deleteChain(chainFileUri)).resolves.toBeUndefined();

      expect(mockDeleteFile).toHaveBeenCalledWith(resourcesUri);
      expect(mockDeleteFile).toHaveBeenCalledWith(chainFileUri);
      expect(mockDeleteFile).toHaveBeenCalledWith(chainFolderUri);
    });

    it("should propagate error when deleting chain file fails", async () => {
      mockDeleteFile.mockImplementation((uri: Uri) => {
        if ((uri as unknown as typeof chainFileUri) === chainFileUri) {
          return Promise.reject(new Error("delete chain failed"));
        }
        return Promise.resolve(undefined);
      });

      await expect(deleteChain(chainFileUri)).rejects.toThrow("delete chain failed");
    });

    it("should not attempt directory deletion when chain file deletion fails", async () => {
      mockDeleteFile.mockImplementation((uri: Uri) => {
        if ((uri as unknown as typeof chainFileUri) === chainFileUri) {
          return Promise.reject(new Error("delete chain failed"));
        }
        return Promise.resolve(undefined);
      });

      await expect(deleteChain(chainFileUri)).rejects.toThrow("delete chain failed");

      expect(mockDeleteFile).not.toHaveBeenCalledWith(resourcesUri);
      expect(mockDeleteFile).not.toHaveBeenCalledWith(chainFolderUri);
    });
  });

  describe("directory deletion via getDirectoriesToRemove", () => {
    it("should delete all directories returned by getDirectoriesToRemove", async () => {
      mockGetDirectoriesToRemove.mockResolvedValue([resourcesUri, chainFolderUri]);

      await deleteChain(chainFileUri);

      expect(mockDeleteFile).toHaveBeenCalledWith(resourcesUri);
      expect(mockDeleteFile).toHaveBeenCalledWith(chainFolderUri);
    });

    it("should not delete any directory when getDirectoriesToRemove returns empty", async () => {
      mockGetDirectoriesToRemove.mockResolvedValue([]);

      await deleteChain(chainFileUri);

      expect(mockDeleteFile).toHaveBeenCalledWith(chainFileUri);
      expect(mockDeleteFile).toHaveBeenCalledTimes(1);
    });

    it("should swallow error from deleting directory and still delete remaining", async () => {
      mockGetDirectoriesToRemove.mockResolvedValue([resourcesUri, chainFolderUri]);
      mockDeleteFile.mockImplementation((uri: Uri) => {
        if ((uri as unknown as typeof chainFolderUri) === chainFolderUri) {
          return Promise.reject(new Error("folder not empty"));
        }
        return Promise.resolve(undefined);
      });

      await expect(deleteChain(chainFileUri)).resolves.toBeUndefined();

      expect(mockDeleteFile).toHaveBeenCalledWith(resourcesUri);
      expect(mockDeleteFile).toHaveBeenCalledWith(chainFolderUri);
    });

    it("should still attempt second directory after first deletion failure", async () => {
      mockGetDirectoriesToRemove.mockResolvedValue([resourcesUri, chainFolderUri]);
      mockDeleteFile.mockImplementation((uri: Uri) => {
        if ((uri as unknown as typeof resourcesUri) === resourcesUri) {
          return Promise.reject(new Error("resources missing"));
        }
        return Promise.resolve(undefined);
      });

      await deleteChain(chainFileUri);

      expect(mockDeleteFile).toHaveBeenCalledWith(chainFolderUri);
    });

    it("should handle single directory", async () => {
      mockGetDirectoriesToRemove.mockResolvedValue([resourcesUri]);

      await deleteChain(chainFileUri);

      expect(mockDeleteFile).toHaveBeenCalledWith(resourcesUri);
      expect(mockDeleteFile).toHaveBeenCalledWith(chainFileUri);
      expect(mockDeleteFile).toHaveBeenCalledTimes(2);
    });
  });

  describe("overall ordering with no resources", () => {
    it("should handle chain without elements and complete all deletions", async () => {
      mockGetMainChain.mockResolvedValue({
        id: "chain-1",
        content: { elements: [] },
      } as never);
      mockGetDirectoriesToRemove.mockResolvedValue([resourcesUri, chainFolderUri]);

      await deleteChain(chainFileUri);

      expect(mockDeleteElementsPropertyFiles).not.toHaveBeenCalled();
      expect(mockDeleteFile).toHaveBeenCalledWith(chainFileUri);
      expect(mockDeleteFile).toHaveBeenCalledWith(resourcesUri);
      expect(mockDeleteFile).toHaveBeenCalledWith(chainFolderUri);
      expect(mockDeleteFile).toHaveBeenCalledTimes(3);
    });
  });
});
