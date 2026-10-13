/**
 * The edges of a chain's element graph: creating them, reading them back, and removing them.
 *
 * Covers `dependency-controller` whole — five operations on `/v1/chains/{chainId}/dependencies`.
 * `element-controller` owns the nodes and `specs/api/elements.spec.ts` covers it; the edges have
 * their own controller, their own validator and their own failure vocabulary, which is why they
 * have their own file.
 *
 * Every case builds its graph in a chain of its own, inside the worker folder, so the cascade
 * removes it and no assertion here reads a row another worker wrote.
 *
 * Five shapes measured rather than assumed:
 *
 * - **The listing is derived from the elements, not queried from the edges.**
 *   `DependencyMapper.extractDependencies` folds each element's input and output dependencies into
 *   a `HashSet` and walks containers' children as well. So the order is unstable — every assertion
 *   here sorts — and an edge inside a container appears **once** despite the element listing
 *   repeating its endpoints at the top level.
 * - **Only the listing is chain-scoped.** It resolves the chain first and answers 404 for a chain
 *   nothing answers to. `findById` and both deletes bind `chainId` and never read it. Not asserted:
 *   the operations are `no-bwc` and the UI always sends the owning chain (#842, won't fix).
 * - **A duplicate edge is a 409**, not an idempotent 200: `DependencyService.create` looks the pair
 *   up and throws `EntityExistsException` rather than returning the edge that exists.
 * - **The two deletes disagree about a missing id.** The plural one answers `200 {}` for ids it did
 *   not find; the singular one goes through `getReferenceById` and answers 404 with JPA's own
 *   message rather than the service's.
 * - **`inputEnabled` is a library property, and a trigger has it off.** An edge *into* an
 *   `http-trigger` is refused whatever it comes from — including the trigger itself, which is why
 *   the self-edge case reports the same message rather than a cycle error.
 */
import { test, expect } from "../../support/fixtures.js";
import type { Catalog, DependencyView } from "../../support/catalog.js";
import { ABSENT_UUID } from "../../support/absent.js";
import { emptyChain } from "../../support/deployable.js";

/** The chain's edges as `from->to` pairs, sorted, which is the only stable form of the listing. */
async function edgesOf(catalog: Catalog, chainId: string): Promise<string[]> {
  const dependencies = await catalog.listDependencies(chainId);
  return dependencies.map((each) => `${each.from}->${each.to}`).sort();
}

/** The one dependency a create answers with, so a case does not index into the diff by hand. */
function createdEdge(diff: { createdDependencies?: DependencyView[] }): DependencyView {
  const created = diff.createdDependencies ?? [];
  expect(created, "a create answers with exactly the edge it created").toHaveLength(1);
  return created[0];
}

test("the listing reports the graph the chain declares, containers included", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const chainId = await emptyChain(catalog, run, folder.id, "dependencies-listing");

  const trigger = await catalog.createElement(chainId, "http-trigger");
  const first = await catalog.createElement(chainId, "script");
  const second = await catalog.createElement(chainId, "script");
  const container = await catalog.createElement(chainId, "try-catch-finally-2");
  const branch = (container.children ?? []).find((child) => child.type === "try-2")!;
  const inner = await catalog.createElement(chainId, "script", { parentElementId: branch.id });
  const innerNext = await catalog.createElement(chainId, "script", { parentElementId: branch.id });

  expect(await edgesOf(catalog, chainId), "a fresh chain has nodes and no edges").toEqual([]);

  const edge = createdEdge(await catalog.createDependency(chainId, trigger.id, first.id));
  expect(edge).toMatchObject({ from: trigger.id, to: first.id });
  expect(edge.id, "the edge has an id of its own, distinct from either endpoint").not.toBe(trigger.id);
  expect(await edgesOf(catalog, chainId)).toEqual([`${trigger.id}->${first.id}`]);

  await catalog.createDependency(chainId, first.id, second.id);
  await catalog.createDependency(chainId, second.id, container.id);
  // Inside a container, between two children of the same branch. The element listing repeats both
  // endpoints at the chain's top level and again under the container, and the mapper walks both —
  // so an edge counted per element rather than deduplicated would appear here more than once.
  await catalog.createDependency(chainId, inner.id, innerNext.id);

  expect(await edgesOf(catalog, chainId), "each edge once, container children included").toEqual(
    [
      `${trigger.id}->${first.id}`,
      `${first.id}->${second.id}`,
      `${second.id}->${container.id}`,
      `${inner.id}->${innerNext.id}`,
    ].sort(),
  );

  // The listing is chain-scoped, unlike everything else this controller offers.
  const foreign = await catalog.raw("get", `/v1/chains/${ABSENT_UUID}/dependencies`);
  expect(foreign.status()).toBe(404);
  expect(await foreign.json()).toMatchObject({
    errorMessage: `Can't find chain with id: ${ABSENT_UUID}`,
  });
});

test("an edge reads back by its own id, and an id nothing answers to is a 404", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const chainId = await emptyChain(catalog, run, folder.id, "dependencies-read");
  const from = await catalog.createElement(chainId, "http-trigger");
  const to = await catalog.createElement(chainId, "script");
  const edge = createdEdge(await catalog.createDependency(chainId, from.id, to.id));

  expect(await catalog.getDependency(chainId, edge.id)).toEqual({
    id: edge.id,
    from: from.id,
    to: to.id,
  });

  const missing = await catalog.raw("get", `/v1/chains/${chainId}/dependencies/${ABSENT_UUID}`);
  expect(missing.status()).toBe(404);
  expect(await missing.json()).toMatchObject({
    serviceName: "Catalog",
    errorMessage: `Can't find dependency with id: ${ABSENT_UUID}`,
  });
});

test("both deletes answer the edge they removed, and the listing loses it", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const chainId = await emptyChain(catalog, run, folder.id, "dependencies-delete");
  const trigger = await catalog.createElement(chainId, "http-trigger");
  const first = await catalog.createElement(chainId, "script");
  const second = await catalog.createElement(chainId, "script");
  const one = createdEdge(await catalog.createDependency(chainId, trigger.id, first.id));
  const two = createdEdge(await catalog.createDependency(chainId, first.id, second.id));

  // The `@Deprecated` singular form.
  const removedOne = await catalog.deleteDependency(chainId, one.id);
  expect(removedOne.removedDependencies).toEqual([{ id: one.id, from: trigger.id, to: first.id }]);
  expect(await edgesOf(catalog, chainId)).toEqual([`${first.id}->${second.id}`]);
  // Deleting the nodes is not part of it: an edge goes without taking its endpoints.
  expect((await catalog.listChainElements(chainId)).map((each) => each.id).sort()).toEqual(
    [trigger.id, first.id, second.id].sort(),
  );

  const removedTwo = await catalog.deleteDependencies(chainId, [two.id]);
  expect(removedTwo.removedDependencies).toEqual([{ id: two.id, from: first.id, to: second.id }]);
  expect(await edgesOf(catalog, chainId)).toEqual([]);

  // The two disagree about an id neither can find, and the difference is not cosmetic: a caller
  // retrying a delete gets a success from one form and a failure from the other.
  const plural = await catalog.raw("delete", `/v1/chains/${chainId}/dependencies?dependenciesIds=${ABSENT_UUID}`);
  expect(plural.status()).toBe(200);
  expect(await plural.json()).toEqual({});

  const singular = await catalog.raw("delete", `/v1/chains/${chainId}/dependencies/${ABSENT_UUID}`);
  expect(singular.status()).toBe(404);
  expect((await singular.json()).errorMessage, "JPA's message, not the service's").toContain(
    `Unable to find`,
  );

  // The plural delete's argument is required rather than defaulted, so a client that forgets it
  // deletes nothing instead of deleting everything.
  const bare = await catalog.raw("delete", `/v1/chains/${chainId}/dependencies`);
  expect(bare.status()).toBe(400);
});

test("the validator refuses the edges the editor cannot draw", { tag: ["@catalog", "@tier2"] }, async ({ catalog, folder, run }) => {
  const chainId = await emptyChain(catalog, run, folder.id, "dependencies-refusals");
  const trigger = await catalog.createElement(chainId, "http-trigger");
  const script = await catalog.createElement(chainId, "script");
  const container = await catalog.createElement(chainId, "try-catch-finally-2");
  const branch = (container.children ?? []).find((child) => child.type === "try-2")!;
  const inner = await catalog.createElement(chainId, "script", { parentElementId: branch.id });
  const swimlane = await catalog.createElement(chainId, "swimlane");
  await catalog.createDependency(chainId, trigger.id, script.id);

  const refusal = async (from: string, to: string): Promise<{ status: number; message: string }> => {
    const response = await catalog.raw("post", `/v1/chains/${chainId}/dependencies`, { from, to });
    const body = (await response.json()) as { errorMessage?: string };
    return { status: response.status(), message: body.errorMessage ?? "" };
  };

  // A second edge between the same pair, rather than an idempotent 200 over the one that exists.
  expect(await refusal(trigger.id, script.id)).toEqual({
    status: 409,
    message: `Dependency from ${trigger.id} to ${script.id} already exists`,
  });

  // `http-trigger` declares `inputEnabled: false`, so nothing may point at it — the self-edge and
  // the back-edge report the same refusal, and neither is described as a cycle.
  expect(await refusal(script.id, trigger.id)).toEqual({
    status: 400,
    message: "Input dependency disabled for http-trigger",
  });
  expect(await refusal(trigger.id, trigger.id)).toEqual({
    status: 400,
    message: "Input dependency disabled for http-trigger",
  });

  // Into a container's child from outside it: the parents differ, so the edge would cross a
  // boundary the compiler cannot express.
  expect(await refusal(script.id, inner.id)).toEqual({
    status: 400,
    message: "Dependency to container child cannot be created",
  });

  // A swimlane is a layout element, and it is refused at either end.
  expect(await refusal(script.id, swimlane.id)).toEqual({
    status: 400,
    message: "Dependency from/to swimlane could not be created",
  });
  expect(await refusal(swimlane.id, script.id)).toEqual({
    status: 400,
    message: "Dependency from/to swimlane could not be created",
  });

  // An endpoint that does not exist fails on the lookup rather than in the validator.
  expect(await refusal(script.id, ABSENT_UUID)).toEqual({
    status: 404,
    message: `Can't find chain element with id: ${ABSENT_UUID}`,
  });

  // Seven refusals, and the graph is exactly what it was before them.
  expect(await edgesOf(catalog, chainId)).toEqual([`${trigger.id}->${script.id}`]);
});
