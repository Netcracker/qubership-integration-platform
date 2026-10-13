/**
 * The chain lifecycle on the catalog API: create, read, update, delete, and the three ways a chain
 * is duplicated or relocated.
 *
 * Every case works inside a folder of its own, created under the worker's folder. That is not
 * ceremony. `GET /v1/folders/{id}/chains` answers with every chain **beneath** the folder, so a
 * case that asserted over the worker folder would see the chains of every other case sharing the
 * worker — and would pass or fail depending on which of them happened to be mid-flight. The same
 * rule keeps the listing assertions filtered to ids this case created: the global chain list is a
 * shared stack's list, and its length is nobody's to assert.
 *
 * Seven measured facts the cases pin, each one a shape a reader would otherwise have to guess:
 *
 * - `DELETE /v1/chains/{id}` answers **200**, not 204, and **404** the second time.
 * - `POST /v1/chains/bulk-delete` answers **204** whatever it was given — an id that does not exist
 *   included, because the controller logs the failure per id and returns regardless.
 * - `POST /v1/chains/{id}/duplicate` names the copy `<name> (1)` and leaves it in the same folder.
 * - `PUT /v1/chains/{id}` takes `parentId` as part of the chain's own state, so an update that
 *   omits it moves the chain to the root.
 * - `GET /v1/chains/find-by-element/{id}` answers **404 with an empty body** for an unknown element,
 *   and `GET /v1/chains/names` leaves an unknown id out of its map.
 * - `POST /v1/chains/diff` pairs a snapshot's elements with the chain's by the id they were copied
 *   from. An element with no pair carries every field it has in `onlyOnLeft` or `onlyOnRight`.
 * - `PATCH /v1/chains/{id}/migrate` keeps a migrated container's id and rebuilds its branches under
 *   new ids and the successor types.
 */
import { test, expect } from "../../support/fixtures.js";
import type { Catalog } from "../../support/catalog.js";
import { tokenized } from "../../support/run.js";
import { ABSENT_UUID } from "../../support/absent.js";

/** A folder of this case's own, under the worker's, so the nested listings see only its chains. */
async function caseFolder(
  catalog: Catalog,
  run: string,
  parentId: string,
  what: string,
): Promise<string> {
  const folder = await catalog.createFolder(tokenized(run, `chains-${what}`), parentId);
  return folder.id;
}

test("a chain round-trips through create, read, update, and delete", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const parent = await caseFolder(catalog, run, folder.id, "crud");
  const created = await catalog.createChain(tokenized(run, "crud"), parent, "the original");

  const read = await catalog.getChain(created.id);
  expect(read.id).toBe(created.id);
  expect(read.name).toBe(tokenized(run, "crud"));
  expect(read).toMatchObject({ description: "the original", parentId: parent });

  // HEAD is the cheap existence check the UI uses, and it answers before and after the delete.
  expect((await catalog.raw("head", `/v1/chains/${created.id}`)).status()).toBe(200);

  // parentId is part of the chain's own state here: omitting it on an update moves the chain to
  // the root, so a rename has to name the folder the chain is already in.
  const updated = await catalog.updateChain(created.id, tokenized(run, "crud-renamed"), "rewritten", parent);
  expect(updated.id).toBe(created.id);
  const reread = await catalog.getChain(created.id);
  expect(reread).toMatchObject({
    name: tokenized(run, "crud-renamed"),
    description: "rewritten",
    parentId: parent,
  });

  const deleted = await catalog.raw("delete", `/v1/chains/${created.id}`);
  expect(deleted.status(), "the chain delete answers 200, not 204").toBe(200);
  expect((await catalog.raw("get", `/v1/chains/${created.id}`)).status()).toBe(404);
  expect((await catalog.raw("head", `/v1/chains/${created.id}`)).status()).toBe(404);
  // Deleting a chain that is already gone is a 404 rather than an idempotent success.
  expect((await catalog.raw("delete", `/v1/chains/${created.id}`)).status()).toBe(404);
});

test("bulk-delete removes the chains it names and tolerates one it cannot find", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const parent = await caseFolder(catalog, run, folder.id, "bulk");
  const doomed = [
    await catalog.createChain(tokenized(run, "bulk-1"), parent),
    await catalog.createChain(tokenized(run, "bulk-2"), parent),
  ];
  const survivor = await catalog.createChain(tokenized(run, "bulk-survivor"), parent);

  const response = await catalog.raw("post", "/v1/chains/bulk-delete", [
    ...doomed.map((chain) => chain.id),
    // The controller catches per id and returns anyway, so an unknown id is not a failed request.
    ABSENT_UUID,
  ]);
  expect(response.status(), "bulk-delete answers 204 whatever it was given").toBe(204);

  for (const chain of doomed) {
    expect((await catalog.raw("get", `/v1/chains/${chain.id}`)).status()).toBe(404);
  }
  expect((await catalog.getChain(survivor.id)).id).toBe(survivor.id);

  // Asserted over this case's own folder rather than over the global chain list, whose length
  // belongs to whatever else is running on this stack.
  const left = await catalog.listNestedChains(parent);
  expect(left.map((chain) => chain.id)).toEqual([survivor.id]);
});

test("the global chain listing carries this case's chain, and only its own row is asserted", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const parent = await caseFolder(catalog, run, folder.id, "listing");
  const created = await catalog.createChain(tokenized(run, "listed"), parent);

  const rows = (await catalog.listChains()).filter((chain) => chain.id === created.id);
  expect(rows).toHaveLength(1);
  expect(rows[0].name).toBe(tokenized(run, "listed"));
});

test("duplicate copies the chain beside itself, elements and all", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const parent = await caseFolder(catalog, run, folder.id, "duplicate");
  const original = await catalog.createChain(tokenized(run, "duplicate"), parent);
  const element = await catalog.createElement(original.id, "header-modification");

  const copy = await catalog.duplicateChain(original.id);

  expect(copy.id).not.toBe(original.id);
  expect(copy.name, "the duplicate is named after the original").toBe(`${original.name} (1)`);
  expect(copy.parentId, "a duplicate stays in the folder it was made from").toBe(parent);

  // The copy carries the elements, under ids of its own — a shallow copy would answer an empty
  // array here and still pass every assertion about the chain itself.
  const copied = await catalog.listChainElements(copy.id);
  expect(copied.map((each) => each.type)).toEqual(["header-modification"]);
  expect(copied[0].id).not.toBe(element.id);

  expect((await catalog.listNestedChains(parent)).map((each) => each.id).sort()).toEqual(
    [original.id, copy.id].sort(),
  );
});

test("copy places the chain in the target folder and leaves the original where it was", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const source = await caseFolder(catalog, run, folder.id, "copy-source");
  const target = await caseFolder(catalog, run, folder.id, "copy-target");
  const original = await catalog.createChain(tokenized(run, "copy"), source);
  await catalog.createElement(original.id, "header-modification");

  const copy = await catalog.copyChain(original.id, target);

  expect(copy.id).not.toBe(original.id);
  expect(copy.parentId).toBe(target);
  // A copy keeps the name: it is the folder that separates the two, not a suffix.
  expect(copy.name).toBe(original.name);
  expect((await catalog.listChainElements(copy.id)).map((each) => each.type)).toEqual([
    "header-modification",
  ]);

  expect((await catalog.listNestedChains(source)).map((each) => each.id)).toEqual([original.id]);
  expect((await catalog.listNestedChains(target)).map((each) => each.id)).toEqual([copy.id]);
});

test("move relocates the chain and leaves nothing in the folder it came from", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const source = await caseFolder(catalog, run, folder.id, "move-source");
  const target = await caseFolder(catalog, run, folder.id, "move-target");
  const chain = await catalog.createChain(tokenized(run, "move"), source);

  const moved = await catalog.moveChain(chain.id, target);

  expect(moved.id, "move answers with the chain itself rather than a copy").toBe(chain.id);
  expect(moved.parentId).toBe(target);
  expect((await catalog.getChain(chain.id)).parentId).toBe(target);
  expect((await catalog.listNestedChains(source))).toEqual([]);
  expect((await catalog.listNestedChains(target)).map((each) => each.id)).toEqual([chain.id]);

  // And back, so the chain ends inside the worker folder and the cascade removes it. A chain moved
  // to the root would outlive the folder and be left for the run-token sweep, which is a slower
  // and less obvious way to clean up after a case that did not have to make a mess.
  await catalog.moveChain(chain.id, source);
  expect((await catalog.getChain(chain.id)).parentId).toBe(source);
});

test("a chain is found by one of its elements, named by id, and counted in the total", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const parent = await caseFolder(catalog, run, folder.id, "lookup");
  const holder = await catalog.createChain(tokenized(run, "lookup-holder"), parent);
  const neighbor = await catalog.createChain(tokenized(run, "lookup-neighbor"), parent);
  const element = await catalog.createElement(holder.id, "script");

  expect(await catalog.chainOfElement(element.id)).toMatchObject({
    id: holder.id,
    name: holder.name,
    parentId: parent,
  });
  const unknown = await catalog.raw("get", `/v1/chains/find-by-element/${ABSENT_UUID}`);
  expect(unknown.status()).toBe(404);
  expect(await unknown.text(), "the 404 carries no error body").toBe("");

  // An id the catalog does not hold is left out of the map rather than refused.
  expect(await catalog.chainNames([holder.id, neighbor.id, ABSENT_UUID])).toEqual({
    [holder.id]: holder.name,
    [neighbor.id]: neighbor.name,
  });

  // The total counts every chain on the stack, so only a lower bound is this case's to assert.
  const total = await catalog.chainsCount();
  expect(Number.isInteger(total)).toBe(true);
  expect(total).toBeGreaterThanOrEqual(2);
});

test("the diff pairs each element of a snapshot with the chain's current one and names what changed", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const parent = await caseFolder(catalog, run, folder.id, "diff");
  const chain = await catalog.createChain(tokenized(run, "diff"), parent);
  const kept = await catalog.createElement(chain.id, "script");
  const snapshot = await catalog.createSnapshot(chain.id);

  await catalog.patchElementProperties(chain.id, kept.id, {}, "renamed");
  const added = await catalog.createElement(chain.id, "log-record");

  const difference = await catalog.compareChains({
    leftChainId: chain.id,
    leftSnapshotId: snapshot.id,
    rightChainId: chain.id,
  });

  expect(difference.leftEntity).toMatchObject({
    id: chain.id,
    currentSnapshotId: snapshot.id,
    currentSnapshotName: "V1",
  });
  // A side with no snapshot is the chain's current state, under a placeholder id.
  expect(difference.rightEntity).toMatchObject({
    id: chain.id,
    currentSnapshotId: "00000000-0000-0000-0000-000000000000",
    currentSnapshotName: "Current",
  });

  const byRight = new Map(difference.elementsDifferences.map((pair) => [pair.rightElement?.originalId, pair]));
  expect([...byRight.keys()].sort()).toEqual([kept.id, added.id].sort());

  // Within one chain the pairing is by id, so the renamed element is one pair naming the field.
  const changed = byRight.get(kept.id)!;
  expect(changed.leftElement).toMatchObject({ originalId: kept.id, name: kept.name });
  expect(changed.rightElement?.name).toBe("renamed");
  expect(changed).toMatchObject({ onlyOnLeft: [], onlyOnRight: [], differing: ["name"] });

  // The added element has no left side, and every field it carries is reported on the right.
  const fresh = byRight.get(added.id)!;
  expect(fresh.leftElement).toBeUndefined();
  expect(fresh.rightElement?.type).toBe("log-record");
  expect(fresh.onlyOnRight).toEqual(
    expect.arrayContaining(["name", "type", ...Object.keys(added.properties).map((key) => `properties.${key}`)]),
  );
  expect(fresh).toMatchObject({ onlyOnLeft: [], differing: [] });

  const halfRequest = await catalog.raw("post", "/v1/chains/diff", { leftChainId: chain.id });
  expect(halfRequest.status(), "a request naming one side is refused").toBe(400);
  expect(((await halfRequest.json()) as { errorMessage: string }).errorMessage).toContain(
    "The rightChainId must not be null",
  );
  const unknown = await catalog.raw("post", "/v1/chains/diff", {
    leftChainId: chain.id,
    rightChainId: ABSENT_UUID,
  });
  expect(unknown.status()).toBe(404);
  expect(((await unknown.json()) as { errorMessage: string }).errorMessage).toBe(
    `Chain with id ${ABSENT_UUID} not found`,
  );
});

test("migrating a chain replaces a deprecated choice with a condition, keeping its branches", { tag: ["@catalog", "@tier2"] }, async ({ catalog, folder, run }) => {
  const parent = await caseFolder(catalog, run, folder.id, "migrate");
  const chain = await catalog.createChain(tokenized(run, "migrate"), parent);
  // The library still creates the deprecated container, with its two branches.
  const choice = await catalog.createElement(chain.id, "choice");
  expect((choice.children ?? []).map((child) => child.type).sort()).toEqual(["otherwise", "when"]);
  expect((await catalog.getChain(chain.id)).containsDeprecatedContainers).toBe(true);

  const migrated = await catalog.migrateChain(chain.id);

  expect(migrated.groupsRemoved).toBe(false);
  expect(migrated.chain.containsDeprecatedContainers).toBe(false);
  // The container keeps its id and changes type; the branches are rebuilt under new types.
  expect(migrated.chain.elements.map((each) => ({ id: each.id, type: each.type }))).toEqual([
    { id: choice.id, type: "condition" },
  ]);
  const branches = migrated.chain.elements[0].children ?? [];
  expect(branches.map((child) => child.type).sort()).toEqual(["else", "if"]);
  const originalBranches = (choice.children ?? []).map((child) => child.id);
  expect(branches.filter((child) => originalBranches.includes(child.id))).toEqual([]);
  expect(branches.find((child) => child.type === "if")?.properties).toEqual({ priority: 0 });

  // The response is the stored chain, so a re-read agrees with it.
  expect((await catalog.listChainElements(chain.id)).map((each) => each.type).sort()).toEqual([
    "condition",
    "else",
    "if",
  ]);

  const unknown = await catalog.raw("patch", `/v1/chains/${ABSENT_UUID}/migrate`);
  expect(unknown.status()).toBe(404);
  expect(((await unknown.json()) as { errorMessage: string }).errorMessage).toBe(
    `Can't find chain with id: ${ABSENT_UUID}`,
  );
});
