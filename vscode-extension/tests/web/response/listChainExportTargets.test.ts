import { Uri } from "vscode";
import { listChainExportTargets } from "../../../src/web/response/chainApiRead";
import { fileApi } from "../../../src/web/response/file";

jest.mock("../../../src/web/response/file", () => ({
  fileApi: {
    findFiles: jest.fn(),
    parseFile: jest.fn(),
  },
}));

jest.mock("../../../src/web/response/file/fileExtensions", () => ({
  getExtensionsForUri: jest.fn(() => ({ chain: "**/*.chain.qip.yaml" })),
}));

const findFiles = fileApi.findFiles as jest.Mock;
const parseFile = fileApi.parseFile as jest.Mock;

describe("listChainExportTargets", () => {
  beforeEach(() => {
    findFiles.mockReset();
    parseFile.mockReset();
  });

  test("returns sorted targets from fileApi discovery", async () => {
    const first = Uri.file("/workspace/chains/b.chain.qip.yaml");
    const second = Uri.file("/workspace/chains/a.chain.qip.yaml");
    findFiles.mockResolvedValue([first, second]);
    parseFile.mockImplementation(async (uri: Uri) => ({
      id: uri.fsPath.includes("/a.") ? "alpha" : "beta",
    }));

    const targets = await listChainExportTargets();

    expect(findFiles).toHaveBeenCalledWith("**/*.chain.qip.yaml");
    expect(targets).toEqual([
      {
        chainId: "alpha",
        filePath: second.toString(),
        outputName: "alpha",
      },
      {
        chainId: "beta",
        filePath: first.toString(),
        outputName: "beta",
      },
    ]);
  });

  test("skips chain files without id", async () => {
    const fileUri = Uri.file("/workspace/chains/invalid.chain.qip.yaml");
    findFiles.mockResolvedValue([fileUri]);
    parseFile.mockResolvedValue({ name: "no-id" });

    const targets = await listChainExportTargets();

    expect(targets).toEqual([]);
  });

  test("skips chain files with non-string id", async () => {
    const fileUri = Uri.file("/workspace/chains/invalid.chain.qip.yaml");
    findFiles.mockResolvedValue([fileUri]);
    parseFile.mockResolvedValue({ id: 42 });

    const targets = await listChainExportTargets();

    expect(targets).toEqual([]);
  });
});
