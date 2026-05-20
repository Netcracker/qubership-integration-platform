import { mergeUpdatedChainInFolderItems } from "../../../src/pages/chains/mergeUpdatedChainInFolderItems";
import {
  CatalogItemType,
  Chain,
  ChainItem,
  FolderItem,
} from "../../../src/api/apiTypes";

describe("mergeUpdatedChainInFolderItems", () => {
  const chainRow: ChainItem = {
    id: "c1",
    itemType: CatalogItemType.CHAIN,
    name: "Old",
    description: "",
    labels: [],
  };

  const folderRow: FolderItem = {
    id: "f1",
    itemType: CatalogItemType.FOLDER,
    name: "Folder",
    description: "",
  };

  const updated: Chain = {
    id: "c1",
    name: "New",
    description: "d",
    labels: [{ name: "x", technical: false }],
    businessDescription: "",
    assumptions: "",
    outOfScope: "",
    navigationPath: [],
    elements: [],
    dependencies: [],
    deployments: [],
    defaultSwimlaneId: "s1",
    reuseSwimlaneId: "s2",
    unsavedChanges: false,
    containsDeprecatedContainers: false,
    containsDeprecatedElements: false,
    containsUnsupportedElements: false,
  } as Chain;

  it("replaces matching chain row with merged chain and preserves itemType", () => {
    const result = mergeUpdatedChainInFolderItems(
      [folderRow, chainRow],
      "c1",
      updated,
    );
    expect(result[0]).toBe(folderRow);
    expect(result[1]).toEqual({
      ...chainRow,
      ...updated,
      itemType: CatalogItemType.CHAIN,
    });
  });

  it("leaves list unchanged when chain id does not match", () => {
    const items = [chainRow];
    const result = mergeUpdatedChainInFolderItems(items, "other", updated);
    expect(result).toEqual(items);
  });

  it("does not replace folder with same id", () => {
    const folderAsId: FolderItem = {
      id: "c1",
      itemType: CatalogItemType.FOLDER,
      name: "Not a chain",
      description: "",
    };
    const result = mergeUpdatedChainInFolderItems([folderAsId], "c1", updated);
    expect(result[0]).toBe(folderAsId);
  });
});
