/**
 * Deployments on the catalog API, asserted on the engine wherever the engine is the one that knows.
 *
 * The catalog's own record of a deployment is what somebody asked for; the route on the engine is
 * what happened. Every case here that can be read either way is read on the engine — an undeploy is
 * asserted by the route going 404, and a redeploy by the response changing — because the catalog's
 * status is written before the engine has done anything with it.
 *
 * Chains are called on the engine directly and never through nginx. No `location` matches
 * `/routes/`, so a request through the proxy falls through to the SPA and answers 200 with
 * `index.html` whatever the chain did.
 *
 * Six shapes measured rather than assumed:
 *
 * - `POST .../deployments` **needs `domain`**. Without it the deployment is created, answers 200,
 *   and is invisible to every engine forever. `Catalog.deploy` sends it.
 * - `GET /v1/catalog/runtime-deployments` is a **dict keyed by chain id**, so "the chains still
 *   missing" is a set difference over its keys and never a filter over rows.
 * - Deploying a second snapshot of the same chain **replaces** the first. One deployment row
 *   survives, not two.
 * - Across that replacement the route **stays addressable**, and that is the whole of the promise.
 *   The new context is started before the superseded one is stopped, so no sample answers 404 and
 *   the marker flips partway through. It is not a promise that every sample answers 200: there is
 *   no drain handshake, and a call that resolves a suspended consumer — or that is still executing
 *   when the superseded context stops — answers `500 QIP-0001`, which is indistinguishable from a
 *   chain that threw. The redeploy case tolerates that one answer, bounds how many samples may
 *   carry it, and refuses everything else; issue #845 has the mechanism and the reproduction.
 * - Two **chains** sharing one `contextPath` on one domain both reach `DEPLOYED`, and the last one
 *   deployed owns the route. Camel does not treat it as a route conflict, and the second deployment
 *   never reaches `FAILED`.
 * - What is refused is a single snapshot carrying **two triggers on one path**: `409` naming the
 *   path, and in bulk, `207` with `FAILED_DEPLOY` and the same message.
 *
 * The engine-side `FAILED` status is not reachable from here without a broker: on this stack the
 * one `FAILED` deployment got there through an AMQP `IOException`.
 *
 * **The bulk endpoint reaches the engine on its own.** `DeploymentService.bulkCreate` carries
 * `@DeploymentModification`, so Consul's `deployments-update` key moves once after the batch
 * commits and the engine collects every row of it in one round. `POST .../deployments/all`, which
 * deploys one chain to a list of domains, moves the key the same way.
 */
import { test, expect } from "../../support/fixtures.js";
import { DEFAULT_DOMAIN, type Catalog, type RuntimeDeployment } from "../../support/catalog.js";
import { tokenized } from "../../support/run.js";
import { ABSENT_UUID } from "../../support/absent.js";
import {
  addCollidingTrigger,
  createDeployableChain,
  MARKER_HEADER,
  setMarker,
  tokenizedChain,
  type DeployableChain,
} from "../../support/deployable.js";
import { DEPLOY_TIMEOUT } from "../../support/corpus.js";
import { sleep } from "../../support/poll.js";
import type { Env } from "../../env/index.js";
import type { APIRequestContext } from "@playwright/test";

/**
 * How long the route may take to change hands while the redeploy case samples it.
 *
 * Short on purpose, and unrelated to `DEPLOY_TIMEOUT`: the loop below calls the chain until the
 * marker flips, and with no pause a marker that never flips is a minute of requests issued as fast
 * as the engine answers, from every worker at once, against the stack whose redeploy window the
 * case is measuring. 100 ms keeps the sampling fine enough to see a gap of a few hundred
 * milliseconds and caps one worker at ten requests a second.
 */
const SAMPLE_PAUSE = 100;

/**
 * How many samples of the redeploy may answer out of the drain window before the case fails.
 *
 * The window is tolerated rather than pinned, and a tolerance with no bound on the count is not a
 * measurement: a window that got an order of magnitude wider answers the same document every time,
 * so a filter asking only *which* answers came back stays green through it. This bound keeps the
 * measured number meaningful: 31,890 calls across eight redeploys produced no non-200 at all, and
 * the single live sighting this suite has is one sample.
 *
 * Ten is roughly a second of drain at `SAMPLE_PAUSE`, and the switch itself spans between 30 and 45
 * samples on this stack, so the bound is about a quarter of the redeploy. Every full run so far has
 * reported zero. The count goes into `report.json` as a `drain-window` annotation whatever it is, so
 * a window creeping toward the bound is visible before it crosses it.
 */
const DRAIN_SAMPLE_LIMIT = 10;

interface RouteAnswer {
  status: number;
  marker: string | null;
  body: string;
}

/**
 * Whether a sample is the engine's answer for a route that is not serving right now.
 *
 * `CustomErrorController` maps every chain-URI error status except 404 and 405 to `QIP-0001` at
 * HTTP 500, so a suspended consumer's 503 and a genuine chain failure reach the caller as the same
 * document. That is a filed platform defect, and it is why this reads the code out of the body
 * rather than accepting any 500: the status alone would also accept a chain that broke.
 */
function isDrainAnswer(answer: RouteAnswer): boolean {
  if (answer.status !== 500) return false;
  try {
    return (JSON.parse(answer.body) as { code?: string }).code === "QIP-0001";
  } catch {
    return false;
  }
}

/**
 * Calls the chain on its route and reads the marker off the response.
 *
 * Named for what it does rather than `callChain`, which `support/sessions.ts` exports with a
 * different signature: that one takes a URL and sends the correlation header a session lookup needs,
 * and no case here looks up a session.
 */
async function callDeployedChain(
  request: APIRequestContext,
  env: Env,
  chain: DeployableChain,
  body: unknown = { ping: 1 },
): Promise<RouteAnswer> {
  // The content type is load-bearing: without one the servlet reads the body as form data and the
  // chain fails with "Invalid parameter, expected to be a pair", which reads as a broken chain.
  const response = await request.post(env.chainUrl(chain.contextPath), {
    headers: { "Content-Type": "application/json" },
    data: body as object,
  });
  return {
    status: response.status(),
    marker: response.headers()[MARKER_HEADER] ?? null,
    body: await response.text(),
  };
}

/**
 * Waits until the engine reports the chain `DEPLOYED`, and answers with the row it reported.
 *
 * Named apart from `waitForDeployed` in `support/corpus.ts`, which it used to shadow: that one takes
 * a list of seeded chains and answers nothing, this one takes a single chain and answers its row, so
 * two readers of the same name got two different functions.
 *
 * The row is captured inside the poll rather than re-read after it. A re-read can find the row
 * already pruned, and indexing the empty result reaches the caller as a `TypeError` on a line that
 * looks unrelated instead of as an assertion naming the chain.
 */
async function deploymentRowOf(catalog: Catalog, chain: DeployableChain): Promise<RuntimeDeployment> {
  let deployed: RuntimeDeployment | undefined;
  await expect
    .poll(
      async () => {
        deployed = (await catalog.runtimeDeploymentsOf(chain.id)).find(
          (row) => row.status === "DEPLOYED",
        );
        return deployed?.status ?? "not-reported";
      },
      { timeout: DEPLOY_TIMEOUT, message: `chain ${chain.name} never reached DEPLOYED` },
    )
    .toBe("DEPLOYED");
  if (deployed === undefined) {
    throw new Error(`chain ${chain.name} was reported DEPLOYED and then carried no row`);
  }
  return deployed;
}

/** Snapshot and deploy in one step, which is what every case here needs before it can assert. */
async function deployChain(catalog: Catalog, chain: DeployableChain) {
  const snapshot = await catalog.createSnapshot(chain.id);
  return { snapshot, deployment: await catalog.deploy(chain.id, snapshot.id) };
}

test("a deployed chain is reported by the engine and answers on its route", { tag: ["@catalog", "@engine", "@tier1"] }, async ({ catalog, env, folder, run, request }) => {
  const chain = await tokenizedChain(catalog, run, {
    prefix: "deploy",
    what: "live",
    parentId: folder.id,
  });

  const { snapshot, deployment } = await deployChain(catalog, chain);
  expect(deployment.domain, "the deployment records the domain it was sent to").toBe(DEFAULT_DOMAIN);

  const reported = await deploymentRowOf(catalog, chain);
  expect(reported.deploymentInfo).toMatchObject({
    deploymentId: deployment.id,
    chainId: chain.id,
    chainName: chain.name,
    snapshotId: snapshot.id,
    snapshotName: "V1",
  });
  expect(reported.host, "the engine reports which host serves the chain").toBeTruthy();

  // The catalog's own row carries the same status, keyed by engine host rather than flat. The row
  // is named before it is indexed, so a chain the catalog no longer lists fails as a missing id.
  const rows = await catalog.listDeployments(chain.id);
  expect(rows.map((each) => each.id)).toEqual([deployment.id]);
  expect(Object.values(rows[0].runtime?.states ?? {}).map((state) => state.status)).toEqual([
    "DEPLOYED",
  ]);
  expect((await catalog.getDeployment(chain.id, deployment.id)).snapshotId).toBe(snapshot.id);

  // And the route itself, which is the only assertion here the catalog cannot fake. An HTTP
  // trigger with nothing behind it echoes the request, and the header modification is what marks
  // which version of the chain answered.
  const answer = await callDeployedChain(request, env, chain, { ping: "live" });
  expect(answer.status).toBe(200);
  expect(answer.marker).toBe("live");
  expect(JSON.parse(answer.body)).toEqual({ ping: "live" });
});

test("undeploy is asserted by the route rather than by the catalog's record of it", { tag: ["@catalog", "@engine", "@tier1"] }, async ({ catalog, env, folder, run, request }) => {
  const chain = await tokenizedChain(catalog, run, {
    prefix: "deploy",
    what: "undeploy",
    parentId: folder.id,
  });
  const { deployment } = await deployChain(catalog, chain);
  await deploymentRowOf(catalog, chain);

  // While the route is live a GET is a 405, because the trigger restricts the method. That is the
  // reading that separates "the route is gone" from "the route is there and refused the verb" —
  // and a 404 assertion alone cannot tell the two apart.
  const url = env.chainUrl(chain.contextPath);
  expect((await request.get(url)).status()).toBe(405);

  await catalog.undeploy(chain.id, deployment.id);
  expect(await catalog.listDeployments(chain.id), "the catalog drops the row at once").toEqual([]);

  await expect
    .poll(async () => (await request.get(url)).status(), {
      timeout: DEPLOY_TIMEOUT,
      message: `the route for ${chain.name} still answers after the undeploy`,
    })
    .toBe(404);

  // The runtime view lags the route it describes, and by seconds: measured, the chain was still
  // keyed `DEPLOYED` there after the route had already gone 404. `RuntimeDeploymentService` prunes
  // on what the engine reports back, so this is a poll and never a read.
  await expect
    .poll(async () => (await catalog.runtimeDeploymentsOf(chain.id)).length, {
      timeout: DEPLOY_TIMEOUT,
      message: `the runtime view still keys ${chain.name} after the undeploy`,
    })
    .toBe(0);
});

test("a redeploy swaps the response and the route never goes missing", { tag: ["@catalog", "@engine", "@tier1"] }, async ({ catalog, env, folder, run, request }) => {
  const chain = await tokenizedChain(catalog, run, {
    prefix: "deploy",
    what: "redeploy",
    parentId: folder.id,
    marker: "before",
  });
  const { deployment: first } = await deployChain(catalog, chain);
  await deploymentRowOf(catalog, chain);
  expect((await callDeployedChain(request, env, chain)).marker).toBe("before");

  await setMarker(catalog, chain, "after");
  const { deployment: second } = await deployChain(catalog, chain);
  expect(second.id).not.toBe(first.id);

  // Sample the route across the switch rather than after it. Whether there is a window in which the
  // route is gone is the contract this case exists to record, and a single call after the fact
  // cannot see one. Measured: there is none. Of 31,890 calls across eight redeploys, none answered
  // 404 and none answered anything but 200 — though the assertions below demand only the first of
  // those, for the reason stated at the second. The pause paces the sampling; see SAMPLE_PAUSE.
  const samples: RouteAnswer[] = [];
  const deadline = Date.now() + DEPLOY_TIMEOUT;
  while (Date.now() < deadline) {
    const answer = await callDeployedChain(request, env, chain);
    samples.push(answer);
    if (answer.marker === "after") break;
    await sleep(SAMPLE_PAUSE);
  }

  expect(samples.at(-1)?.marker, "the new response never arrived").toBe("after");
  // The guard on everything below, and it has to come first: the loop breaks on the first sample
  // carrying "after", so a switch that landed before the first call leaves `samples` one entry
  // long, and every reading below is then taken over a single post-switch response. Read off the
  // first sample that carried a marker rather than off `samples[0]`, because a sample answered from
  // inside the drain window carries none.
  expect(
    samples.map((each) => each.marker).filter((each) => each !== null)[0],
    "sampling began after the route had already changed hands, so this case measured whatever the " +
      "timing allowed rather than the switch it claims to span",
  ).toBe("before");

  // The route stays addressable across the switch, and that is the half the engine does promise:
  // `IntegrationRuntimeService.update` starts the new context before it stops the superseded one,
  // both consumers sit in one servlet registry under distinct `servletCustomId` values, and
  // `matchNewerConsumer` prefers the newer one. A 404 here means the route was gone, which is the
  // regression this case exists to catch.
  expect(
    samples.filter((each) => each.status === 404),
    "the route disappeared during the redeploy",
  ).toEqual([]);

  // What the engine does not promise is that every sample answers 200. There is no drain handshake:
  // a request that resolves the superseded consumer while it is suspended, or that is still
  // executing when its context stops, answers `500 QIP-0001` — the same body a chain that threw
  // would produce. Issue #845 has the mechanism and the reproduction. The second of the two paths
  // it describes is the one that reaches a redeploy: `stopSupersededContext` tears the old context
  // down under whatever is still executing on it, and `CamelServlet.service` answers that request
  // 500. This suite has seen it once, against 31,890 redeploy calls that produced no non-200: rare,
  // not impossible, which is why the window is tolerated here rather than pinned with `test.fail()`.
  //
  // Tolerated is not unread. A sample that is neither a 200 nor that one answer fails the case, so
  // a redeploy that starts answering 502, or 500 with some other code, is still a regression, and
  // the assertion after this one bounds how much of the redeploy the window may swallow.
  expect(
    samples.filter((each) => each.status !== 200 && !isDrainAnswer(each)),
    "a sample answered something other than the chain and the known drain window",
  ).toEqual([]);

  // How wide the window was, bounded and recorded. See DRAIN_SAMPLE_LIMIT.
  const drains = samples.filter(isDrainAnswer);
  test.info().annotations.push({
    type: "drain-window",
    description: `${drains.length} of ${samples.length} samples answered 500 QIP-0001`,
  });
  expect(
    drains.length,
    `${drains.length} of ${samples.length} samples answered the drain window, which is wider than ` +
      "the one issue #845 measured — the tolerance is for a window, not for a redeploy " +
      "that spends most of itself unserved",
  ).toBeLessThanOrEqual(DRAIN_SAMPLE_LIMIT);

  // Every answered call carries the marker. This is what `not.toContain(null)` used to say, narrowed
  // to the 200s: the drain answer above carries no marker by construction, and reading it as one
  // would make this assertion pass on a route that answered nothing but drain windows.
  expect(
    samples.filter((each) => each.status === 200 && each.marker === null),
    "a sample answered 200 without the marker the chain sets",
  ).toEqual([]);

  // The redeploy replaces rather than adds — but not at the moment of the call. The superseded row
  // survives until the engine reports which deployments it is actually running and
  // `RuntimeDeploymentService` prunes the rest, so both rows are visible for a few seconds and a
  // straight read here fails on roughly every other run.
  await expect
    .poll(async () => (await catalog.listDeployments(chain.id)).map((each) => each.id), {
      timeout: DEPLOY_TIMEOUT,
      message: `the superseded deployment of ${chain.name} was never pruned`,
    })
    .toEqual([second.id]);
  expect(
    (await catalog.runtimeDeploymentsOf(chain.id)).map((each) => each.deploymentInfo.deploymentId),
  ).toEqual([second.id]);
});

test("a second chain deploying onto a context path another chain holds is refused, and the first keeps the route", { tag: ["@catalog", "@engine", "@tier1"] }, async ({ catalog, env, folder, run, request }) => {
  const first = await tokenizedChain(catalog, run, {
    prefix: "deploy",
    what: "shared-first",
    parentId: folder.id,
    marker: "first",
  });
  const second = await createDeployableChain(catalog, {
    name: tokenized(run, "deploy-shared-second"),
    parentId: folder.id,
    // The same path as the first chain, deliberately.
    contextPath: first.contextPath,
    marker: "second",
  });

  await deployChain(catalog, first);
  await deploymentRowOf(catalog, first);
  expect((await callDeployedChain(request, env, first)).marker).toBe("first");

  // Until #840 both chains deployed and the engine servlet silently handed the route to whichever
  // was deployed last. The deploy check compared external and private routes only, so two internal
  // triggers on one path in one domain never met. It now compares every trigger of the other chains
  // in the domain, and the refusal names the chain already holding the path.
  const snapshot = await catalog.createSnapshot(second.id);
  const refused = await catalog.raw("post", `/v1/catalog/chains/${second.id}/deployments`, {
    snapshotId: snapshot.id,
    domain: DEFAULT_DOMAIN,
  });
  expect(refused.status()).toBe(409);
  expect(
    ((await refused.json()) as { errorMessage?: string }).errorMessage,
    "the refusal names the chain that holds the path, not the path",
  ).toContain(first.name);

  // Nothing was deployed for the second chain, and the first still owns the route. Mapped rather
  // than indexed: a chain the runtime view has stopped keying is an empty array, and that reads as
  // an assertion here instead of as a TypeError.
  expect(await catalog.listDeployments(second.id)).toEqual([]);
  expect(
    (await catalog.runtimeDeploymentsOf(second.id)).map((row) => row.status),
    "the runtime view of the refused chain",
  ).toEqual([]);
  expect(
    (await catalog.runtimeDeploymentsOf(first.id)).map((row) => row.status),
    "the runtime view of the first chain",
  ).toEqual(["DEPLOYED"]);
  expect((await callDeployedChain(request, env, first)).marker).toBe("first");
});

test("a snapshot whose triggers collide on one path is refused with 409 naming the path", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const chain = await tokenizedChain(catalog, run, {
    prefix: "deploy",
    what: "collision",
    parentId: folder.id,
  });
  await addCollidingTrigger(catalog, chain);

  // The collision is not caught at compile time: the snapshot builds.
  const snapshot = await catalog.createSnapshot(chain.id);
  expect(snapshot.id).toBeTruthy();

  const refused = await catalog.raw("post", `/v1/catalog/chains/${chain.id}/deployments`, {
    snapshotId: snapshot.id,
    domain: DEFAULT_DOMAIN,
  });
  expect(refused.status()).toBe(409);
  const body = (await refused.json()) as { errorMessage?: string; serviceName?: string };
  expect(body.serviceName).toBe("Catalog");
  expect(body.errorMessage, "the refusal names the path it refused").toContain(chain.contextPath);

  // And nothing was deployed, which is the half a status code does not say.
  expect(await catalog.listDeployments(chain.id)).toEqual([]);
});

test("bulk deploy takes the snapshot itself and reports one row per chain", { tag: ["@catalog", "@engine", "@tier1"] }, async ({ catalog, env, folder, run, request }) => {
  const one = await tokenizedChain(catalog, run, {
    prefix: "deploy",
    what: "bulk-one",
    parentId: folder.id,
    marker: "one",
  });
  const two = await tokenizedChain(catalog, run, {
    prefix: "deploy",
    what: "bulk-two",
    parentId: folder.id,
    marker: "two",
  });

  const { status, rows } = await catalog.bulkDeploy([one.id, two.id]);

  expect(status, "200 while every row succeeded").toBe(200);
  expect(rows.map((row) => row.chainId).sort()).toEqual([one.id, two.id].sort());
  for (const row of rows) {
    expect(row.status).toBe("CREATED");
    expect(row.domain.name).toBe(DEFAULT_DOMAIN);
    expect(row.errorMessage).toBeUndefined();
  }

  // The snapshot the bulk call took for itself is a real one.
  expect((await catalog.listSnapshots(one.id)).map((each) => each.name)).toEqual(["V1"]);

  await deploymentRowOf(catalog, one);
  await deploymentRowOf(catalog, two);
  expect((await callDeployedChain(request, env, one)).marker).toBe("one");
  expect((await callDeployedChain(request, env, two)).marker).toBe("two");
});

test("bulk deploy answers 207 for a chain it could not deploy, and drops an id it cannot find", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const chain = await tokenizedChain(catalog, run, {
    prefix: "deploy",
    what: "bulk-collision",
    parentId: folder.id,
  });
  await addCollidingTrigger(catalog, chain);

  const failed = await catalog.bulkDeploy([chain.id]);
  expect(failed.status, "207 as soon as one row failed").toBe(207);
  expect(failed.rows).toHaveLength(1);
  expect(failed.rows[0]).toMatchObject({ chainId: chain.id, status: "FAILED_DEPLOY" });
  expect(failed.rows[0].errorMessage).toContain(chain.contextPath);
  expect(await catalog.listDeployments(chain.id)).toEqual([]);

  // An id nothing answers to is neither an error nor a row: the call answers 200 with an empty
  // list, so a caller counting rows against ids it sent is the only one who ever finds out.
  const unknown = await catalog.bulkDeploy([ABSENT_UUID]);
  expect(unknown.status).toBe(200);
  expect(unknown.rows).toEqual([]);
});

test("deploying a chain to a list of domains creates the deployment and tells the engine", { tag: ["@catalog", "@engine", "@tier2"] }, async ({ catalog, env, folder, run, request }) => {
  const chain = await tokenizedChain(catalog, run, {
    prefix: "deploy",
    what: "all",
    parentId: folder.id,
    marker: "all",
  });
  const snapshot = await catalog.createSnapshot(chain.id);

  const created = await catalog.deployAll(chain.id, [{ snapshotId: snapshot.id, domain: DEFAULT_DOMAIN }]);

  expect(created).toHaveLength(1);
  expect(created[0]).toMatchObject({ chainId: chain.id, snapshotId: snapshot.id, domain: DEFAULT_DOMAIN });
  expect((await catalog.listDeployments(chain.id)).map((each) => each.id)).toEqual([created[0].id]);

  // Like the bulk endpoint, this one tells the engine itself.
  const reported = await deploymentRowOf(catalog, chain);
  expect(reported.deploymentInfo).toMatchObject({ deploymentId: created[0].id, snapshotId: snapshot.id });
  expect((await callDeployedChain(request, env, chain)).marker).toBe("all");

  expect(await catalog.deployAll(chain.id, []), "an empty list deploys nothing").toEqual([]);
});
