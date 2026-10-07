/**
 * The folder tree on the catalog API: create, read, rename, nest, move, and the cascade that
 * deletes everything underneath.
 *
 * The cascade is the reason this file matters beyond its own assertions. The whole suite's
 * teardown is one `DELETE /v1/folders/{id}` per worker, and that call is worth exactly what the
 * cascade reaches. `specs/api/worker-folder.spec.ts` proves it reaches a chain; here it is proved
 * to reach a **nested folder** and the chains under that, which is the shape a worker folder
 * actually holds once the specs above it start making sub-folders of their own.
 *
 * Five measured shapes the cases pin:
 *
 * - `DELETE /v1/folders/{id}` answers **204**, where the chain delete answers 200.
 * - `PUT /v1/folders/{id}` with no `parentId` leaves the folder where it is. The chain update does
 *   the opposite with the same omission, so the two are not one rule.
 * - `GET /v1/folders/{id}/chains` is **recursive** — every chain beneath the folder, not the direct
 *   children — while `GET /v1/folders/{id}` answers with the direct items alone.
 * - `POST /v1/folders/search` and `POST /v1/folders/filter` answer one flat list of the matched
 *   chains and every folder above them. The search also matches folders by name and brings in the
 *   chains beneath a matched folder; the filter reads chains only.
 * - A folder row in either answer lists every child the folder holds, matched or not, so a case
 *   reads the flat list and never a folder's `items`.
 *
 * Every case builds its own sub-tree under the worker's folder, because both listings above are
 * recursive and a case reading the worker folder would read its neighbors' work.
 */
import { test, expect } from "../../support/fixtures.js";
import { tokenized } from "../../support/run.js";
import type { FolderSearchItem } from "../../support/catalog.js";

test("a folder round-trips through create, read, rename, and delete", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const created = await catalog.createFolder(tokenized(run, "folder-crud"), folder.id, "the original");

  const read = await catalog.getFolder(created.id);
  expect(read).toMatchObject({
    id: created.id,
    name: tokenized(run, "folder-crud"),
    description: "the original",
    parentId: folder.id,
  });
  // The navigation path names every folder from the root down to this one, so the worker folder
  // is in it — that is what the UI's breadcrumb reads.
  expect(read.navigationPath[folder.id]).toBe(folder.name);
  expect(read.navigationPath[created.id]).toBe(tokenized(run, "folder-crud"));

  // No parentId, and the folder stays where it is. The chain update reads the same omission as a
  // move to the root, so this is worth pinning rather than inferring from the chain rule.
  const renamed = await catalog.updateFolder(created.id, tokenized(run, "folder-renamed"), "rewritten");
  expect(renamed).toMatchObject({ name: tokenized(run, "folder-renamed"), description: "rewritten" });
  // Re-read, and the name with it: the response above is the request body echoed, so a handler that
  // never persisted the rename passes every assertion made on it alone.
  expect(await catalog.getFolder(created.id)).toMatchObject({
    name: tokenized(run, "folder-renamed"),
    description: "rewritten",
    parentId: folder.id,
  });

  const deleted = await catalog.raw("delete", `/v1/folders/${created.id}`);
  expect(deleted.status(), "the folder delete answers 204, where the chain delete answers 200").toBe(204);
  expect((await catalog.raw("get", `/v1/folders/${created.id}`)).status()).toBe(404);
});

test("folders nest, and the nested listings read the tree rather than one level", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const outer = await catalog.createFolder(tokenized(run, "nest-outer"), folder.id);
  const inner = await catalog.createFolder(tokenized(run, "nest-inner"), outer.id);
  const deep = await catalog.createChain(tokenized(run, "nest-chain"), inner.id);

  // The folder's own view carries its direct items: the sub-folder, and not the chain two levels
  // down. A reading that expected the chain here would be reading the wrong endpoint.
  const view = await catalog.getFolder(outer.id);
  expect(view.items.map((item) => ({ id: item.id, itemType: item.itemType }))).toEqual([
    { id: inner.id, itemType: "FOLDER" },
  ]);

  // The chain listing is recursive, which is what makes it useful and what makes a case reading
  // the worker folder read its neighbors.
  expect((await catalog.listNestedChains(outer.id)).map((each) => each.id)).toEqual([deep.id]);

  const elements = await catalog.listNestedElements(outer.id);
  const byId = new Map(elements.map((item) => [item.id, item.itemType]));
  expect(byId.get(outer.id), "the folder itself is in its own element listing").toBe("FOLDER");
  expect(byId.get(inner.id)).toBe("FOLDER");
  expect(byId.get(deep.id)).toBe("CHAIN");
});

test("moving a folder takes everything under it", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const from = await catalog.createFolder(tokenized(run, "move-from"), folder.id);
  const to = await catalog.createFolder(tokenized(run, "move-to"), folder.id);
  const moving = await catalog.createFolder(tokenized(run, "move-subject"), from.id);
  const chain = await catalog.createChain(tokenized(run, "move-passenger"), moving.id);

  const moved = await catalog.moveFolder(moving.id, to.id);

  expect(moved.id, "move answers with the folder itself rather than a copy").toBe(moving.id);
  expect(moved.parentId).toBe(to.id);
  expect((await catalog.getFolder(moving.id)).parentId).toBe(to.id);
  // The chain never moved and never was named, and it is now under a different tree — which is
  // the only assertion that distinguishes a move from a re-parent of the folder alone.
  expect((await catalog.listNestedChains(from.id))).toEqual([]);
  expect((await catalog.listNestedChains(to.id)).map((each) => each.id)).toEqual([chain.id]);
  expect((await catalog.getChain(chain.id)).parentId).toBe(moving.id);
});

test("deleting a folder cascades through nested folders to the chains beneath them", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  // The shape the worker folder actually has by the end of a run, rather than the one-level tree
  // worker-folder.spec.ts proves: a sub-folder, a chain in it, and a chain beside it.
  const outer = await catalog.createFolder(tokenized(run, "cascade-outer"), folder.id);
  const inner = await catalog.createFolder(tokenized(run, "cascade-inner"), outer.id);
  const shallow = await catalog.createChain(tokenized(run, "cascade-shallow"), outer.id);
  const deep = await catalog.createChain(tokenized(run, "cascade-deep"), inner.id);
  // A neighbor outside the tree, so the case proves the cascade stops where it should.
  const bystander = await catalog.createChain(tokenized(run, "cascade-bystander"), folder.id);

  await catalog.deleteFolder(outer.id);

  for (const id of [outer.id, inner.id]) {
    expect((await catalog.raw("get", `/v1/folders/${id}`)).status()).toBe(404);
  }
  for (const id of [shallow.id, deep.id]) {
    expect((await catalog.raw("get", `/v1/chains/${id}`)).status()).toBe(404);
  }
  expect((await catalog.getChain(bystander.id)).id).toBe(bystander.id);
});

/** The rows of a search or filter answer as `itemType:id`, sorted, so two answers compare as sets. */
function rowsOf(items: FolderSearchItem[]): string[] {
  return items.map((item) => `${item.itemType}:${item.id}`).sort();
}

test("the folder search finds chains and folders by name, with every folder above them", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  // A marker only this case's names carry, so the answer is this case's rows and nothing else.
  const marker = tokenized(run, "search-marked");
  const tree = await catalog.createFolder(tokenized(run, "search-tree"), folder.id);
  const markedFolder = await catalog.createFolder(`${marker}-folder`, tree.id);
  const underMarkedFolder = await catalog.createChain(tokenized(run, "search-plain"), markedFolder.id);
  const markedChain = await catalog.createChain(`${marker}-chain`, tree.id);
  await catalog.createChain(tokenized(run, "search-decoy"), tree.id);

  const found = await catalog.searchFolders(marker);

  // A matched folder brings in the chains beneath it, and every match brings in its ancestors, up
  // to the worker folder at the root.
  expect(rowsOf(found)).toEqual(
    [
      `CHAIN:${markedChain.id}`,
      `CHAIN:${underMarkedFolder.id}`,
      `FOLDER:${markedFolder.id}`,
      `FOLDER:${tree.id}`,
      `FOLDER:${folder.id}`,
    ].sort(),
  );
  const parents = new Map(found.map((item) => [item.id, item.parentId]));
  expect(parents.get(markedChain.id)).toBe(tree.id);
  expect(parents.get(underMarkedFolder.id)).toBe(markedFolder.id);
  expect(parents.get(folder.id), "the worker folder sits at the root").toBeUndefined();
});

test("the folder filter reads chains only, and answers the folders above the chains it matched", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const marker = tokenized(run, "filter-marked");
  const tree = await catalog.createFolder(tokenized(run, "filter-tree"), folder.id);
  // A folder carrying the marker and a chain beneath it that does not: the filter matches neither.
  const markedFolder = await catalog.createFolder(`${marker}-folder`, tree.id);
  await catalog.createChain(tokenized(run, "filter-plain"), markedFolder.id);
  const markedChain = await catalog.createChain(`${marker}-chain`, tree.id);

  const found = await catalog.filterFolders([{ column: "NAME", condition: "CONTAINS", value: marker }]);

  expect(rowsOf(found)).toEqual(
    [`CHAIN:${markedChain.id}`, `FOLDER:${tree.id}`, `FOLDER:${folder.id}`].sort(),
  );
});
