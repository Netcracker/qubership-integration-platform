import { Uri } from "vscode";
import { getChain } from "../../../src/web/response/chainApiRead";
import { fileApi } from "../../../src/web/response/file";

jest.mock("../../../src/web/response/file", () => ({
  fileApi: {
    parseFile: jest.fn(),
  },
}));

const parseFile = fileApi.parseFile as jest.Mock;

describe("getChain", () => {
  const workspaceUri = Uri.file("/workspace/current.chain.qip.yaml");
  const explicitUri = Uri.file("/workspace/chains/chain-1.chain.qip.yaml");

  beforeEach(() => {
    jest.clearAllMocks();
  });

  test("throws when parsed chain id does not match requested id", async () => {
    parseFile.mockResolvedValueOnce({ id: "other-chain" });

    await expect(getChain(workspaceUri, "chain-1", explicitUri)).rejects.toThrow(
      "ChainId mismatch",
    );
    expect(parseFile).toHaveBeenCalledWith(explicitUri);
  });
});
