import { Uri } from "vscode";
import { changeFolder } from "../../../src/web/response/chainApiModify";
import { getMainChain, schemaToChain } from "../../../src/web/response/chainApiRead";
import { fileApi } from "../../../src/web/response/file";

// Keep the real schemaToChain, mock only the file access getMainChain needs.
jest.mock("../../../src/web/response/chainApiRead", () => ({
  ...jest.requireActual("../../../src/web/response/chainApiRead"),
  getMainChain: jest.fn(),
}));

jest.mock("../../../src/web/response/file", () => ({
  fileApi: {
    writeMainChain: jest.fn(),
    parseFile: jest.fn(),
  },
}));

const getMainChainMock = getMainChain as jest.Mock;
const writeMainChainMock = fileApi.writeMainChain as jest.Mock;

// changeFolder writes metaInfo.group; schemaToChain reads it back into navigationPath.
// These tests pin the two functions as each other's inverse for the group field.
describe("group round-trip: changeFolder -> schemaToChain", () => {
  const fileUri = Uri.file("/workspace/chain-1.chain.qip.yaml");

  const newChain = () => ({
    $schema: "http://qubership.org/schemas/product/qip/chain.schema.yaml",
    id: "chain-1",
    name: "Chain 1",
    content: { dependencies: [] },
  });

  beforeEach(() => {
    jest.clearAllMocks();
    writeMainChainMock.mockImplementation((_uri, chain) => chain);
  });

  it("reads back the same path that was written", async () => {
    const chain = newChain();
    getMainChainMock.mockResolvedValue(chain);

    await changeFolder(fileUri, "chain-1", "a/b/c");
    const result = await schemaToChain(fileUri, chain as any, false);

    expect(result.navigationPath).toEqual([
      ["a", "a"],
      ["b", "b"],
      ["c", "c"],
    ]);
  });

  it("reflects forbidden-character sanitization in the read-back path", async () => {
    const chain = newChain();
    getMainChainMock.mockResolvedValue(chain);

    await changeFolder(fileUri, "chain-1", "a:b/c");
    const result = await schemaToChain(fileUri, chain as any, false);

    expect((chain as any).metaInfo.group).toBe("a-b/c");
    expect(result.navigationPath).toEqual([
      ["a-b", "a-b"],
      ["c", "c"],
    ]);
  });
});
