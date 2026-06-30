import { Uri } from "vscode";
import {
  buildExportImagesStartupPayload,
  getExportTargetFromFilePath,
  resolveExportPaths,
  toUri,
} from "../../src/web/exportImagesHandler";
import { fileApi } from "../../src/web/response/file";

jest.mock("../../src/web/response/file", () => ({
  fileApi: {
    parseFile: jest.fn(),
  },
}));

const parseFile = fileApi.parseFile as jest.Mock;

const uriApi = {
  parse: jest.fn((value: string) => Uri.parse(value)),
  file: jest.fn((path: string) => Uri.file(path)),
};

describe("exportImagesHandler", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    parseFile.mockResolvedValue({ id: "chain-1" });
  });

  describe("toUri", () => {
    test("returns undefined for empty values", () => {
      expect(toUri(undefined, uriApi)).toBeUndefined();
      expect(toUri("", uriApi)).toBeUndefined();
    });

    test("returns Uri values unchanged", () => {
      const uri = Uri.file("/workspace/chain.yaml");
      expect(toUri(uri, uriApi)).toBe(uri);
    });

    test("parses scheme-based paths", () => {
      toUri("file:///workspace/chain.yaml", uriApi);
      expect(uriApi.parse).toHaveBeenCalledWith("file:///workspace/chain.yaml");
    });

    test("uses file() for relative paths", () => {
      toUri("/workspace/chain.yaml", uriApi);
      expect(uriApi.file).toHaveBeenCalledWith("/workspace/chain.yaml");
    });
  });

  describe("resolveExportPaths", () => {
    test("prefers config.filePaths", () => {
      expect(
        resolveExportPaths("ignored", {
          outputDir: "/tmp",
          filePaths: ["/a.yaml", "/b.yaml"],
        }),
      ).toEqual(["/a.yaml", "/b.yaml"]);
    });

    test("falls back to filePath argument", () => {
      expect(
        resolveExportPaths("file:///workspace/chain.yaml", { outputDir: "/tmp" }),
      ).toEqual(["file:///workspace/chain.yaml"]);
    });

    test("returns empty list when no paths are provided", () => {
      expect(resolveExportPaths(undefined, { outputDir: "/tmp" })).toEqual([]);
    });
  });

  describe("getExportTargetFromFilePath", () => {
    test("builds export target from chain file", async () => {
      const target = await getExportTargetFromFilePath(
        "file:///workspace/chain-1.chain.qip.yaml",
      );

      expect(parseFile).toHaveBeenCalled();
      expect(target).toEqual({
        chainId: "chain-1",
        filePath: "file:///workspace/chain-1.chain.qip.yaml",
        outputName: "chain-1",
      });
    });

    test("throws when file path is empty", async () => {
      await expect(getExportTargetFromFilePath("")).rejects.toThrow(
        "Export file path is required",
      );
    });

    test("throws when chain id cannot be resolved", async () => {
      parseFile.mockResolvedValueOnce({ name: "no-id" });

      await expect(
        getExportTargetFromFilePath("/workspace/invalid.chain.qip.yaml"),
      ).rejects.toThrow("Unable to determine chain id");
    });
  });

  describe("buildExportImagesStartupPayload", () => {
    test("builds startup payload for batch export", () => {
      const targets = [
        { chainId: "chain-1", filePath: "/a.yaml", outputName: "chain-1" },
      ];

      expect(
        buildExportImagesStartupPayload(
          "file:///workspace/chain-1.chain.qip.yaml",
          { outputDir: "/tmp/export" },
          targets,
        ),
      ).toEqual({
        filePath: "file:///workspace/chain-1.chain.qip.yaml",
        exportConfig: {
          outputDir: "/tmp/export",
          imageFormat: "png",
          targets,
        },
        targets,
      });
    });

    test("uses svg image format when configured", () => {
      const targets = [
        { chainId: "chain-1", filePath: "/a.yaml", outputName: "chain-1" },
      ];
      const fileUri = Uri.file("/workspace/chain-1.chain.qip.yaml");

      expect(
        buildExportImagesStartupPayload(
          fileUri,
          { outputDir: "/tmp/export", imageFormat: "svg" },
          targets,
        ),
      ).toEqual({
        filePath: fileUri.toString(),
        exportConfig: {
          outputDir: "/tmp/export",
          imageFormat: "svg",
          targets,
        },
        targets,
      });
    });
  });
});
