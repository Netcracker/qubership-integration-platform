/**
 * Snapshots on the catalog API: the compile, and the lifecycle around it.
 *
 * A snapshot is the only place the platform writes down what a chain *means*, so the first case
 * asserts its contents rather than only that one was produced. The Camel XML is where a chain's
 * elements become routes, and a compile that silently dropped the trigger would still answer 200
 * with an id.
 *
 * Six shapes measured rather than assumed:
 *
 * - `xmlDefinition` reaches the caller on a full `GET .../snapshots/{id}` and nowhere else. The
 *   controller nulls it on the build response, the listing and `?light=true`, and the serializer
 *   then drops the key entirely — the field is **absent**, not `null`, so a spec reading the XML
 *   off the build response asserts over `undefined`.
 * - Snapshots are named `V1`, `V2`, … per chain, by the catalog and not by the caller.
 * - `PUT .../snapshots/{id}` renames in place and does not need `labels`.
 * - The snapshot copies the chain's elements under **fresh ids**, so the compiled XML names none
 *   of the ids the chain was built with.
 * - Both deletes answer **204**, and a second delete of the same snapshot answers **404**.
 * - A revert writes the snapshot's elements back under the ids the chain had, makes the snapshot
 *   current, and keeps every snapshot built after it.
 *
 * Every case works on a chain of its own under the worker folder, so the per-chain listings see
 * only what the case built.
 */
import { test, expect } from "../../support/fixtures.js";
import { tokenized } from "../../support/run.js";
import { MARKER_HEADER, setMarker, tokenizedChain } from "../../support/deployable.js";

/**
 * A value no chain name here contains, so the assertion reads the marker and not the context path.
 *
 * The compile case reads the marker out of the XML, and a marker spelled like the case is a
 * substring of the chain's own `contextPath`, which is already in the document — so the assertion
 * that the header modification was compiled could not fail.
 */
const COMPILE_MARKER = "pinned-header-value";

test("a snapshot compiles the chain into Camel XML naming its trigger", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const chain = await tokenizedChain(catalog, run, {
    prefix: "snapshot",
    what: "compile",
    parentId: folder.id,
    marker: COMPILE_MARKER,
  });

  const built = await catalog.createSnapshot(chain.id);
  expect(built.name, "the catalog names the first snapshot of a chain V1").toBe("V1");
  expect(built.xmlDefinition, "the build response drops the XML it just produced").toBeUndefined();

  const full = await catalog.getSnapshot(chain.id, built.id);
  const xml = full.xmlDefinition ?? "";
  // The compiled consumer, not merely the string the property was set to: the trigger becomes a
  // servlet-custom endpoint under the engine's route prefix, and the method restriction rides on
  // the same URI. A compile that dropped the element would still answer with well-formed XML.
  expect(xml).toContain(`servlet-custom:${chain.contextPath}`);
  expect(xml).toContain("httpMethodRestrict=POST");
  // The second element compiles too, and it is its configuration rather than its identity that
  // proves it: the marker the header modification adds is inlined into the route as a constant.
  expect(xml).toContain("headerModificationProcessor");
  expect(xml).toContain(MARKER_HEADER);
  expect(xml).toContain(COMPILE_MARKER);

  // The snapshot re-ids the elements it copied, so the XML names none of the chain's element ids.
  // A spec correlating a compiled step back to the chain has to go through the snapshot's own
  // elements, not through the ids it created the chain with.
  expect(xml).not.toContain(chain.triggerId);
  expect(xml).not.toContain(chain.headerElementId);

  // The two cheap readings carry no XML at all, which is the point of asking for them.
  expect((await catalog.getSnapshot(chain.id, built.id, true)).xmlDefinition).toBeUndefined();
  expect((await catalog.listSnapshots(chain.id))[0].xmlDefinition).toBeUndefined();
});

test("snapshots are numbered per chain and the listing is scoped to the chain that owns them", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const chain = await tokenizedChain(catalog, run, {
    prefix: "snapshot",
    what: "numbering",
    parentId: folder.id,
  });
  const neighbor = await tokenizedChain(catalog, run, {
    prefix: "snapshot",
    what: "neighbor",
    parentId: folder.id,
  });

  const first = await catalog.createSnapshot(chain.id);
  const second = await catalog.createSnapshot(chain.id);
  const other = await catalog.createSnapshot(neighbor.id);

  expect([first.name, second.name]).toEqual(["V1", "V2"]);
  // The numbering restarts per chain rather than running across the catalog.
  expect(other.name).toBe("V1");

  expect((await catalog.listSnapshots(chain.id)).map((each) => each.id).sort()).toEqual(
    [first.id, second.id].sort(),
  );
  expect((await catalog.listSnapshots(neighbor.id)).map((each) => each.id)).toEqual([other.id]);
});

test("a snapshot is renamed in place, and the name survives a re-read", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const chain = await tokenizedChain(catalog, run, {
    prefix: "snapshot",
    what: "rename",
    parentId: folder.id,
  });
  const built = await catalog.createSnapshot(chain.id);

  const renamed = await catalog.renameSnapshot(chain.id, built.id, tokenized(run, "snapshot-named"));

  expect(renamed.id, "a rename answers with the same snapshot, not a copy").toBe(built.id);
  expect(renamed.name).toBe(tokenized(run, "snapshot-named"));
  // The rename response is not the record: re-read it, because an endpoint that echoes its own
  // request body passes a shallower assertion without having written anything.
  expect((await catalog.getSnapshot(chain.id, built.id, true)).name).toBe(
    tokenized(run, "snapshot-named"),
  );
  expect((await catalog.listSnapshots(chain.id))[0].name).toBe(tokenized(run, "snapshot-named"));
});

test("deleting one snapshot leaves the others, and delete-all empties the chain", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const chain = await tokenizedChain(catalog, run, {
    prefix: "snapshot",
    what: "delete",
    parentId: folder.id,
  });
  const doomed = await catalog.createSnapshot(chain.id);
  const survivor = await catalog.createSnapshot(chain.id);

  const deleted = await catalog.raw(
    "delete",
    `/v1/catalog/chains/${chain.id}/snapshots/${doomed.id}`,
  );
  expect(deleted.status(), "the snapshot delete answers 204").toBe(204);
  expect(
    (await catalog.raw("get", `/v1/catalog/chains/${chain.id}/snapshots/${doomed.id}`)).status(),
  ).toBe(404);
  // Deleting a snapshot that is already gone is a 404 rather than an idempotent success.
  expect(
    (await catalog.raw("delete", `/v1/catalog/chains/${chain.id}/snapshots/${doomed.id}`)).status(),
  ).toBe(404);
  expect((await catalog.listSnapshots(chain.id)).map((each) => each.id)).toEqual([survivor.id]);

  const cleared = await catalog.raw("delete", `/v1/catalog/chains/${chain.id}/snapshots`);
  expect(cleared.status()).toBe(204);
  expect(await catalog.listSnapshots(chain.id)).toEqual([]);
});

test("reverting to a snapshot puts the chain back to the elements it held then", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const chain = await tokenizedChain(catalog, run, {
    prefix: "snapshot",
    what: "revert",
    parentId: folder.id,
    marker: "first",
  });
  const first = await catalog.createSnapshot(chain.id);
  const headerName = (await catalog.getElement(chain.id, chain.headerElementId)).name;

  await setMarker(catalog, chain, "second");
  await catalog.patchElementProperties(chain.id, chain.headerElementId, {}, "renamed after V1");
  const added = await catalog.createElement(chain.id, "script");
  const second = await catalog.createSnapshot(chain.id);

  const reverted = await catalog.revertSnapshot(chain.id, first.id);
  expect(reverted).toMatchObject({ id: first.id, name: "V1" });
  expect(reverted.xmlDefinition, "the revert answers the snapshot without its XML").toBeUndefined();

  // The snapshot holds copies under fresh ids, and the revert writes them back under the ids the
  // chain had, so the element added after V1 is gone and the other two are the originals.
  const elements = await catalog.listChainElements(chain.id);
  expect(elements.map((each) => each.id).sort()).toEqual([chain.triggerId, chain.headerElementId].sort());
  expect(elements.map((each) => each.id)).not.toContain(added.id);
  const header = await catalog.getElement(chain.id, chain.headerElementId);
  expect(header.name).toBe(headerName);
  expect(header.properties.headerModificationToAdd).toEqual({ [MARKER_HEADER]: "first" });

  // The diff against V1 is the same reading made by the catalog: every pair matches.
  const difference = await catalog.compareChains({
    leftChainId: chain.id,
    leftSnapshotId: first.id,
    rightChainId: chain.id,
  });
  for (const pair of difference.elementsDifferences) {
    expect(pair.leftElement?.originalId, "each current element pairs with its V1 copy").toBe(
      pair.rightElement?.originalId,
    );
    expect([...pair.onlyOnLeft, ...pair.onlyOnRight, ...pair.differing]).toEqual([]);
  }
  expect(difference.elementsDifferences).toHaveLength(2);

  const view = await catalog.getChain(chain.id);
  expect(view.currentSnapshot?.id, "V1 is the chain's current snapshot again").toBe(first.id);
  expect(view.unsavedChanges, "the chain matches its current snapshot").toBe(false);
  // The revert keeps the snapshots built after the one it restores.
  expect((await catalog.listSnapshots(chain.id)).map((each) => each.id).sort()).toEqual(
    [first.id, second.id].sort(),
  );
});
