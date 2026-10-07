/**
 * @jest-environment jsdom
 */

import { beforeEach, describe, expect, it, jest } from "@jest/globals";
import { CatalogItemType } from "../../../src/api/apiTypes";
import type { ChainItem, FolderItem } from "../../../src/api/apiTypes";

const mockListFolder =
  jest.fn<(...args: unknown[]) => Promise<(FolderItem | ChainItem)[]>>();

jest.mock("../../../src/api/api", () => ({
  api: {
    listFolder: (...args: unknown[]) => mockListFolder(...args),
  },
}));

import {
  collectDescendantFolderIds,
  collectSubtreeBatches,
  fetchFolderContent,
  findUnvisitedChildFolderIds,
  findUnvisitedFolderIds,
} from "../../../src/pages/chains/chainSubtree";
import type { SubtreeLoadContext } from "../../../src/pages/chains/chainSubtree";

const folder = (id: string, parentId?: string): FolderItem => ({
  id,
  name: id,
  description: id,
  parentId,
  itemType: CatalogItemType.FOLDER,
});

const chain = (id: string, parentId?: string): ChainItem => ({
  id,
  name: id,
  description: id,
  parentId,
  itemType: CatalogItemType.CHAIN,
  labels: [],
});

function makeContext(
  overrides: Partial<SubtreeLoadContext> = {},
): SubtreeLoadContext {
  return {
    folderItems: [],
    knownLoaded: new Set<string>(),
    visited: new Set<string>(),
    filters: [],
    searchString: "",
    notifyLoadFailed: () => {},
    ...overrides,
  };
}

describe("collectDescendantFolderIds", () => {
  it("should return only the root id when it has no folder children", () => {
    expect(collectDescendantFolderIds("root", [])).toEqual(["root"]);
  });

  it("should collect nested folder ids while ignoring chains", () => {
    const items = [
      folder("root"),
      folder("child", "root"),
      chain("chain-1", "root"),
      folder("grandchild", "child"),
      chain("chain-2", "child"),
      folder("other"),
    ];
    expect(collectDescendantFolderIds("root", items)).toEqual([
      "root",
      "child",
      "grandchild",
    ]);
  });

  it("should not loop forever when folders reference each other", () => {
    const items = [folder("a", "b"), folder("b", "a")];
    expect(collectDescendantFolderIds("a", items)).toEqual(["a", "b"]);
  });
});

describe("findUnvisitedChildFolderIds", () => {
  it("should return child folder ids of the parent excluding visited ones", () => {
    const items = [
      folder("child-1", "root"),
      folder("child-2", "root"),
      folder("elsewhere", "other"),
    ];
    expect(
      findUnvisitedChildFolderIds("root", items, new Set(["child-2"])),
    ).toEqual(["child-1"]);
  });

  it("should ignore chain items under the same parent", () => {
    const items = [folder("child", "root"), chain("chain-1", "root")];
    expect(findUnvisitedChildFolderIds("root", items, new Set())).toEqual([
      "child",
    ]);
  });
});

describe("findUnvisitedFolderIds", () => {
  it("should return all unvisited folder ids", () => {
    const items = [folder("a"), folder("b"), folder("c")];
    expect(findUnvisitedFolderIds(items, new Set(["b"]))).toEqual(["a", "c"]);
  });

  it("should ignore chain items", () => {
    const items = [folder("a"), chain("chain-1")];
    expect(findUnvisitedFolderIds(items, new Set())).toEqual(["a"]);
  });
});

describe("fetchFolderContent", () => {
  beforeEach(() => {
    mockListFolder.mockReset();
  });

  it("should return folder content when listFolder resolves", async () => {
    const content = [folder("child", "root")];
    mockListFolder.mockResolvedValueOnce(content);

    await expect(fetchFolderContent("root", [], "", () => {})).resolves.toBe(
      content,
    );
    expect(mockListFolder).toHaveBeenCalledWith({
      folderId: "root",
      filters: [],
      searchString: "",
    });
  });

  it("should notify and return undefined when listFolder rejects", async () => {
    const failure = new Error("denied");
    mockListFolder.mockRejectedValueOnce(failure);
    const notifyLoadFailed = jest.fn();

    await expect(
      fetchFolderContent("root", [], "", notifyLoadFailed),
    ).resolves.toBeUndefined();
    expect(notifyLoadFailed).toHaveBeenCalledWith(failure);
  });
});

describe("collectSubtreeBatches", () => {
  beforeEach(() => {
    mockListFolder.mockReset();
  });

  it("should return empty array when the folder was already visited", async () => {
    const ctx = makeContext({ visited: new Set(["root"]) });

    await expect(collectSubtreeBatches("root", ctx)).resolves.toEqual([]);
    expect(mockListFolder).not.toHaveBeenCalled();
  });

  it("should walk cached children without fetching when everything is loaded", async () => {
    const ctx = makeContext({
      folderItems: [
        folder("root"),
        folder("child", "root"),
        chain("chain-1", "child"),
      ],
      knownLoaded: new Set(["root", "child"]),
    });

    await expect(collectSubtreeBatches("root", ctx)).resolves.toEqual([]);
    expect(mockListFolder).not.toHaveBeenCalled();
    expect(ctx.visited).toEqual(new Set(["root", "child"]));
  });

  it("should fetch uncached folders and recurse into nested ones", async () => {
    const rootContent = [folder("child", "root"), chain("chain-1", "root")];
    const childContent = [chain("chain-2", "child")];
    mockListFolder.mockImplementationOnce(async () => rootContent);
    mockListFolder.mockImplementationOnce(async () => childContent);
    const ctx = makeContext();

    const batches = await collectSubtreeBatches("root", ctx);

    expect(batches).toEqual([rootContent, childContent]);
    expect(mockListFolder).toHaveBeenCalledTimes(2);
    expect(mockListFolder).toHaveBeenNthCalledWith(1, {
      folderId: "root",
      filters: [],
      searchString: "",
    });
    expect(mockListFolder).toHaveBeenNthCalledWith(2, {
      folderId: "child",
      filters: [],
      searchString: "",
    });
    expect(ctx.knownLoaded).toEqual(new Set(["root", "child"]));
  });

  it("should return empty array and notify when the fetch fails", async () => {
    const failure = new Error("denied");
    mockListFolder.mockRejectedValueOnce(failure);
    const notifyLoadFailed = jest.fn();
    const ctx = makeContext({ notifyLoadFailed });

    await expect(collectSubtreeBatches("root", ctx)).resolves.toEqual([]);
    expect(notifyLoadFailed).toHaveBeenCalledWith(failure);
    expect(ctx.knownLoaded.has("root")).toBe(false);
  });
});
