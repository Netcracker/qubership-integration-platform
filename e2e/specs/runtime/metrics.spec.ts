/**
 * The chain meters the engine publishes, asserted against a real call.
 *
 * A broken meter is invisible until a production dashboard is empty, which is the worst place to
 * find out. `MetricsStore` registers `sessions.counter`, `sessions.duration.timer` and
 * `chains.failures` tagged by `chain_id` and `chain_name`
 * (`engine/.../debugger/metrics/MetricsStore.java:49-72`), all of it behind `cip.metrics.enabled`.
 * Measured: the flag reads `MONITORING_ENABLED`, which Compose did not set, so `/actuator/prometheus`
 * carried none of these meters — `infrastructure/engine-dev.env` now turns it on.
 *
 * Three measured facts shape the assertions:
 *
 * - **A counter is locked for its first 30 s.** `MetricsStore` wraps every new counter and schedules
 *   `commitAndUnlock` after `cip.metrics.prometheus.init.delay` seconds; increments before that are
 *   buffered and the meter reads **0**. So the assertion polls rather than reads once.
 * - **The corpus is shared and the `runtime` project is parallel**, so another spec may call the same
 *   chain between two readings. The session counter is therefore asserted to have *grown by at least*
 *   the calls this spec made: a meter that is broken reads zero forever, and a meter that works
 *   cannot go backwards.
 * - **A growth assertion is not enough for the failure counter.** `error-handling.spec.ts` calls the
 *   shared `try-catch-finally` chain in the same parallel project and produces exactly one counted
 *   failure, so a `+1` over that chain can come entirely from the neighbor. The failing chain here
 *   is private to the worker for that reason.
 */
import { test, expect } from "../../support/fixtures.js";
import {
  ENGINE_CASE_TIMEOUT,
  SEED_LOGGING,
  readCorpusState,
  seedChain,
  waitForDeployed,
  waitForRoutes,
  type SeedChain,
} from "../../support/corpus.js";
import { createScriptChain } from "../../support/deployable.js";
import { callChain } from "../../support/sessions.js";
import { releaseChains } from "../../support/cleanup.js";
import type { Catalog } from "../../support/catalog.js";
import type { APIRequestContext } from "@playwright/test";

/** `cip.metrics.prometheus.init.delay` is 30 s, and the poll has to outlast it. */
const METER_TIMEOUT = 60_000;

/** How many extra calls the growth assertion makes. */
const CALLS = 3;

/**
 * The sum of one Prometheus counter over every sample carrying `chain_id="<id>"`.
 *
 * Parsed from the text exposition rather than through a client, because the only thing needed is
 * "the samples of this meter for this chain", and the label set varies by `execution_status`. The
 * label is matched anywhere in the sample rather than at a fixed position: Micrometer decides the
 * order, so `meter{chain_id="…"` silently requires `chain_id` to come first.
 */
function sumForChain(exposition: string, meter: string, chainId: string): number {
  let total = 0;
  for (const line of exposition.split("\n")) {
    if (!line.startsWith(`${meter}{`)) continue;
    if (!line.includes(`chain_id="${chainId}"`)) continue;
    const value = Number(line.slice(line.lastIndexOf("}") + 1).trim());
    if (Number.isFinite(value)) total += value;
  }
  return total;
}

/**
 * The exposition, or an empty one.
 *
 * Exception-free by construction, because every reading below is an `expect.poll` callback and
 * `expect.poll` does not retry a callback that throws: one transient non-200 would end the test
 * instead of costing it an interval. An empty exposition sums to zero on every meter, which is what
 * the poll is already waiting to move. The `request` fixture rather than the global `fetch`, so the
 * calls land in the trace a failed test retains.
 */
async function meters(request: APIRequestContext, url: string): Promise<string> {
  const response = await request.get(url, { failOnStatusCode: false }).catch(() => null);
  if (!response || !response.ok()) return "";
  return await response.text();
}

/** The shared builder with a script that always throws, which is how the failure counter moves. */
async function failingChain(
  catalog: Catalog,
  run: string,
  folderId: string,
  built: SeedChain[],
): Promise<SeedChain> {
  return await createScriptChain(
    catalog,
    run,
    {
      what: "metrics-failure",
      parentId: folderId,
      script: "throw new IllegalStateException('metrics failure')",
      scriptName: "Failing Script",
      logging: SEED_LOGGING,
    },
    built,
  );
}

test("a chain's session counter grows with the calls it serves", { tag: ["@engine", "@infra", "@tier1"] }, async ({ request, env }) => {
  test.setTimeout(ENGINE_CASE_TIMEOUT);
  const chain = seedChain(readCorpusState(), "script");
  const url = `${env.url("engine")}/actuator/prometheus`;

  const exposition = await meters(request, url);
  expect(
    exposition,
    "the engine publishes no chain meters, or /actuator/prometheus did not answer: " +
      "cip.metrics.enabled reads MONITORING_ENABLED, and infrastructure/engine-dev.env is where " +
      "Compose sets it",
  ).toContain("qip_engine_sessions_counter_total");

  const before = sumForChain(exposition, "qip_engine_sessions_counter_total", chain.id);
  for (let call = 0; call < CALLS; call++) {
    const answered = await callChain(request, env.chainUrl(chain.contextPath), { data: { call } });
    expect(answered.response.status()).toBe(200);
  }

  await expect
    .poll(
      async () =>
        sumForChain(await meters(request, url), "qip_engine_sessions_counter_total", chain.id),
      {
        timeout: METER_TIMEOUT,
        message:
          `sessions.counter for chain ${chain.id} did not grow by ${CALLS}. A counter is locked ` +
          `for its first 30 s and reads 0 until it commits, so a value that never moves past the ` +
          `baseline is the meter, not the lock`,
      },
    )
    .toBeGreaterThanOrEqual(before + CALLS);

  // The timer is registered from the same call path, so its absence separates "no meters at all"
  // from "the counter alone is broken". Summed through the same helper, because matching the
  // literal `..._count{chain_id="…"` would pin Micrometer's label order along with it.
  //
  // Polled like every other reading here, and for the reason the `meters` docstring gives: it
  // answers `""` on a transport failure, which sums to zero on every meter. A single read would
  // report one refused connection as a timer that carries no sample.
  await expect
    .poll(
      async () =>
        sumForChain(
          await meters(request, url),
          "qip_engine_sessions_duration_timer_seconds_count",
          chain.id,
        ),
      {
        timeout: METER_TIMEOUT,
        message: `sessions.duration.timer carries no sample for chain ${chain.id}`,
      },
    )
    .toBeGreaterThan(0);
});

test("a chain that ends in a failure increments the failure counter", { tag: ["@engine", "@catalog", "@infra", "@tier1"] }, async ({ request, env, catalog, folder, run }) => {
  test.setTimeout(ENGINE_CASE_TIMEOUT);
  const url = `${env.url("engine")}/actuator/prometheus`;

  // The accumulator is bound out here and filled inside the `try`, because a chain that throws
  // partway through building is one the `finally` still has to release.
  const chains: SeedChain[] = [];
  let bodyFailed = true;
  try {
    // A chain of this spec's own rather than a corpus one. `ChainFinishProcessor.handleMetrics`
    // counts both COMPLETED_WITH_WARNINGS and COMPLETED_WITH_ERRORS as a chain failure, and the
    // corpus chain that produces the first of those is also called by `error-handling.spec.ts` in
    // the same parallel project — so a `+1` there could be entirely the neighbor's, and this
    // assertion would pass whatever this spec did.
    const chain = await failingChain(catalog, run, folder.id, chains);

    const snapshot = await catalog.createSnapshot(chain.id);
    await catalog.deploy(chain.id, snapshot.id);
    await waitForDeployed(catalog, [chain]);
    await waitForRoutes(env, [chain]);

    // One, not a growth over a baseline. The chain was created inside this test and nothing else on
    // this stack can call it, so the meter has no history to read a baseline from: a `before` here
    // would be zero by construction and the arithmetic around it would say nothing.
    const call = await callChain(request, env.chainUrl(chain.contextPath), { data: { ping: "fail" } });
    expect(call.response.status()).toBe(500);

    await expect
      .poll(
        async () =>
          sumForChain(await meters(request, url), "qip_engine_chains_failures_total", chain.id),
        {
          timeout: METER_TIMEOUT,
          message:
            `chains.failures for chain ${chain.id} never reached exactly 1. The meter is locked ` +
            `for its first 30 s and reads 0 until it commits, so a value stuck at 0 is the meter ` +
            `rather than the lock`,
        },
      )
      .toBe(1);
    bodyFailed = false;
  } finally {
    await releaseChains(catalog, chains, bodyFailed);
  }
});
