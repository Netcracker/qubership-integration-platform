import type React from "react";
import { api } from "../../api/api.ts";
import { CatalogItemType, ChainItem, FolderItem } from "../../api/apiTypes.ts";
import type { EntityFilterModel } from "../../components/table/filter/filterTypes.ts";

export type FolderContent = (FolderItem | ChainItem)[];

export type SubtreeLoadContext = {
  folderItems: FolderContent;
  knownLoaded: Set<string>;
  visited: Set<string>;
  filters: EntityFilterModel[];
  searchString: string;
  notifyLoadFailed: (error: unknown) => void;
};

export function collectDescendantFolderIds(
  rootId: string,
  items: (FolderItem | ChainItem)[],
): React.Key[] {
  const ids: React.Key[] = [rootId];
  const visited = new Set<string>([rootId]);
  const queue: string[] = [rootId];
  let head = 0;
  while (head < queue.length) {
    const current = queue[head++];
    for (const item of items) {
      if (
        item.parentId === current &&
        item.itemType === CatalogItemType.FOLDER &&
        !visited.has(item.id)
      ) {
        visited.add(item.id);
        ids.push(item.id);
        queue.push(item.id);
      }
    }
  }
  return ids;
}

export function findUnvisitedChildFolderIds(
  parentId: string,
  items: FolderContent,
  visited: Set<string>,
): string[] {
  return items
    .filter(
      (item) =>
        item.parentId === parentId &&
        item.itemType === CatalogItemType.FOLDER &&
        !visited.has(item.id),
    )
    .map((item) => item.id);
}

export function findUnvisitedFolderIds(
  items: FolderContent,
  visited: Set<string>,
): string[] {
  return items
    .filter(
      (item) =>
        item.itemType === CatalogItemType.FOLDER && !visited.has(item.id),
    )
    .map((item) => item.id);
}

export async function fetchFolderContent(
  folderId: string,
  filters: EntityFilterModel[],
  searchString: string,
  notifyLoadFailed: (error: unknown) => void,
): Promise<FolderContent | undefined> {
  try {
    return await api.listFolder({ folderId, filters, searchString });
  } catch (error) {
    notifyLoadFailed(error);
    return undefined;
  }
}

export async function collectSubtreeBatches(
  folderId: string,
  ctx: SubtreeLoadContext,
): Promise<FolderContent[]> {
  if (ctx.visited.has(folderId)) {
    return [];
  }
  ctx.visited.add(folderId);
  if (ctx.knownLoaded.has(folderId)) {
    const childIds = findUnvisitedChildFolderIds(
      folderId,
      ctx.folderItems,
      ctx.visited,
    );
    const nested = await Promise.all(
      childIds.map((id) => collectSubtreeBatches(id, ctx)),
    );
    return nested.flat();
  }
  const response = await fetchFolderContent(
    folderId,
    ctx.filters,
    ctx.searchString,
    ctx.notifyLoadFailed,
  );
  if (!response) {
    return [];
  }
  ctx.knownLoaded.add(folderId);
  const childIds = findUnvisitedFolderIds(response, ctx.visited);
  const nested = await Promise.all(
    childIds.map((id) => collectSubtreeBatches(id, ctx)),
  );
  return [response, ...nested.flat()];
}
