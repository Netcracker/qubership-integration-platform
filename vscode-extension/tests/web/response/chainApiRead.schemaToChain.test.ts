import { Uri } from "vscode";
import { schemaToChain } from "../../../src/web/response/chainApiRead";
import type { Chain as ChainSchema } from "@netcracker/qip-schemas";

jest.mock("../../../src/web/response/file", () => ({
  fileApi: {
    parseFile: jest.fn(),
  },
}));

describe("schemaToChain", () => {
  const fileUri = Uri.file("/workspace/chain-1.chain.qip.yaml");

  const baseChain = (overrides: Partial<ChainSchema> = {}): ChainSchema =>
    ({
      $schema: "http://qubership.org/schemas/product/qip/chain.schema.yaml",
      id: "chain-1",
      name: "Chain 1",
      content: { dependencies: [] },
      ...overrides,
    }) as ChainSchema;

  it("should build navigationPath from metaInfo.group segments", async () => {
    const chain = baseChain({ metaInfo: { group: "a/b/c" } });

    const result = await schemaToChain(fileUri, chain, false);

    expect(result.navigationPath).toEqual([
      ["a", "a"],
      ["b", "b"],
      ["c", "c"],
    ]);
  });

  it("should return an empty navigationPath when metaInfo is absent", async () => {
    const result = await schemaToChain(fileUri, baseChain(), false);

    expect(result.navigationPath).toEqual([]);
  });

  it("should return an empty navigationPath when the group is empty", async () => {
    const chain = baseChain({ metaInfo: { group: "" } });

    const result = await schemaToChain(fileUri, chain, false);

    expect(result.navigationPath).toEqual([]);
  });

  it("should skip empty segments in the group path", async () => {
    const chain = baseChain({ metaInfo: { group: "a//b" } });

    const result = await schemaToChain(fileUri, chain, false);

    expect(result.navigationPath).toEqual([
      ["a", "a"],
      ["b", "b"],
    ]);
  });

  it("should trim leading and trailing slashes in the group", async () => {
    const chain = baseChain({ metaInfo: { group: "/a/b/" } });

    const result = await schemaToChain(fileUri, chain, false);

    expect(result.navigationPath).toEqual([
      ["a", "a"],
      ["b", "b"],
    ]);
  });

  it("should ignore the deprecated content.folder structure", async () => {
    const chain = {
      ...baseChain(),
      content: {
        dependencies: [],
        folder: { name: "a", subfolder: { name: "b" } },
      },
    } as unknown as ChainSchema;

    const result = await schemaToChain(fileUri, chain, false);

    expect(result.navigationPath).toEqual([]);
  });
});
