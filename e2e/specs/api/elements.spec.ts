/**
 * The elements inside a chain: creating them, editing them, moving them between containers and
 * swimlanes, grouping and cloning them, and deleting them again.
 *
 * Covers `element-controller` whole and `element-modification-controller` whole. That is the unit
 * because the two share a path prefix and a service.
 *
 * Every case asserts the chain's **element graph** after the change rather than the response code,
 * and `graphOf` below is what makes that one line: a create that answers 200 and attaches the child
 * to nothing is the shape this product has actually shipped, and a status assertion cannot see it.
 *
 * Six shapes measured rather than assumed, each pinned by the case that names it:
 *
 * - **`GET .../elements` is flat *and* nested.** Every element of the chain appears at the top
 *   level — a child of a container twice over, once on its own and once under its parent's
 *   `children`. So a reader counting the top level counts nested elements too.
 * - **A container arrives with its children.** `try-catch-finally-2` answers with `try-2`,
 *   `catch-2` and `finally-2` already created, so a case that then creates them collides with the
 *   library's `allowedChildren` quantities.
 * - **The first swimlane is the default swimlane.** The chain's first `POST {type: "swimlane"}`
 *   adopts every top-level element it finds and reports them under `updatedElements` with a
 *   `createdDefaultSwimlaneId`; the second creates an ordinary one and adopts nothing.
 * - **Deleting a container deletes everything under it**, and the removed list names each one.
 *   Deleting an element that is already gone answers **200 with `{}`** rather than 404.
 * - **`PUT .../properties-modification` clears `contextPath`.** It is the one property the call
 *   removes, and the removal is the point: an implemented trigger takes its path from the
 *   specification.
 * - **`GET .../elements/type/{type}` switches scope on the *shape* of its path variable.** A UUID
 *   is chain-scoped; anything else is platform-wide.
 *
 * - **The by-id operations ignore `chainId`.** An element is readable, patchable, and deletable
 *   through any chain's URL. Not asserted: the operations are `no-bwc` and the UI always sends the
 *   chain that holds the element (#842, won't fix).
 */
import { test, expect } from "../../support/fixtures.js";
import type { Catalog, ChainElement } from "../../support/catalog.js";
import { readSpecificationFixture } from "../../fixtures/templating.js";
import { tokenized } from "../../support/run.js";
import { ABSENT_UUID } from "../../support/absent.js";
import { covers } from "../../registry/covers.js";
import { emptyChain } from "../../support/deployable.js";

/**
 * The chain's element graph: element id → its type and the container holding it.
 *
 * Built off the top level of the listing only. That is not a shortcut — the listing repeats every
 * nested element there, so walking `children` as well would visit each child twice.
 */
async function graphOf(
  catalog: Catalog,
  chainId: string,
): Promise<Record<string, { type: string; parent: string | undefined }>> {
  const elements = await catalog.listChainElements(chainId);
  return Object.fromEntries(
    elements.map((element) => [element.id, { type: element.type, parent: element.parentElementId }]),
  );
}

/** Where each element sits, keyed by id: the swimlane half of the same picture. */
async function swimlanesOf(catalog: Catalog, chainId: string): Promise<Record<string, string | null>> {
  const elements = await catalog.listChainElements(chainId);
  // `null` rather than the `undefined` the endpoint sends, because `toEqual` drops an
  // undefined-valued key on **both** sides: an expectation written with them compares `{}` with
  // `{}` and holds for a listing that came back empty, or with entirely different ids.
  return Object.fromEntries(elements.map((element) => [element.id, element.swimlaneId ?? null]));
}

/** The `try-2` / `catch-2` / `finally-2` a `try-catch-finally-2` is created holding. */
function branchesOf(container: ChainElement): Record<string, string> {
  return Object.fromEntries((container.children ?? []).map((child) => [child.type, child.id]));
}

test("an element round-trips through create, read, patch, and delete", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const chainId = await emptyChain(catalog, run, folder.id, "elements-crud");

  const created = await catalog.createElement(chainId, "http-trigger");
  expect(created.type).toBe("http-trigger");
  // The library's defaults are materialised at create time rather than at compile time, which is
  // what makes a one-property patch fail with a validation error naming a property nobody touched.
  expect(created.properties).toMatchObject({
    accessControlType: "NONE",
    externalRoute: true,
    httpBinding: "handlingHttpBinding",
  });

  const read = await catalog.getElement(chainId, created.id);
  expect(read.id).toBe(created.id);
  expect(read.type).toBe("http-trigger");
  expect(read.parentElementId, "a top-level element has no container").toBeUndefined();

  const contextPath = tokenized(run, "elements-crud-path");
  const patched = await catalog.patchElementProperties(
    chainId,
    created.id,
    { contextPath },
    tokenized(run, "renamed-trigger"),
  );
  // The patch answers a diff, not the element: the element is under `updatedElements`.
  expect(patched.updatedElements?.map((each) => each.id)).toEqual([created.id]);
  const reread = await catalog.getElement(chainId, created.id);
  expect(reread.name).toBe(tokenized(run, "renamed-trigger"));
  expect(reread.properties.contextPath).toBe(contextPath);
  // And the defaults survived the patch, which is the half a wholesale replacement loses.
  expect(reread.properties.accessControlType).toBe("NONE");

  const removed = await catalog.deleteElement(chainId, created.id);
  expect(removed.removedElements?.map((each) => each.id)).toEqual([created.id]);
  expect((await catalog.raw("get", `/v1/chains/${chainId}/elements/${created.id}`)).status()).toBe(404);
  expect(await graphOf(catalog, chainId)).toEqual({});

  // Deleting it again is a no-op rather than a 404: an empty diff, and HTTP 200.
  const again = await catalog.deleteElement(chainId, created.id);
  expect(again).toEqual({});
});

test("a container is created with its branches, and deleting it takes everything under it", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const chainId = await emptyChain(catalog, run, folder.id, "elements-container");

  const container = await catalog.createElement(chainId, "try-catch-finally-2");
  const branches = branchesOf(container);
  expect(
    Object.keys(branches).sort(),
    "the library creates the three branches with the container",
  ).toEqual(["catch-2", "finally-2", "try-2"]);

  const script = await catalog.createElement(chainId, "script", { parentElementId: branches["try-2"] });
  expect(await graphOf(catalog, chainId)).toEqual({
    [container.id]: { type: "try-catch-finally-2", parent: undefined },
    [branches["try-2"]]: { type: "try-2", parent: container.id },
    [branches["catch-2"]]: { type: "catch-2", parent: container.id },
    [branches["finally-2"]]: { type: "finally-2", parent: container.id },
    [script.id]: { type: "script", parent: branches["try-2"] },
  });

  const removed = await catalog.deleteElements(chainId, [container.id]);
  expect(
    (removed.removedElements ?? []).map((each) => each.id).sort(),
    "the delete names every element it took, not only the one it was given",
  ).toEqual([container.id, script.id, ...Object.values(branches)].sort());
  expect(await graphOf(catalog, chainId)).toEqual({});
});

test("transfer moves an element from one container to another", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const chainId = await emptyChain(catalog, run, folder.id, "elements-transfer");
  const container = await catalog.createElement(chainId, "try-catch-finally-2");
  const branches = branchesOf(container);
  const script = await catalog.createElement(chainId, "script", { parentElementId: branches["try-2"] });

  expect((await graphOf(catalog, chainId))[script.id].parent).toBe(branches["try-2"]);

  const diff = await catalog.transferElements(chainId, {
    parentId: branches["catch-2"],
    elements: [script.id],
  });
  // Both containers are reported: the one that lost the child and the one that gained it.
  expect((diff.updatedElements ?? []).map((each) => each.id).sort()).toEqual(
    [branches["try-2"], branches["catch-2"]].sort(),
  );

  const graph = await graphOf(catalog, chainId);
  expect(graph[script.id].parent, "the script now hangs off the catch branch").toBe(branches["catch-2"]);
  // And it moved rather than being copied: the try branch holds nothing.
  expect(Object.values(graph).filter((each) => each.parent === branches["try-2"])).toEqual([]);
});

test("the first swimlane adopts the chain, and a transfer moves an element between swimlanes", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  covers("swimlane");
  const chainId = await emptyChain(catalog, run, folder.id, "elements-swimlane");
  const trigger = await catalog.createElement(chainId, "http-trigger");
  const script = await catalog.createElement(chainId, "script");
  expect(await swimlanesOf(catalog, chainId)).toEqual({ [trigger.id]: null, [script.id]: null });

  const first = await catalog.createElementDiff(chainId, "swimlane");
  const defaultSwimlane = first.createdElements?.[0];
  expect(defaultSwimlane?.name).toBe("Default swimlane");
  expect(first.createdDefaultSwimlaneId).toBe(defaultSwimlane?.id);
  expect(
    (first.updatedElements ?? []).map((each) => each.id).sort(),
    "the first swimlane adopts every element already in the chain",
  ).toEqual([trigger.id, script.id].sort());

  const second = await catalog.createElement(chainId, "swimlane");
  expect(second.name, "the second is an ordinary swimlane, not another default").toBe("Swimlane");

  const moved = await catalog.transferElements(chainId, {
    swimlaneId: second.id,
    elements: [trigger.id],
  });
  expect(moved.updatedElements?.map((each) => each.id)).toEqual([trigger.id]);

  expect(await swimlanesOf(catalog, chainId)).toEqual({
    [defaultSwimlane!.id]: null,
    [second.id]: null,
    [trigger.id]: second.id,
    // The one that did not move stayed where the default swimlane put it.
    [script.id]: defaultSwimlane!.id,
  });
});

test("a patch never moves the element, whatever parentElementId it carries", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  // A patch used to move a nested element to the container it named, or to the top level when it
  // named none (#1003). `POST .../elements/transfer` is now the only way to move an element.
  const chainId = await emptyChain(catalog, run, folder.id, "elements-patch-parent");
  const container = await catalog.createElement(chainId, "try-catch-finally-2");
  const branches = branchesOf(container);
  const script = await catalog.createElement(chainId, "script", { parentElementId: branches["try-2"] });

  const patch = (body: Record<string, unknown>) =>
    catalog.raw("patch", `/v1/chains/${chainId}/elements/${script.id}`, {
      name: script.name,
      type: script.type,
      properties: { ...script.properties, script: "// patched" },
      ...body,
    });
  expect((await patch({})).status()).toBe(200);
  expect((await graphOf(catalog, chainId))[script.id].parent, "a patch without parentElementId").toBe(branches["try-2"]);
  expect((await patch({ parentElementId: branches["finally-2"] })).status()).toBe(200);
  expect((await graphOf(catalog, chainId))[script.id].parent, "a patch naming another branch").toBe(branches["try-2"]);
  expect((await catalog.getElement(chainId, script.id)).properties.script).toBe("// patched");
});

test("grouping wraps elements in a container, and ungrouping lifts them back out", { tag: ["@catalog", "@tier2"] }, async ({ catalog, folder, run }) => {
  const chainId = await emptyChain(catalog, run, folder.id, "elements-group");
  const script = await catalog.createElement(chainId, "script");
  const header = await catalog.createElement(chainId, "header-modification");

  const group = await catalog.groupElements(chainId, [script.id, header.id]);
  expect(group.type).toBe("container");
  expect((group.children ?? []).map((each) => each.id).sort()).toEqual([script.id, header.id].sort());
  expect(await graphOf(catalog, chainId)).toEqual({
    [group.id]: { type: "container", parent: undefined },
    [script.id]: { type: "script", parent: group.id },
    [header.id]: { type: "header-modification", parent: group.id },
  });

  const freed = await catalog.ungroupElements(chainId, group.id);
  expect(freed.map((each) => each.id).sort()).toEqual([script.id, header.id].sort());
  expect(
    await graphOf(catalog, chainId),
    "ungrouping removes the container and keeps its children",
  ).toEqual({
    [script.id]: { type: "script", parent: undefined },
    [header.id]: { type: "header-modification", parent: undefined },
  });
});

test("clone copies an element into a container under a new id", { tag: ["@catalog", "@tier2"] }, async ({ catalog, folder, run }) => {
  const chainId = await emptyChain(catalog, run, folder.id, "elements-clone");
  const container = await catalog.createElement(chainId, "try-catch-finally-2");
  const branches = branchesOf(container);
  const script = await catalog.createElement(chainId, "script", { parentElementId: branches["try-2"] });
  await catalog.patchElementProperties(chainId, script.id, { script: "// e2e clone source" });

  const [clone] = await catalog.cloneElements(chainId, [
    { id: script.id, parent: branches["finally-2"] },
  ]);

  expect(clone.id, "a clone is a new element, not the same one moved").not.toBe(script.id);
  expect(clone.parentElementId).toBe(branches["finally-2"]);
  // The properties come with it: a clone that copied only the type would pass every assertion
  // about the graph and lose the element's whole configuration.
  expect(clone.properties.script).toBe("// e2e clone source");

  const graph = await graphOf(catalog, chainId);
  expect(graph[script.id].parent, "the original stayed in the try branch").toBe(branches["try-2"]);
  expect(graph[clone.id].parent).toBe(branches["finally-2"]);
});

test("the code view round-trips a rename and a description", { tag: ["@catalog", "@tier2"] }, async ({ catalog, folder, run }) => {
  const chainId = await emptyChain(catalog, run, folder.id, "elements-code");
  const script = await catalog.createElement(chainId, "script");

  const code = await catalog.elementsCode(chainId);
  expect(code, "the code view is a YAML document keyed by element id").toContain(script.id);

  const renamed = tokenized(run, "named-in-code");
  await catalog.saveElementsCode(
    chainId,
    `---\n- id: "${script.id}"\n  name: "${renamed}"\n  description: "written through the code view"\n  properties:\n    exportFileExtension: "groovy"\n    propertiesToExportInSeparateFile: "script"\n`,
  );

  const reread = await catalog.getElement(chainId, script.id);
  expect(reread.name).toBe(renamed);
  expect(reread.description).toBe("written through the code view");
});

test("the type query is chain-scoped for a UUID and platform-wide for anything else", { tag: ["@catalog", "@tier2"] }, async ({ catalog, folder, run }) => {
  const chainId = await emptyChain(catalog, run, folder.id, "elements-type-query");
  const script = await catalog.createElement(chainId, "script");
  await catalog.createElement(chainId, "header-modification");

  const scoped = await catalog.elementsOfType(chainId, "script");
  expect(
    scoped.map((each) => each.id),
    "a UUID in the path scopes the query to that chain, so this set is closed",
  ).toEqual([script.id]);
  expect(scoped[0].chainName).toBe(tokenized(run, "elements-type-query"));

  // A path variable that is not a UUID falls through to the platform-wide query, whose length is
  // nobody's to assert on a shared stack. So the superset is proven with a second chain of this
  // case's own: the wide answer holds both scripts and the narrow one holds neither the other
  // chain's nor anything else.
  const otherChainId = await emptyChain(catalog, run, folder.id, "elements-type-query-other");
  const otherScript = await catalog.createElement(otherChainId, "script");

  const platformWide = (await catalog.elementsOfType("all", "script")).map((each) => each.id);
  expect(platformWide, "the query the path variable did not scope answers this chain's script").toContain(script.id);
  expect(platformWide, "and the other chain's, which is what makes it platform-wide").toContain(otherScript.id);

  // Re-read rather than reused: `scoped` above was taken before the other chain's script existed,
  // so asserting it does not hold that id would be true whatever the endpoint scopes on. This read
  // is taken with both scripts on the stack, and it still answers one.
  expect(
    (await catalog.elementsOfType(chainId, "script")).map((each) => each.id),
    "while the chain-scoped one stops at the chain in its path",
  ).toEqual([script.id]);
});

test("used exchange properties are read out of the chain's scripts", { tag: ["@catalog", "@tier2"] }, async ({ catalog, folder, run }) => {
  const chainId = await emptyChain(catalog, run, folder.id, "elements-used-properties");
  expect(await catalog.usedProperties(chainId), "a chain with no script uses nothing").toEqual([]);

  const script = await catalog.createElement(chainId, "script");
  await catalog.patchElementProperties(chainId, script.id, {
    script: "exchange.setProperty('e2eWritten', 'x')\ndef read = exchange.getProperty('e2eRead')\n",
  });

  const used = await catalog.usedProperties(chainId);
  expect(used.map((each) => each.name).sort()).toEqual(["e2eRead", "e2eWritten"]);
  for (const property of used) {
    expect(property.source).toBe("EXCHANGE_PROPERTY");
    expect(Object.keys(property.relatedElements)).toEqual([script.id]);
  }
});

test("properties-modification points a trigger at a specification group and clears its contextPath", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const chainId = await emptyChain(catalog, run, folder.id, "elements-modification");
  const trigger = await catalog.createElement(chainId, "http-trigger");
  const before = await catalog.patchElementProperties(chainId, trigger.id, {
    contextPath: tokenized(run, "before-modification"),
  });
  expect(before.updatedElements?.[0].properties.contextPath).toBe(tokenized(run, "before-modification"));

  const service = await catalog.createSystem(tokenized(run, "elements-modification"), "EXTERNAL");
  try {
    const groupName = tokenized(run, "elements-group");
    const started = await catalog.importSpecificationGroup(
      service.id,
      groupName,
      readSpecificationFixture("widgets.openapi.yaml"),
      "http",
    );
    const imported = await catalog.awaitSpecificationImport(started);
    const groupId = `${service.id}-${groupName}`;

    await catalog.modifyHttpTriggerProperties(chainId, groupId, [trigger.id]);

    const modified = await catalog.getElement(chainId, trigger.id);
    expect(modified.properties).toMatchObject({
      systemType: "IMPLEMENTED",
      integrationSystemId: service.id,
      integrationSpecificationGroupId: groupId,
      integrationSpecificationId: imported.specifications[0].id,
    });
    // The clearing is the point of the call: an implemented trigger takes its path from the
    // specification, so the property is set to null and the response drops the key entirely.
    expect(
      "contextPath" in modified.properties,
      "the modification clears contextPath rather than leaving the old one",
    ).toBe(false);
    // And it edits nothing else: the trigger's own defaults are still there.
    expect(modified.properties.accessControlType).toBe("NONE");
  } finally {
    await catalog.deleteSystem(service.id).catch(() => {});
  }
});

test("a create the library cannot place is refused, and the chain is unchanged", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const chainId = await emptyChain(catalog, run, folder.id, "elements-refusals");
  const container = await catalog.createElement(chainId, "try-catch-finally-2");
  const branches = branchesOf(container);
  const before = await graphOf(catalog, chainId);

  // A type the library does not offer. The message is the parent check's, because the lookup that
  // fails is the descriptor lookup inside it.
  const unknown = await catalog.raw("post", `/v1/chains/${chainId}/elements`, { type: "no-such-element" });
  expect(unknown.status()).toBe(400);
  expect(await unknown.text()).toContain("Element of type no-such-element cannot be a child");

  // A type the library offers only inside one parent, asked for at the chain's top level.
  const orphaned = await catalog.raw("post", `/v1/chains/${chainId}/elements`, { type: "try-2" });
  expect(orphaned.status()).toBe(400);
  expect(await orphaned.text()).toContain(
    "Element try-2 should be only inside parent element: try-catch-finally-2",
  );

  // A parent that cannot hold this child: a trigger has no input, so nothing can precede it.
  const inContainer = await catalog.raw("post", `/v1/chains/${chainId}/elements`, {
    type: "http-trigger",
    parentElementId: branches["try-2"],
  });
  expect(inContainer.status()).toBe(400);
  expect(await inContainer.text()).toContain(
    "Element with disabled input cannot be inside a parent element try-2",
  );

  // A parent that is not in the chain at all.
  const absentParent = await catalog.raw("post", `/v1/chains/${chainId}/elements`, {
    type: "script",
    parentElementId: ABSENT_UUID,
  });
  expect(absentParent.status()).toBe(400);
  expect(await absentParent.text()).toContain(`Element ${ABSENT_UUID} does not exist in chain ${chainId}`);

  expect(await graphOf(catalog, chainId), "four refusals left the chain exactly as it was").toEqual(before);
});

test("a transfer the library cannot place is refused, and the chain is unchanged", { tag: ["@catalog", "@tier2"] }, async ({ catalog, folder, run }) => {
  const chainId = await emptyChain(catalog, run, folder.id, "elements-transfer-refusals");
  const container = await catalog.createElement(chainId, "try-catch-finally-2");
  const branches = branchesOf(container);
  const trigger = await catalog.createElement(chainId, "http-trigger");
  const before = await graphOf(catalog, chainId);

  const intoContainer = await catalog.raw("post", `/v1/chains/${chainId}/elements/transfer`, {
    parentId: branches["try-2"],
    elements: [trigger.id],
  });
  expect(intoContainer.status()).toBe(400);
  expect(await intoContainer.text()).toContain(
    "Element with disabled input cannot be inside a parent element try-2",
  );

  // A container into one of its own branches.
  const intoItself = await catalog.raw("post", `/v1/chains/${chainId}/elements/transfer`, {
    parentId: branches["try-2"],
    elements: [container.id],
  });
  expect(intoItself.status()).toBe(400);
  expect(await intoItself.text()).toContain("Element cannot be transfer into itself");

  const absentParent = await catalog.raw("post", `/v1/chains/${chainId}/elements/transfer`, {
    parentId: ABSENT_UUID,
    elements: [trigger.id],
  });
  expect(absentParent.status()).toBe(400);
  expect(await absentParent.text()).toContain(`Element with id ${ABSENT_UUID} not found`);

  expect(await graphOf(catalog, chainId), "three refusals left the chain exactly as it was").toEqual(before);
});
