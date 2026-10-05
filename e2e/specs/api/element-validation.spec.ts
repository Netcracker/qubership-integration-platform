/**
 * Route collisions: `element-validation-controller`, whole.
 *
 * The name misleads. The controller does not validate a
 * chain — it answers "would an HTTP trigger on this path collide with a route somebody has already
 * deployed", which is the question the UI asks while a trigger's `contextPath` is being typed.
 * Chain-rule validation is `diagnostic-controller`, and it lives in `specs/global/diagnostic.spec.ts`
 * because it rewrites one alert table for the whole catalog.
 *
 * These two are parallel-safe despite reading across every chain on the stack: the predicate is the
 * URI, and every URI here carries the run token, so no other worker and no other run can put a row
 * into an answer these cases read.
 *
 * Five shapes measured against the stack rather than assumed:
 *
 * - **Only a deployed trigger collides.** `findElementsForRouteExistenceCheck` is
 *   `elements INNER JOIN deployments ON e.snapshot_id = d.snapshot_id`, so a chain that has been
 *   snapshotted and not deployed is invisible here. Case 1 asserts the path is free before the
 *   deploy for that reason, and free again after the undeploy.
 * - **`excludeChainId` is required, not optional.** Omitting it answers 400. The UI always asks on
 *   behalf of the chain it is editing, and a chain always collides with itself; a spec wanting an
 *   unfiltered answer passes `ABSENT_UUID`.
 * - **Methods intersect, they do not have to match.** `ElementRouteUtils.intersects` is
 *   `route.methods.anyMatch(other.methods::contains)`, and an empty `httpMethods` means every
 *   method rather than none.
 * - **An unknown method is accepted and answers `false`.** Spring 6's `HttpMethod` is a class and
 *   not an enum, so `HttpMethod.valueOf("NOTAMETHOD")` mints one instead of failing conversion.
 *   Recorded below as measured behaviour and deliberately not filed: the endpoint has no way to
 *   reject the token without an allowlist that would date with the Spring version, so there is no
 *   answer this spec could assert instead.
 * - **The path is compared segment by segment, with placeholder names erased.** `PathParser`
 *   rewrites `{anything}` to a single placeholder character, and `PathIntersectionChecker` refuses
 *   two paths of different lengths outright — so `a/{id}` collides with `a/{other}`, and with
 *   neither `a/42` nor `a`.
 *
 * The `isExternalRoute` and `isPrivateRoute` parameters are sent by one case and asserted to change
 * nothing: `intersects` reads the path and the methods and never the flags.
 */
import { test, expect } from "../../support/fixtures.js";
import { ABSENT_UUID } from "../../support/absent.js";
import { createDeployableChain, tokenizedChain, type DeployableChain } from "../../support/deployable.js";
import { tokenized } from "../../support/run.js";
import type { Catalog } from "../../support/catalog.js";

/** Snapshot and deploy, which is the only state in which this controller can see a trigger. */
async function deployChain(catalog: Catalog, chain: { id: string }) {
  const snapshot = await catalog.createSnapshot(chain.id);
  return { snapshot, deployment: await catalog.deploy(chain.id, snapshot.id) };
}

test("a path collides only once a chain is deployed on it, and only for that path", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const chain = await tokenizedChain(catalog, run, {
    prefix: "elemval",
    what: "collide",
    parentId: folder.id,
  });

  // Before the deploy the trigger exists and the route does not. The join onto `deployments` is
  // what makes that true, and it is the half a spec that deploys first can never see.
  expect(await catalog.checkRouteExists(chain.contextPath, ABSENT_UUID)).toBe(false);
  expect(await catalog.findRouteDeployments(chain.contextPath, ABSENT_UUID)).toEqual([]);

  const { snapshot, deployment } = await deployChain(catalog, chain);

  expect(await catalog.checkRouteExists(chain.contextPath, ABSENT_UUID)).toBe(true);
  // A path that merely starts with the same text is not a collision: the comparison is over whole
  // segments, not over a prefix.
  expect(await catalog.checkRouteExists(`${chain.contextPath}-other`, ABSENT_UUID)).toBe(false);

  // The long form names who is in the way. Every row in the answer is scoped to the URI asked
  // about, and the URI carries the run token, so this list is this case's own whatever else the
  // stack has deployed.
  const rows = await catalog.findRouteDeployments(chain.contextPath, ABSENT_UUID);
  expect(rows).toHaveLength(1);
  expect(rows[0]?.path).toBe(chain.contextPath);
  expect(rows[0]?.deployment).toMatchObject({
    id: deployment.id,
    chainId: chain.id,
    snapshotId: snapshot.id,
  });

  // And the undeploy takes the collision with it, which is the assertion that proves the two calls
  // read the deployment rather than the chain.
  await catalog.undeployAll(chain.id);
  expect(await catalog.checkRouteExists(chain.contextPath, ABSENT_UUID)).toBe(false);
  expect(await catalog.findRouteDeployments(chain.contextPath, ABSENT_UUID)).toEqual([]);
});

test("excludeChainId drops the asking chain out of its own answer", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const chain = await tokenizedChain(catalog, run, {
    prefix: "elemval",
    what: "exclude",
    parentId: folder.id,
  });
  await deployChain(catalog, chain);
  try {
    // The same path, the same instant, and two answers: the parameter is the only difference.
    expect(await catalog.checkRouteExists(chain.contextPath, ABSENT_UUID)).toBe(true);
    expect(await catalog.checkRouteExists(chain.contextPath, chain.id)).toBe(false);

    expect(await catalog.findRouteDeployments(chain.contextPath, ABSENT_UUID)).toHaveLength(1);
    expect(await catalog.findRouteDeployments(chain.contextPath, chain.id)).toEqual([]);
  } finally {
    // `chain-roles.spec.ts` states the policy in full: the worker folder's cascade reaches the
    // route anyway, so this is about when the engine stops serving it rather than whether.
    await catalog.undeployAll(chain.id).catch(() => {});
  }
});

test("the collision is scoped by HTTP method, and the flags are not read at all", { tag: ["@catalog", "@tier2"] }, async ({ catalog, folder, run }) => {
  // `tokenizedChain` restricts the trigger to POST, which is what makes a method scope observable.
  const chain = await tokenizedChain(catalog, run, {
    prefix: "elemval",
    what: "methods",
    parentId: folder.id,
  });
  await deployChain(catalog, chain);
  const collidesOn = (route: Parameters<Catalog["checkRouteExists"]>[2]) =>
    catalog.checkRouteExists(chain.contextPath, ABSENT_UUID, route);
  try {
    expect(await collidesOn({ httpMethods: ["POST"] })).toBe(true);
    expect(await collidesOn({ httpMethods: ["GET"] })).toBe(false);
    // Intersection, not equality: one shared method is a collision.
    expect(await collidesOn({ httpMethods: ["GET", "POST"] })).toBe(true);
    // No methods at all is every method, so it collides with a trigger restricted to any one of them.
    expect(await collidesOn({})).toBe(true);

    // A token no HTTP method has. Spring 6 mints an `HttpMethod` for it rather than refusing the
    // conversion, so the request is served and answers "no collision" — the measured contract, and
    // the day it becomes a 400 this line is what says so.
    expect(await collidesOn({ httpMethods: ["NOTAMETHOD"] })).toBe(false);

    // The trigger was created with `externalRoute: false`. Every combination of the two flags still
    // answers the same, because `intersects` compares the path and the methods and reads neither.
    for (const flags of [
      { isExternalRoute: true },
      { isExternalRoute: false },
      { isPrivateRoute: true },
      { isExternalRoute: false, isPrivateRoute: true },
    ]) {
      expect(await collidesOn(flags), `flags ${JSON.stringify(flags)} changed the answer`).toBe(true);
    }
  } finally {
    // `chain-roles.spec.ts` states the policy in full: the worker folder's cascade reaches the
    // route anyway, so this is about when the engine stops serving it rather than whether.
    await catalog.undeployAll(chain.id).catch(() => {});
  }
});

test("paths are compared segment by segment, with placeholder names erased", { tag: ["@catalog", "@tier2"] }, async ({ catalog, folder, run }) => {
  const base = tokenized(run, "elemval-placeholder");
  const chain: DeployableChain = await createDeployableChain(catalog, {
    name: base,
    parentId: folder.id,
    contextPath: `${base}/{bookId}`,
    marker: "placeholder",
  });
  await deployChain(catalog, chain);

  const collidesWith = (uri: string) => catalog.checkRouteExists(uri, ABSENT_UUID);
  try {
    expect(await collidesWith(`${base}/{bookId}`)).toBe(true);
    // The placeholder's *name* is erased, so two triggers naming their variable differently are the
    // same route. This is the case the check exists for.
    expect(await collidesWith(`${base}/{somethingElse}`)).toBe(true);
    // A literal segment is not a placeholder, whatever a live request would do with it.
    expect(await collidesWith(`${base}/42`)).toBe(false);
    // Different segment counts are refused before any segment is compared.
    expect(await collidesWith(base)).toBe(false);
    expect(await collidesWith(`${base}/{bookId}/pages`)).toBe(false);
    // Leading and trailing separators are stripped off both sides.
    expect(await collidesWith(`/${base}/{bookId}/`)).toBe(true);

    // The long form agrees, and reports the trigger's path as the chain declared it.
    const rows = await catalog.findRouteDeployments(`${base}/{other}`, ABSENT_UUID);
    expect(rows).toHaveLength(1);
    expect(rows[0]?.path).toBe(`${base}/{bookId}`);
  } finally {
    // `chain-roles.spec.ts` states the policy in full: the worker folder's cascade reaches the
    // route anyway, so this is about when the engine stops serving it rather than whether.
    await catalog.undeployAll(chain.id).catch(() => {});
  }
});

test("both route calls refuse a request missing either required parameter", { tag: ["@catalog", "@tier2"] }, async ({ catalog, run }) => {
  const uri = tokenized(run, "elemval-required");

  for (const endpoint of ["routes", "findRouteDeployments"]) {
    const noChain = await catalog.raw("get", `/v1/catalog/validation/${endpoint}?uri=${uri}`);
    expect(noChain.status(), `${endpoint} without excludeChainId`).toBe(400);
    expect(await noChain.text()).toContain("excludeChainId");

    const noUri = await catalog.raw(
      "get",
      `/v1/catalog/validation/${endpoint}?excludeChainId=${ABSENT_UUID}`,
    );
    expect(noUri.status(), `${endpoint} without uri`).toBe(400);
    expect(await noUri.text()).toContain("uri");
  }
});
