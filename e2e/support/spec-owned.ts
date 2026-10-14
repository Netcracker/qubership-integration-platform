/**
 * A spec-owned fixture that cannot join the shared corpus because it never goes live: imported,
 * deployed and deleted by the spec that reads it, and named in `SPEC_OWNED_FIXTURES`.
 */
import { expect } from "@playwright/test";
import { assembleFixture, readDocumentFixture } from "../fixtures/templating.js";
import { noteChain } from "./diagnostics.js";
import type { Catalog, ImportResult } from "./catalog.js";
import type { DeployedChain } from "./corpus.js";
import type { Env } from "../env/index.js";

/**
 * The fixture document read for what a case addresses the chain it becomes by. `contextPath` is
 * empty for a chain with no HTTP trigger.
 *
 * The trigger is found at the top level rather than through a walk: a trigger is never a
 * container's child.
 */
function describeFixture(file: string, run: string, dir: string): DeployedChain {
  const fixture = readDocumentFixture(file, run, dir);
  const content = (fixture.document.content ?? {}) as { elements?: Record<string, unknown>[] };
  const trigger = (content.elements ?? []).find((each) => each.type === "http-trigger");
  const contextPath = ((trigger?.properties ?? {}) as Record<string, string>).contextPath ?? "";
  return { id: fixture.id, name: String(fixture.document.name), contextPath };
}

/**
 * Imports one spec-owned fixture from `dir`, deploys it, and deletes it again whatever the body did.
 *
 * The delete in front of the import is rule 5 of the spec rules: the fixture ids are fixed, so
 * residue from a run that died before its teardown would make the import an update and `CREATED`
 * below would not hold. The delete in the `finally` matters more than usual, because the engine
 * retries a deployment parked at `PROCESSING` for as long as its chain exists.
 *
 * Deleting the chain is what ends that retry loop; no engine restart is needed. The catalog computes
 * a `stop` for the parked deployment, `stop()` returns `REMOVED` even where no Camel context was
 * ever created (`IntegrationRuntimeService.java:703-711`), and `case REMOVED` drops the cache entry
 * and the retry-queue entry together (`:386-391`). Measured against one engine that was never
 * restarted: a deleted chain left no runtime-deployment row and no further
 * `Deployment marked for retry` line, with or without an undeploy first. So a case may assert an
 * exact one-row list rather than hunt for its own deployment id.
 *
 * No logging properties are written: these chains never run, so there is no trace to raise a level
 * for, and no Consul key is left under an id this function deletes.
 */
export async function withOwnFixture(
  catalog: Catalog,
  run: string,
  file: string,
  dir: string,
  body: (chain: DeployedChain) => Promise<void>,
): Promise<void> {
  const chain = describeFixture(file, run, dir);
  noteChain(chain);

  await catalog.raw("delete", `/v1/chains/${chain.id}`);
  const response = await catalog.importChains(await assembleFixture(file, run, dir));
  expect(response.status(), await response.text()).toBe(200);
  expect(((await response.json()) as ImportResult).chains).toEqual([
    expect.objectContaining({ id: chain.id, status: "CREATED" }),
  ]);

  try {
    const snapshot = await catalog.createSnapshot(chain.id);
    await catalog.deploy(chain.id, snapshot.id);
    await body(chain);
  } finally {
    await catalog.undeployAll(chain.id).catch((cause: unknown) => {
      console.error(`[teardown] ${chain.name} was not undeployed: ${String(cause)}`);
    });
    await catalog.raw("delete", `/v1/chains/${chain.id}`);
  }
}

/** The chain's runtime rows as `status: message`, which is what a deploy failure is asserted on. */
export async function deploymentRows(catalog: Catalog, chainId: string): Promise<string[]> {
  return (await catalog.runtimeDeploymentsOf(chainId)).map(
    (row) => `${row.status}: ${row.errorMessage ?? ""}`,
  );
}

/**
 * The status the chain's route answers. 404 is the reading a deployment that never went live is
 * pinned by.
 *
 * A rejection propagates. Mapping it to a status nothing answers would report a stack that is down
 * as `expected 404, received 0`, which names the wrong problem.
 */
export async function routeStatus(env: Env, chain: DeployedChain): Promise<number> {
  if (!chain.contextPath) throw new Error(`${chain.name} carries no HTTP trigger, so nothing can call it`);
  return (await fetch(env.chainUrl(chain.contextPath), { method: "GET" })).status;
}
