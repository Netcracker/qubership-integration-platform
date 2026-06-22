import { Uri } from "vscode";
import { changeFolder } from "../../../src/web/response/chainApiModify";
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

describe("changeFolder", () => {
  const fileUri = { path: "/workspace/test.chain.qip.yaml" } as Uri;
  const chainId = "chain-1";

  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("should throw when chainId does not match", async () => {
    getMainChainMock.mockResolvedValue({ id: "other-chain", content: {} });

    await expect(changeFolder(fileUri, chainId, "a/b")).rejects.toThrow(
      "ChainId mismatch",
    );
    expect(writeMainChainMock).not.toHaveBeenCalled();
  });

  it("should set metaInfo.group from a slash-separated path", async () => {
    const chain = { id: chainId, content: {} };
    getMainChainMock.mockResolvedValue(chain);

    await changeFolder(fileUri, chainId, "a/b/c");

    expect((chain as any).metaInfo).toEqual({ group: "a/b/c" });
    expect(writeMainChainMock).toHaveBeenCalledWith(fileUri, chain);
  });

  it("should trim surrounding slashes and skip empty segments", async () => {
    const chain = { id: chainId, content: {} };
    getMainChainMock.mockResolvedValue(chain);

    await changeFolder(fileUri, chainId, "/a//b/");

    expect((chain as any).metaInfo).toEqual({ group: "a/b" });
  });

  it("should replace forbidden characters in each segment with '-'", async () => {
    const chain = { id: chainId, content: {} };
    getMainChainMock.mockResolvedValue(chain);

    await changeFolder(fileUri, chainId, "a:b/c*d");

    expect((chain as any).metaInfo).toEqual({ group: "a-b/c-d" });
  });

  it("should preserve other metaInfo fields when the path is empty", async () => {
    const chain = {
      id: chainId,
      content: {},
      metaInfo: { group: "old", application: "QIP" },
    };
    getMainChainMock.mockResolvedValue(chain);

    await changeFolder(fileUri, chainId, "");

    expect((chain as any).metaInfo).toEqual({ application: "QIP" });
    expect(writeMainChainMock).toHaveBeenCalledWith(fileUri, chain);
  });

  it("should drop metaInfo entirely when the path is empty and no other fields remain", async () => {
    const chain = { id: chainId, content: {}, metaInfo: { group: "old" } };
    getMainChainMock.mockResolvedValue(chain);

    await changeFolder(fileUri, chainId, "");

    expect((chain as any).metaInfo).toBeUndefined();
  });

  it("should drop the deprecated nested content.folder structure", async () => {
    const chain = {
      id: chainId,
      content: { folder: { name: "a", subfolder: { name: "b" } } },
    };
    getMainChainMock.mockResolvedValue(chain);

    await changeFolder(fileUri, chainId, "a/b");

    expect((chain as any).content.folder).toBeUndefined();
    expect((chain as any).metaInfo).toEqual({ group: "a/b" });
  });
});
