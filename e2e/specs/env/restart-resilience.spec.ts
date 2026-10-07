/**
 * A deployed chain keeps working after the engine or the catalog restarts.
 *
 * The `env` project exists to restart services, and until this file its only spec restarted one to
 * flip an export flag. Nothing asserted the property a release actually depends on: for an
 * orchestration platform, "the chain still works after the process that runs it comes back" is the
 * release question immediately after "the chain works". The two halves of it are not the same
 * property and neither implies the other:
 *
 *   - the **engine** restarting drops every route it holds in memory, and it has to rebuild them
 *     from Consul and the catalog without anyone redeploying anything;
 *   - the **catalog** restarting must not disturb what is already deployed, and the engine has to
 *     keep serving the whole time the catalog is unreachable.
 *
 * Both are asserted over the whole seeded corpus rather than over one chain, because "a chain
 * answers" is satisfied by whichever chain the spec happened to name and the interesting failure is
 * the one that loses some of them. The corpus set is checked against the fixture directories on
 * disk first, so the claim cannot quietly shrink to the chains that survived.
 *
 * The restart goes through `env.restart`, which recreates the container, waits for its health
 * check, and reloads the proxy — nginx resolves `proxy_pass http://engine:8080` once, at config
 * load, so a recreated backend otherwise strands every `/api/` address for the rest of the run.
 * The reload is part of the seam rather than of this spec, and each half ends by asserting that the
 * engine's `/api/` surface answers — which is what goes red the day the seam stops reloading it.
 * The engine's, in both halves, because the reload polls the **catalog's** route to 200 before it
 * returns: a catalog assertion here would restate the seam instead of checking it.
 */
import { test, expect } from "../../support/fixtures.js";
import {
  readCorpusState,
  seedChain,
  waitForRoutes,
  type SeedChain,
  type SeededCorpus,
} from "../../support/corpus.js";
import { callChain } from "../../support/sessions.js";
import { sleep } from "../../support/poll.js";
import { corpusFixtureNames } from "../../fixtures/templating.js";
import type { Catalog } from "../../support/catalog.js";
import { DEFAULT_DOMAIN } from "../../support/catalog-types.js";
import { notTheKnownDefect } from "../../support/known-defect.js";
import type { Env, ServiceRole } from "../../env/index.js";
import { RESTART_TIMEOUT } from "./constants.js";

/**
 * How long the routes may take to answer again once `restart` has returned.
 *
 * Measured on this stack over the 15-chain corpus: every route answered again **0.2 s** after the
 * engine container reported healthy, and 44.1 s after the restart began. The recreate and the health
 * check are all but that fifth of a second, because the engine rebuilds its routes before it reports
 * healthy. So this budget covers a machine where it does not, and the gap between 0.2 s and 30 s is
 * deliberate room rather than a measurement. It stays far under the 120 s a Playwright test gets: a
 * route still missing half a minute past a healthy container is a finding.
 */
const RECOVERY_BUDGET = 30_000;


/** `fixture deploymentId snapshotId` per deployed chain — the identity a redeploy would change. */
async function deploymentRows(
  catalog: Catalog,
  chains: readonly SeedChain[],
): Promise<string[]> {
  const rows: string[] = [];
  for (const chain of chains) {
    for (const row of await catalog.listDeployments(chain.id)) {
      rows.push(`${chain.fixture} ${row.id} ${row.snapshotId}`);
    }
  }
  return rows.sort();
}

/**
 * The same identity as the engine at `host` reports it back, which is the half a restart can move.
 *
 * `listDeployments` reads catalog rows in Postgres, and an engine restart writes none of them: a
 * comparison over that reading is equal whatever the engine did with its routes, including losing
 * them. `RuntimeDeploymentService` keys this view on what each engine reports it is running, so a
 * chain the engine came back without has no row here, and one it rebuilt from a different snapshot
 * has a different one. The rows are those of one address, because the registration of a replaced
 * engine pod outlives it (see the case after the engine restart).
 */
async function runtimeRows(catalog: Catalog, chains: readonly SeedChain[], host: string): Promise<string[]> {
  const rows: string[] = [];
  for (const chain of chains) {
    for (const row of (await catalog.runtimeDeploymentsOf(chain.id)).filter((each) => each.host === host)) {
      rows.push(
        `${chain.fixture} ${row.deploymentInfo.deploymentId} ${row.deploymentInfo.snapshotId} ${row.status}`,
      );
    }
  }
  return rows.sort();
}

/**
 * `status content-type` from the `/api/` surface, which is the shape that can catch the SPA.
 *
 * No nginx `location` claiming a path means the request falls through to the single-page app, which
 * answers **200 text/html** — so a bare status check reads a stranded proxy as a healthy one.
 */
async function proxyAnswer(env: Env, role: ServiceRole, servicePath: string): Promise<string> {
  const response = await fetch(env.apiUrl(role, servicePath)).catch(() => null);
  if (!response) return "no response";
  const type = (response.headers.get("content-type") ?? "").split(";")[0];
  return `${response.status} ${type || "no body"}`;
}

/**
 * The two answers the engine's own `/api/` surface gives for `live-exchanges`, and nothing else.
 *
 * `LiveExchangesController.getLiveExchanges` returns `ResponseEntity.noContent()` while the list is
 * empty and `ResponseEntity.ok(result)` the moment it is not, so pinning this read to **204 no
 * body** was a coin toss rather than an assertion: measured, roughly one run in three read
 * **200 `application/json`** off an exchange the engine had not yet deregistered. Both halves of
 * this spec drive chains immediately before the read — the engine half calls `http-echo`, the
 * catalog half runs sixty-odd calls through the downtime — so the exchange list is the one thing
 * here that is legitimately unsettled at that instant.
 *
 * Polling for the 204 would only move the coin toss into a timeout, and it would assert a property
 * this spec never claimed: nothing here says the engine is idle, and an exchange is free to outlive
 * the call that started it. What the read is for is the **proxy**, and every failure it exists to
 * catch lands outside this pair. An unclaimed path is answered by the SPA as 200 `text/html`. An
 * upstream whose address Docker has since reassigned answers 502, or — measured, and recorded on
 * `Env.reloadProxy` — 500 with a *Session Management* error body. A container that is gone answers
 * nothing at all. `specs/api/api-prefixes.spec.ts` reads the same row the same way and calls it
 * state-dependent for the same reason.
 */
const ENGINE_ANSWERS = ["204 no body", "200 application/json"];

/** Asserts the engine's own `/api/` surface answered, whatever its exchange list happens to hold. */
async function expectEngineApiSurface(env: Env, why: string): Promise<void> {
  const answer = await proxyAnswer(env, "engine", "/v1/engine/live-exchanges");
  expect(ENGINE_ANSWERS, `${why} — the engine's /api/ surface answered ${answer}`).toContain(answer);
}

/** The corpus, with the set it holds asserted against the fixtures on disk. */
function corpusOfEveryFixture(): SeededCorpus {
  const corpus = readCorpusState();
  expect(
    corpus.chains.map((each) => each.fixture).sort(),
    "the corpus holds fewer chains than there are fixtures, so 'every chain answers again' would " +
      "be a claim about whatever survived",
  ).toEqual(corpusFixtureNames());
  return corpus;
}

// The catalog restart reads the deployments the engine restart left, and a failed engine restart
// makes the second case's reading meaningless rather than red.
test.describe.configure({ mode: "serial" });

/**
 * The engine's address before and after its restart, and the hosts the catalog listed once the new
 * one registered, which the case after it reads.
 */
let engineRestart: { before: string; after: string; listed: string[] } | undefined;

test("every seed chain answers again after the engine restarts, with nothing redeployed", { tag: ["@engine", "@infra", "@tier1"] }, async ({ catalog, env, request }) => {
  test.setTimeout(RESTART_TIMEOUT);
  const corpus = corpusOfEveryFixture();
  const chains = corpus.chains;

  // What the engine reports it is running, and that every route serves *now*: a route missing after
  // the restart is then the restart's doing rather than damage an earlier project left.
  const address = await env.address("engine");
  const before = await runtimeRows(catalog, chains, address);
  expect(before.length, "the corpus is not deployed, so a restart cannot be shown to survive").toBe(
    chains.length,
  );
  await waitForRoutes(env, chains);

  const began = Date.now();
  await env.restart("engine");
  const healthy = Date.now();
  const after = await env.address("engine");
  // Read now rather than in the next case: the registration a replaced pod leaves expires about a
  // minute after the new pod is healthy, and this case spends up to a minute of that below.
  let listed: string[] = [];
  await expect
    .poll(
      async () => {
        listed = [...((await catalog.engineHosts())[DEFAULT_DOMAIN] ?? [])].sort();
        return listed.includes(after);
      },
      { timeout: RECOVERY_BUDGET, message: `the restarted engine at ${after} never registered` },
    )
    .toBe(true);
  engineRestart = { before: address, after, listed };

  // Nothing between the restart and this poll deploys anything. That is the assertion: the engine
  // rebuilt its routes from Consul and the catalog on its own.
  await waitForRoutes(env, chains, RECOVERY_BUDGET);
  console.log(
    `[restart] engine: ${chains.length} seed routes answered again ` +
      `${((Date.now() - healthy) / 1000).toFixed(1)} s after the container reported healthy, ` +
      `${((Date.now() - began) / 1000).toFixed(1)} s after the restart began`,
  );

  // Polled rather than read: the view is rebuilt from the engine's own report and the report
  // arrives after the routes do, so a chain can be serving and still be momentarily unkeyed here.
  await expect
    .poll(() => runtimeRows(catalog, chains, after), {
      timeout: RECOVERY_BUDGET,
      message:
        "the engine reports a different deployment or snapshot than it did before the restart, so " +
        "the routes came back because something redeployed them rather than because the engine " +
        "restored what it had — or it came back without one of them",
    })
    .toEqual(before);

  // A route answering 405 is a listener rather than a working chain, so one chain is driven end to
  // end: the trigger, the element after it, and the body that comes back.
  const echo = seedChain(corpus, "http-echo");
  const { response } = await callChain(request, env.chainUrl(echo.contextPath), {
    data: { ping: "after-engine-restart" },
  });
  expect(response.status()).toBe(200);
  expect(JSON.parse(await response.text()), "the restored route runs a different chain").toEqual({
    ping: "after-engine-restart",
  });

  await expectEngineApiSurface(
    env,
    "the proxy still points at the container the restart replaced, and every /api/ assertion after " +
      "this spec would read a stale address",
  );
});

// A replaced pod comes back under a new address. Its old registration stays until Consul expires
// the session, measured at 79 s after the new pod was healthy, so the listing the restart case took
// as soon as the new pod registered still holds it (`docs/product-defects.md`, "A replaced engine
// pod stays registered for up to two minutes on Kubernetes"). A Compose container that keeps its
// address shows nothing.
test("an engine that restarted is listed under its new address alone", { tag: ["@catalog", "@engine", "@infra", "@tier1"] }, () => {
  if (!engineRestart) throw new Error("the engine restart case recorded no addresses");
  const { before, after, listed } = engineRestart;
  test.fail(before !== after, "a replaced engine pod stays registered until its Consul session expires");

  if (before !== after && listed.join() !== [before, after].sort().join() && listed.join() !== after) {
    notTheKnownDefect(`the catalog lists ${listed.join(", ") || "no engine"}, where ${before} was replaced by ${after}`);
  }
  expect(listed, "the catalog lists the new address alone: delete the test.fail() annotation").toEqual([after]);
});

test("the catalog restarts without disturbing what is deployed, and the engine keeps answering", { tag: ["@catalog", "@engine", "@infra", "@tier1"] }, async ({ catalog, env }) => {
  test.setTimeout(RESTART_TIMEOUT);
  const corpus = corpusOfEveryFixture();
  const chains = corpus.chains;
  const before = await deploymentRows(catalog, chains);
  const echo = seedChain(corpus, "http-echo");
  const url = env.chainUrl(echo.contextPath);

  // Driven *during* the downtime rather than after it. The engine holds its routes in memory, and
  // the assertion is that it never needs the catalog to serve one. A call made after the catalog is
  // back cannot tell that apart from a route that stopped and came back.
  let restarting = true;
  const restart = env.restart("runtime-catalog").finally(() => {
    restarting = false;
  });
  // Handled the moment it exists. The loop below runs for the ~30 s the recreate takes before
  // `await restart` attaches anything, and a rejection inside that window is reported as an
  // `unhandledRejection` — which fails the worker rather than this test, and buries the reason.
  restart.catch(() => {});
  const refused: string[] = [];
  let calls = 0;
  while (restarting) {
    calls += 1;
    // The global fetch rather than the `request` fixture: this is a poll of sixty-odd calls and
    // every one of them would otherwise land in the failure trace.
    const status = await fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ ping: "during-catalog-restart" }),
    })
      .then((response) => response.status)
      .catch(() => 0);
    if (status !== 200) refused.push(`call ${calls} answered ${status}`);
    await sleep(500);
  }
  await restart;
  // Taken here and nowhere later: `env.restart` returns once the container reports healthy, and the
  // number this test prints is quoted as a measurement from that point. Two assertions and a
  // deployment-rows round trip sit between here and the poll below.
  const healthy = Date.now();

  // A restart the loop never overlapped proves nothing: measured, the recreate takes about 30 s and
  // the loop calls twice a second, so a handful of calls means the loop raced the container.
  expect(calls, "the call loop did not span the catalog's downtime").toBeGreaterThan(10);
  expect(
    refused,
    "the engine stopped serving a deployed chain while the catalog was down, so a catalog restart " +
      "is an outage of the running platform rather than of its control plane",
  ).toEqual([]);

  expect(
    await deploymentRows(catalog, chains),
    "the deployments are not what they were before the catalog restarted",
  ).toEqual(before);
  await waitForRoutes(env, chains, RECOVERY_BUDGET);
  console.log(
    `[restart] catalog: ${chains.length} seed routes answering ` +
      `${((Date.now() - healthy) / 1000).toFixed(1)} s after the container reported healthy, ` +
      `over ${calls} calls driven through the downtime`,
  );

  // The **engine's** surface, not the catalog's. `ComposeEnv.recreate` ends in `reloadProxy`, which
  // polls `/api/…/v1/folders` to 200 and throws otherwise, so a catalog assertion here restates
  // what the restart already guaranteed. What the reload does not check is every other upstream it
  // just re-resolved, and the engine is the one that served traffic throughout this test.
  await expectEngineApiSurface(
    env,
    "the catalog's proxy reload left the engine's upstream unresolved, and every /api/ assertion " +
      "against the engine after this spec would read a stranded address",
  );
});
