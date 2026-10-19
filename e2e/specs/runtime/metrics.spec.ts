/**
 * The chain meters the engine publishes, asserted against a real call.
 *
 * A broken meter is invisible until a production dashboard is empty, which is the worst place to
 * find out. `MetricsStore` registers `sessions.counter`, `sessions.duration.timer` and
 * `chains.failures` tagged by `chain_id` and `chain_name`
 * (`engine/.../debugger/metrics/MetricsStore.java:49-72`), all of it behind `cip.metrics.enabled`.
 * Measured: the flag reads `MONITORING_ENABLED`, which Compose did not set, so `/actuator/prometheus`
 * carried none of these meters — `infrastructure/docker/engine-dev.env` now turns it on.
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
 * - **The failure counter needs a chain no other spec calls.** `error-handling.spec.ts` calls the
 *   shared `try-catch-finally` chain in the same parallel project and produces exactly one counted
 *   failure, so a `+1` over that chain can come entirely from the neighbor. The seeded
 *   `metrics-failure` chain is called by this file alone, so its counter grows by exactly the calls
 *   made here, also against a kept corpus.
 */
import { test, expect } from "../../support/fixtures.js";
import { ENGINE_CASE_TIMEOUT, readCorpusState, seedChain } from "../../support/corpus.js";
import { callChain } from "../../support/sessions.js";
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

test("a chain's session counter grows with the calls it serves", { tag: ["@engine", "@infra", "@tier1"] }, async ({ request, env }) => {
  test.setTimeout(ENGINE_CASE_TIMEOUT);
  const chain = seedChain(readCorpusState(), "script");
  const url = env.engineMetricsUrl();

  const exposition = await meters(request, url);
  expect(
    exposition,
    `the engine publishes no chain meters, or ${url} did not answer: ` +
      "cip.metrics.enabled reads MONITORING_ENABLED, and infrastructure/docker/engine-dev.env is where " +
      "Compose sets it; a micro domain gets it from the chart value qip-runtime-catalog.microDomainMetrics",
  ).toContain("cip_engine_sessions_counter_total");

  const before = sumForChain(exposition, "cip_engine_sessions_counter_total", chain.id);
  for (let call = 0; call < CALLS; call++) {
    const answered = await callChain(request, env.chainUrl(chain.contextPath), { data: { call } });
    expect(answered.response.status()).toBe(200);
  }

  await expect
    .poll(
      async () =>
        sumForChain(await meters(request, url), "cip_engine_sessions_counter_total", chain.id),
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
          "cip_engine_sessions_duration_timer_seconds_count",
          chain.id,
        ),
      {
        timeout: METER_TIMEOUT,
        message: `sessions.duration.timer carries no sample for chain ${chain.id}`,
      },
    )
    .toBeGreaterThan(0);
});

test("a chain that ends in a failure increments the failure counter", { tag: ["@engine", "@infra", "@tier1"] }, async ({ request, env }) => {
  test.setTimeout(ENGINE_CASE_TIMEOUT);
  const url = env.engineMetricsUrl();
  // `ChainFinishProcessor.handleMetrics` counts COMPLETED_WITH_WARNINGS and COMPLETED_WITH_ERRORS
  // alike, and the script of this chain always throws.
  const chain = seedChain(readCorpusState(), "metrics-failure");

  // Zero on a fresh corpus, where the counter does not exist yet; what earlier runs counted on a kept
  // one. Read off an exposition that answered, since `meters` turns a failed read into zero.
  let exposition = "";
  await expect
    .poll(async () => (exposition = await meters(request, url)) !== "", { timeout: METER_TIMEOUT, message: `${url} never answered` })
    .toBe(true);
  const before = sumForChain(exposition, "cip_engine_chains_failures_total", chain.id);
  const call = await callChain(request, env.chainUrl(chain.contextPath), { data: { ping: "fail" } });
  expect(call.response.status()).toBe(500);

  await expect
    .poll(
      async () =>
        sumForChain(await meters(request, url), "cip_engine_chains_failures_total", chain.id),
      {
        timeout: METER_TIMEOUT,
        message:
          `chains.failures for chain ${chain.id} never reached exactly ${before + 1}. A new counter is ` +
          `locked for its first 30 s and reads 0 until it commits, so a value stuck at ${before} is ` +
          `the meter rather than the lock`,
      },
    )
    .toBe(before + 1);
});
