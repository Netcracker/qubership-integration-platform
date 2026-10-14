/**
 * A chain built through the API and the same chain restored from its own export run identically.
 *
 * Every other spec in this suite starts from one of the two paths and never compares them, and the
 * defect that hides in the gap is a real one this product shipped: an API-built chain worked and
 * the same chain arriving as a document did not, because the two paths disagreed about what an
 * absent property defaults to. A suite that only ever imports fixtures cannot see it, and neither
 * can one that only ever builds through the API.
 *
 * Note the irony this case exists to correct. The suite's rule that a fixture document spells out
 * its full property set is the right workaround for that class of defect **and** is exactly what
 * makes every other spec here blind to it forever. So this one builds the chain through
 * `POST /v1/chains` plus element create and transfer, lets the exporter write whatever it writes,
 * and asserts that what comes back behaves the same.
 *
 * The two halves run in sequence rather than side by side, and that is deliberate: the export
 * carries the chain's own id, so an import while the original is live is an **update** and proves
 * nothing (rule 5), and rewriting the id to get a second copy would mean re-zipping the archive,
 * which rule 6 forbids for a reason of its own. Deleting the original first keeps the original
 * bytes and makes the import a create, at the cost of driving the two one after the other — which
 * costs the comparison nothing, because the request is the same both times.
 *
 * The chain is deployed and called, so this file is the expensive one in `specs/api/`: two deploys
 * at roughly three seconds each. It stays here rather than in `specs/env/` because it restarts
 * nothing, which is what that project's placement rule is actually about.
 */
import { test, expect } from "../../support/fixtures.js";
import type { Catalog, ImportResult } from "../../support/catalog.js";
import { tokenized } from "../../support/run.js";
import { createDeployableChain, MARKER_HEADER, type DeployableChain } from "../../support/deployable.js";
import { DEPLOY_TIMEOUT, SEED_LOGGING } from "../../support/corpus.js";
import { Sessions, callChain, trace, type RecordedSession } from "../../support/sessions.js";
import { releaseChains } from "../../support/cleanup.js";
import type { Env } from "../../env/index.js";
import type { APIRequestContext } from "@playwright/test";

/** What the request produced, in the terms the two paths are compared on. */
interface Answer {
  status: number;
  body: string;
  marker: string | null;
  /** `elementName camelName executionStatus` per step, depth first — the trace, not its outline. */
  steps: string[];
}

function stepsOf(session: RecordedSession): string[] {
  return trace(session).map(
    (each) => `${each.elementName} ${each.camelName} ${each.executionStatus}`,
  );
}

/**
 * What the route answers to a `GET`.
 *
 * The trigger restricts the method, so a live route answers **405** and a route that is not there
 * answers **404**. That distinction is the whole gate: it separates "deployed" from "serving"
 * without running the chain once, and `DEPLOYED` on its own means neither — it is what the catalog
 * accepted, and a chain whose listener never connects reports it indefinitely.
 *
 * The call goes through the `request` fixture rather than the global `fetch`. Only the fixture's
 * traffic reaches the `retain-on-failure` trace, and a route poll is the first thing a reader of a
 * red run here wants to see. `waitForRoutes` from `support/corpus.ts` is the shared version of this
 * and does not fit: it takes a `SeedChain`, it polls with the global `fetch`, and it cannot gate on
 * the deployment id this file has to gate on.
 */
async function routeStatus(
  request: APIRequestContext,
  env: Env,
  chain: DeployableChain,
): Promise<number> {
  return await request
    .get(env.chainUrl(chain.contextPath), { failOnStatusCode: false })
    .then((response) => response.status())
    .catch(() => 0);
}

/**
 * Snapshots, deploys, and waits until the engine is serving **this** deployment.
 *
 * The deployment id is the gate rather than a 405, and that is the whole correction. The import
 * restores the pre-export snapshot together with its deployment, so a route can already be
 * answering 405 when the second half of the case deploys — the poll returns on the restored route
 * and the case then compares the chain against a deployment it did not make. Measured: a deliberate
 * mutation reproduced in 3 attempts out of 6 for that reason.
 */
async function deployAndServe(
  catalog: Catalog,
  request: APIRequestContext,
  env: Env,
  chain: DeployableChain,
): Promise<void> {
  const snapshot = await catalog.createSnapshot(chain.id);
  const deployment = await catalog.deploy(chain.id, snapshot.id);
  await expect
    .poll(
      async () =>
        (await catalog.runtimeDeploymentsOf(chain.id))
          .filter((row) => row.status === "DEPLOYED")
          .map((row) => row.deploymentInfo.deploymentId),
      {
        timeout: DEPLOY_TIMEOUT,
        message: `the engine never reported deployment ${deployment.id} of ${chain.name}`,
      },
    )
    .toContain(deployment.id);
  // And then the route, because DEPLOYED is what the catalog accepted rather than what is serving.
  await expect
    .poll(() => routeStatus(request, env, chain), {
      timeout: DEPLOY_TIMEOUT,
      message: `the route of ${chain.name} never began to answer`,
    })
    .toBe(405);
}

/**
 * Waits until the undeployed route has actually stopped answering.
 *
 * Without this the comparison is vacuous: the engine learns of the undeploy on its next Consul
 * round, so a poll for a serving route straight after one sees the **old** route still answering
 * 405 and returns at once. `e2e/AGENTS.md` owns what that round costs and how it was measured;
 * repeating the number here is how the last stale copy of it got written.
 */
async function waitForRouteGone(
  request: APIRequestContext,
  env: Env,
  chain: DeployableChain,
): Promise<void> {
  await expect
    .poll(() => routeStatus(request, env, chain), {
      timeout: DEPLOY_TIMEOUT,
      message: `the route of ${chain.name} was still answering after the undeploy`,
    })
    .toBe(404);
}

/**
 * How many steps `createDeployableChain`'s chain records.
 *
 * Three, not two: the trigger contributes **`HTTP Trigger`** and **`Validate Request`**, and the
 * header modification one. The floor here used to be two, and being one short made the comparison
 * vacuous rather than merely lenient — a first read that caught 2 of the 3 set the second read's
 * target to 2, the second read then saw all three, and the case reported a divergence between one
 * chain and itself. Measured once in four full runs.
 *
 * Waiting for the session to settle does not close that gap and is already done:
 * `Sessions.byExternalId` polls past `IN_PROGRESS`, and the element documents are indexed
 * separately from the session, so a settled session can still be a step short.
 */
const RECORDED_STEPS = 3;

/** The one request both paths are driven with, and everything observable about what it did. */
async function drive(
  request: APIRequestContext,
  env: Env,
  sessions: Sessions,
  chain: DeployableChain,
  wantedSteps: number,
): Promise<Answer> {
  const { token, response } = await callChain(request, env.chainUrl(chain.contextPath), {
    data: { ping: "equivalence" },
  });
  const session = await sessions.byExternalId(token, { elements: wantedSteps });
  return {
    status: response.status(),
    body: await response.text(),
    marker: response.headers()[MARKER_HEADER] ?? null,
    steps: stepsOf(session),
  };
}

test("a chain from its own export runs the same way the API-built original did", { tag: ["@catalog", "@engine", "@sessions", "@tier1"] }, async ({ catalog, env, folder, request, run, sessions }) => {
  const name = tokenized(run, "equivalence");
  const contextPath = tokenized(run, "equivalence");
  const built = await createDeployableChain(catalog, {
    name,
    parentId: folder.id,
    contextPath,
    marker: "built",
  });

  let bodyFailed = true;
  try {
    // Session recording is off by default — `DeploymentRuntimeProperties` defaults to
    // `SessionsLoggingLevel.OFF` and nothing is written at that level — and the properties travel
    // to the engine with the deployment, so this has to happen before the deploy rather than after.
    await catalog.saveLoggingProperties(built.id, SEED_LOGGING);
    await deployAndServe(catalog, request, env, built);

    // The count both lookups wait for is the chain's whole trace, so a partial read cannot pass for
    // a divergence in either direction.
    const fromApi = await drive(request, env, sessions, built, RECORDED_STEPS);
    expect(fromApi.status).toBe(200);
    expect(fromApi.steps.length, "the API-built chain recorded a trace of another shape").toBe(
      RECORDED_STEPS,
    );

    // The bytes the exporter wrote, imported unchanged. Re-zipping an unpacked tree imports as a
    // no-op that answers success, which is how this assertion would quietly stop asserting.
    const archive = await catalog.exportChain(built.id);

    // Delete before importing, and undeploy first so the route is free when the copy claims it.
    await catalog.undeployAll(built.id);
    await waitForRouteGone(request, env, built);
    await catalog.deleteChain(built.id);
    expect((await catalog.raw("get", `/v1/chains/${built.id}`)).status()).toBe(404);

    const response = await catalog.importChains(archive, `${name}.zip`);
    expect(response.status(), await response.text()).toBe(200);
    const rows = ((await response.json()) as ImportResult).chains ?? [];
    // `CREATED` is the row that says the import built the chain rather than updating one that was
    // already there — which is the whole point of having deleted it first.
    expect(rows.map((each) => `${each.name} ${each.status}`)).toEqual([`${name} CREATED`]);
    expect(rows[0].id).toBe(built.id);

    await catalog.saveLoggingProperties(built.id, SEED_LOGGING);
    await deployAndServe(catalog, request, env, built);
    const fromDocument = await drive(request, env, sessions, built, fromApi.steps.length);

    // The comparison, and it is deliberately over what the chain *did* rather than over what the
    // catalog stored: the defect this exists for is one where both records look identical and the
    // two chains behave differently.
    expect(fromDocument.status, "the imported chain answered a different status").toBe(fromApi.status);
    expect(fromDocument.body, "the imported chain answered a different body").toBe(fromApi.body);
    expect(fromDocument.marker, "the header modification did not survive the round trip").toBe(
      fromApi.marker,
    );
    expect(fromDocument.steps, "the imported chain ran a different trace").toEqual(fromApi.steps);
    bodyFailed = false;
  } finally {
    // `releaseChains` rather than two hops of this spec's own, and it is the same policy for the
    // same reasons: the Consul key first, because a `finally` cut short by the test timeout may
    // never reach the second hop and the key is the one thing that stops being addressable; and the
    // chain moved to the root when that delete fails, because until the import this chain sits in
    // the worker folder and the `folder` fixture cascades that folder away after the worker's last
    // test. Keeping a chain the cascade is about to take keeps nothing. Both logging writes above
    // are under one id, so one delete covers them.
    const seeded = [{ id: built.id, name }];
    // `false` where `releaseChains` takes `bodyFailed`, so the failure comes back here instead of
    // being logged inside: this spec has a hop of its own to skip over exactly that failure.
    const released = await releaseChains(catalog, seeded, false).then(
      () => null,
      (cause: unknown) => (cause instanceof Error ? cause : new Error(String(cause))),
    );

    if (released === null) {
      // Deleted rather than left to a sweep, and this is where this spec parts from the two runtime
      // ones: after the import the chain sits at the root, where the worker folder cascade never
      // reaches it. The key is gone, so the id it was filed under is free to go too.
      await catalog.deleteChain(built.id).catch((cause: unknown) => {
        console.error(`[cleanup] ${name} was not deleted: ${String(cause)}`);
      });
    } else if (bodyFailed) {
      // A teardown error raised over a real assertion failure replaces the finding with its own, and
      // the reader then has the leak instead of the reason for it.
      console.error(`[cleanup] ${released.message}`);
    } else {
      throw released;
    }
  }
});
