import { Uri } from "vscode";
import {
  decodeBase64,
  resolveOutputFileUri,
  sanitizeExportOutputName,
} from "../../src/web/exportImageUtils";
import { fileApi } from "../../src/web/response/file";

jest.mock("../../src/web/response/file", () => ({
  fileApi: {
    getRootDirectory: jest.fn(),
  },
}));

const getRootDirectory = fileApi.getRootDirectory as jest.Mock;

describe("sanitizeExportOutputName", () => {
  test("replaces unsafe characters with underscores", () => {
    expect(sanitizeExportOutputName("chain/id with spaces")).toBe(
      "chain_id_with_spaces",
    );
  });

  test("truncates very long chain ids", () => {
    const longId = "a".repeat(200);
    expect(sanitizeExportOutputName(longId).length).toBe(120);
  });
});

describe("resolveOutputFileUri", () => {
  beforeEach(() => {
    getRootDirectory.mockReturnValue(Uri.file("/workspace"));
  });

  test("appends .png when extension is missing", () => {
    const uri = resolveOutputFileUri("/tmp/export", "chain-1");
    expect(uri.path).toBe("/joined/path");
  });

  test("keeps existing png extension", () => {
    const uri = resolveOutputFileUri("/tmp/export", "chain-1.png");
    expect(uri.path).toBe("/joined/path");
  });

  test("keeps existing svg extension", () => {
    const uri = resolveOutputFileUri("/tmp/export", "chain-1.svg");
    expect(uri.path).toBe("/joined/path");
  });

  test("resolves scheme-based output dir", () => {
    const uri = resolveOutputFileUri("file:///tmp/export", "chain-1.png");
    expect(uri.path).toBe("/joined/path");
  });

  test("resolves relative output dir against workspace root", () => {
    const uri = resolveOutputFileUri("exports", "chain-1");
    expect(uri.path).toBe("/joined/path");
    expect(getRootDirectory).toHaveBeenCalled();
  });
});

describe("decodeBase64", () => {
  test("decodes base64 payload to bytes", () => {
    const bytes = decodeBase64("SGVsbG8=");
    expect(Array.from(bytes)).toEqual([72, 101, 108, 108, 111]);
  });

  test("decodes multi-byte payload", () => {
    const bytes = decodeBase64("YQ==");
    expect(Array.from(bytes)).toEqual([97]);
  });
});
