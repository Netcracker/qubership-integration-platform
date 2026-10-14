/**
 * `runtime-deployment-controller`, whole: one operation, `GET /v1/catalog/runtime-deployments`.
 *
 * Global by construction, and `e2e/AGENTS.md` rule 2 is what puts the file here: the answer is
 * every deployment of every chain on every engine pod, keyed by chain id. No parameter narrows it
 * — `fields` reduces each row's *shape* and never the set of rows — so a spec asserting anything
 * about the answer as a whole is asserting over what every other worker has deployed.
 *
 * The suite already reaches this endpoint on every deployment assertion:
 * `Catalog.runtimeDeployments` is what `waitForDeployed` polls to decide a corpus chain is up, and
 * `specs/api/deployments.spec.ts` pins the dict-keyed-by-chain-id shape. What is left, and what
 * this file covers, is the `fields` parameter — the half of the operation nothing else touches.
 *
 * **The 204 branch is unreachable here and is recorded rather than scheduled.**
 * `findChainRuntimeDeployments` answers `noContent()` when the map is empty, which means the whole
 * platform has zero deployments on zero engines. This spec runs in `global`, which depends on
 * `runtime`, which depends on the seed that deploys every chain under `fixtures/chains/` and holds
 * them for the length of the run — so the empty answer cannot be produced without dismantling the
 * corpus every other project is asserting over. `Catalog.runtimeDeployments` unwraps the 204 into
 * an empty dict for the callers that would otherwise parse an empty body, and that is the whole of
 * the branch's treatment in this suite.
 *
 * Three shapes measured against the stack rather than assumed:
 *
 * - `fields` selects with a Jackson `filterOutAllExcept` over two filters, not with a projection
 *   the query knows about. `deploymentInfo.x` selects inside the nested object, everything else
 *   selects at the row's top level, and naming any nested field pulls `deploymentInfo` itself in.
 * - a name **nothing carries** is not refused: it selects nothing, and every row comes back `{}`.
 * - the values are repeated rather than the parameter: `?fields=a&fields=b` and `?fields=a,b` bind
 *   to the same `String[]`, so the client sends the comma form and both are the same request.
 */
import { test, expect } from "../../support/fixtures.js";
import { readCorpusState } from "../../support/corpus.js";
import type { Catalog, RuntimeDeployment } from "../../support/catalog.js";

/**
 * The corpus rows out of a catalog-wide answer.
 *
 * Every assertion below is scoped this way. The dict holds whatever the developer running the
 * suite left deployed, so "the map has N entries" is a claim about the machine rather than about
 * the endpoint.
 */
async function corpusRows(
  catalog: Catalog,
): Promise<Array<{ fixture: string; chainId: string; rows: RuntimeDeployment[] }>> {
  const all = await catalog.runtimeDeployments();
  return readCorpusState().chains.map((chain) => ({
    fixture: chain.fixture,
    chainId: chain.id,
    rows: all[chain.id] ?? [],
  }));
}

test("every seed chain is keyed by its own id, carrying the deployment the catalog recorded", { tag: ["@catalog", "@engine", "@tier1"] }, async ({ catalog }) => {
  const corpus = await corpusRows(catalog);

  for (const { fixture, chainId, rows } of corpus) {
    expect(rows, `${fixture} has no runtime row at all`).toHaveLength(1);
    const row = rows[0];

    // The row is the engine's reading, and the catalog's own deployment record is the other side
    // of it: a runtime view keyed on a deployment the catalog does not have is the failure this
    // pairing exists to catch.
    const recorded = await catalog.listDeployments(chainId);
    expect(recorded.map((each) => each.id)).toEqual([row.deploymentInfo.deploymentId]);
    expect(row.deploymentInfo.snapshotId).toBe(recorded[0].snapshotId);
    expect(row.deploymentInfo.chainId).toBe(chainId);
    expect(row.status).toBe("DEPLOYED");
    expect(row.suspended).toBe(false);
    // `errorMessage` is set on FAILED and on a retriable PROCESSING, and absent on DEPLOYED.
    expect(row.errorMessage).toBeUndefined();
    // The pod that answered. `specs/global/engines.spec.ts` is where it is reconciled with the
    // domain's host list; here it only has to be there, because a row with no host is a row no
    // engine reported.
    expect(row.host, `${fixture} was reported by no engine host`).toBeTruthy();
  }
});

test("fields reduces every row to the names it lists, at the top level and inside deploymentInfo", { tag: ["@catalog", "@tier2"] }, async ({ catalog }) => {
  const corpus = readCorpusState().chains;

  // One top-level name on its own: the row is that key and nothing else, `deploymentInfo`
  // included. A filter that left the nested object in would make the parameter useless to the UI,
  // which asks for `status` alone to poll a deployment.
  const statusOnly = await catalog.runtimeDeploymentFields(["status"]);
  for (const chain of corpus) {
    const rows = statusOnly[chain.id] ?? [];
    expect(rows, `${chain.fixture} is missing from the filtered answer`).toHaveLength(1);
    expect(Object.keys(rows[0]).sort()).toEqual(["status"]);
    expect(rows[0].status).toBe("DEPLOYED");
  }

  // A nested name pulls `deploymentInfo` in and reduces it to that one key. Both halves matter:
  // without the first the nested object would be filtered out of the row entirely, and without the
  // second the whole of it would come back.
  const nested = await catalog.runtimeDeploymentFields(["status", "deploymentInfo.chainName"]);
  for (const chain of corpus) {
    const rows = nested[chain.id] ?? [];
    expect(rows, `${chain.fixture} is missing from the filtered answer`).toHaveLength(1);
    expect(Object.keys(rows[0]).sort()).toEqual(["deploymentInfo", "status"]);
    expect(rows[0].deploymentInfo).toEqual({ chainName: chain.name });
  }

  // And the unfiltered answer is the one that carries everything, which is what makes the two
  // above a reduction rather than the endpoint's ordinary shape.
  const whole = await catalog.runtimeDeployments();
  const sample = whole[corpus[0].id]?.[0];
  expect(Object.keys(sample ?? {}).sort()).toEqual(["deploymentInfo", "host", "status", "suspended"]);
});

test("a field name nothing carries selects nothing rather than being refused", { tag: ["@catalog", "@tier2"] }, async ({ catalog }) => {
  const corpus = readCorpusState().chains;

  const unknown = await catalog.runtimeDeploymentFields(["no-such-field"]);
  for (const chain of corpus) {
    const rows = unknown[chain.id] ?? [];
    expect(rows, `${chain.fixture} is missing from the filtered answer`).toHaveLength(1);
    // The row survives with no properties at all. `filterOutAllExcept` is a serialization filter,
    // so an unrecognised name is a name that matches no property rather than a bad request — the
    // rows are still there, and every one of them is empty.
    expect(rows[0]).toEqual({});
  }
});
