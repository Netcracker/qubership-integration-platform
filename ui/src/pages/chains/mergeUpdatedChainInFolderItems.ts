import {
  CatalogItemType,
  Chain,
  ChainItem,
  FolderItem,
} from "../../api/apiTypes.ts";

export function mergeUpdatedChainInFolderItems(
  folderItems: (FolderItem | ChainItem)[],
  chainId: string,
  chain: Chain,
): (FolderItem | ChainItem)[] {
  return folderItems.map((i) =>
    i.id === chainId && i.itemType === CatalogItemType.CHAIN
      ? { ...i, ...chain, itemType: CatalogItemType.CHAIN }
      : i,
  );
}
