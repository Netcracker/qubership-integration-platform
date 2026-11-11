import { Uri } from "vscode";
import { deleteMaskedFields } from "../../../src/web/response/chainApiModify";
import { getMainChain } from "../../../src/web/response/chainApiRead";
import { fileApi } from "../../../src/web/response/file";

jest.mock("../../../src/web/response/chainApiRead", () => ({
  getMainChain: jest.fn(),
}));

jest.mock("../../../src/web/response/file", () => ({
  fileApi: {
    writeMainChain: jest.fn(),
  },
}));

const getMainChainMock = getMainChain as jest.Mock;
const writeMainChainMock = fileApi.writeMainChain as jest.Mock;

describe("deleteMaskedFields", () => {
  const fileUri = { path: "/workspace/test.chain.cip.yaml" } as Uri;
  const chainId = "chain-1";

  const writtenMaskedFieldIds = () =>
    writeMainChainMock.mock.calls[0][1].content.maskedFields.map(
      (mf: { id: string }) => mf.id,
    );

  beforeEach(() => {
    jest.clearAllMocks();
    getMainChainMock.mockResolvedValue({
      id: chainId,
      content: {
        maskedFields: [
          { id: "mf-1", name: "password" },
          { id: "mf-2", name: "token" },
        ],
      },
    });
  });

  it("removes the first masked field from the chain file", async () => {
    await deleteMaskedFields(fileUri, chainId, ["mf-1"]);

    expect(writtenMaskedFieldIds()).toEqual(["mf-2"]);
  });

  it("removes a masked field that is not the first", async () => {
    await deleteMaskedFields(fileUri, chainId, ["mf-2"]);

    expect(writtenMaskedFieldIds()).toEqual(["mf-1"]);
  });

  it("keeps every masked field when the id matches none", async () => {
    await deleteMaskedFields(fileUri, chainId, ["missing"]);

    expect(writtenMaskedFieldIds()).toEqual(["mf-1", "mf-2"]);
  });
});
