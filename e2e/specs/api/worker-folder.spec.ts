/**
 * The per-worker folder, the cascade it buys, and the reach of the sweep beside it.
 *
 * These are the two halves of the suite's isolation rule, and both are worth a live reading rather
 * than a claim in a comment. The folder is what makes teardown one call; the sweep is the only
 * thing that removes what the cascade cannot reach, and the residue check at the end of a run is
 * worth nothing without it.
 */
import { test, expect } from "../../support/fixtures.js";
import { findResidue } from "../../support/fixtures.js";
import { workerFolderName, tokenized } from "../../support/run.js";

test("the worker's folder exists, is named after the run, and is its own", { tag: ["@infra", "@tier1"] }, async ({ catalog, folder, run }, testInfo) => {
  expect(folder.name).toBe(workerFolderName(run, testInfo.workerIndex));

  const root = await catalog.listRootItems();
  const mine = root.filter((item) => item.id === folder.id);
  // Asserted over this worker's own row, never over the length of the list: a parallel worker's
  // folder is in that list too, and counting it is the mistake this suite's isolation rule exists
  // to prevent.
  expect(mine).toHaveLength(1);
  expect(mine[0].itemType).toBe("FOLDER");
});

test("deleting a folder takes the chains inside it", { tag: ["@infra", "@tier1"] }, async ({ catalog, run }) => {
  // A folder of its own rather than the worker's, because this case is about the cascade and it
  // has to delete the folder to see it.
  const folder = await catalog.createFolder(tokenized(run, "cascade"));
  const chain = await catalog.createChain(tokenized(run, "cascade-chain"), folder.id);
  expect((await catalog.getChain(chain.id)).id).toBe(chain.id);

  await catalog.deleteFolder(folder.id);

  const gone = await catalog.raw("get", `/v1/chains/${chain.id}`);
  expect(gone.status()).toBe(404);
});

test("a service is outside the cascade, so the sweep is what reaches it", { tag: ["@infra", "@tier1"] }, async ({ catalog, folder, run }) => {
  // Measured: an environment answered 200 after the folder holding its chain was gone, because it
  // hangs off a system and systems have no folder. So a service created here survives the folder
  // and is found by name, which is exactly what the sweep matches on.
  const service = await catalog.createSystem(tokenized(run, "swept"), "EXTERNAL");
  try {
    expect((await catalog.listSystems()).some((each) => each.id === service.id)).toBe(true);

    const residue = await findResidue(catalog, run);
    expect(
      residue.map((each) => `${each.kind}:${each.id}`),
      "the sweep must see a service the folder cascade cannot reach",
    ).toContain(`service:${service.id}`);
    // And the per-worker folder is residue too until its own teardown removes it.
    expect(residue.map((each) => each.id)).toContain(folder.id);
  } finally {
    await catalog.deleteSystem(service.id).catch(() => {});
  }
});

test("a context service and an MCP service are their own families, so the sweep has to look for them too", { tag: ["@infra", "@tier1"] }, async ({ catalog, run }) => {
  // Neither is an `IntegrationSystem`, so `GET /v1/systems` does not list them and the sweep's
  // services pass walks straight past both. Without this reading, a spec creating one leaves it on
  // the stack for every later run, and the residue check would never say so.
  const context = await catalog.createContextSystem(tokenized(run, "residue-ctx"));
  const mcp = await catalog.createMcpSystem({
    name: tokenized(run, "residue-mcp"),
    identifier: tokenized(run, "residue-mcp"),
  });
  try {
    expect(
      (await catalog.listSystems()).some((each) => each.id === context.id || each.id === mcp.id),
      "the services listing is the wrong place to look for either of them",
    ).toBe(false);

    const residue = await findResidue(catalog, run);
    const kinds = residue.map((each) => `${each.kind}:${each.id}`);
    expect(kinds).toContain(`context-system:${context.id}`);
    expect(kinds).toContain(`mcp-system:${mcp.id}`);
  } finally {
    await catalog.deleteContextSystem(context.id).catch(() => {});
    await catalog.deleteMcpSystem(mcp.id).catch(() => {});
  }
});
