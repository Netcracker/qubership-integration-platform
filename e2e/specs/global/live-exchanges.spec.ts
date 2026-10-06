/**
 * `live-exchanges-controller`, on both services: the catalog's three operations and the engine's
 * two that they fan out to.
 *
 * Global by construction. The catalog list is "the top N exchanges running on every engine, ordered
 * by duration", default `limit=10`, and the engine list is the same thing for one pod: neither can
 * be scoped by a caller, and any assertion about what is *not* in them is a claim about what every
 * other worker is doing at that instant. `specs/global/` runs one worker after `api`, `runtime` and
 * `env`, which is what makes the readings below stable enough to assert.
 *
 * **The timing is handled by holding the exchange, never by sleeping and hoping.** An exchange can
 * finish before the first list call, and
 * "not in the list" on its own distinguishes nothing — an empty list is the answer for a call that
 * finished, for a call that never started, and for a call the `limit` pushed out. So the fixture
 * chain `long-running` holds its exchange for as long as the `e2e-hold-ms` header says, and every
 * case here asserts the exchange **present, by its own id**, before it asserts anything about its
 * absence. The presence assertion is what gives the absence one meaning instead of three.
 *
 * Six shapes measured against the stack rather than assumed:
 *
 * - **204 on empty, on both services.** `CollectionUtils.isEmpty(result)` answers
 *   `ResponseEntity.noContent()`, so a client parsing the body gets a parse error rather than `[]`.
 *   The transports unwrap it; one case asserts the raw status.
 * - **`limit` is `@Positive` and validated.** `?limit=0` answers **400** on the catalog and **500**
 *   on the engine — the catalog has a handler for `ConstraintViolationException` and the engine
 *   does not.
 * - **the filter key is `column`, not `feature`.** `FilterRequestDTO.feature` carries
 *   `@JsonProperty("column")`. A body spelling `feature` is accepted and silently ignored, because
 *   Jackson leaves the field null and `isApplicable` is then false for every filter, so the clause
 *   matches every row. A `column` naming a feature neither `StringFieldFilter` nor
 *   `LongFieldFilter` handles behaves the same way.
 * - **the catalog row is the engine row plus two fields.** `podIp` and `chainName` are added by
 *   `LiveExchangeExtDTO` and by `enrichResultWithChainName`; everything else is the engine's own
 *   `LiveExchangeDTO`, field for field.
 * - **a kill is asynchronous and its effect is in the session, not in the status.** The engine sets
 *   a `ChainExecutionTerminatedException` on the exchange, so the step that was running finishes
 *   and the route then aborts: the caller gets **500 `QIP-0114`** and the recorded session gets
 *   `COMPLETED_WITH_ERRORS` with `Chain was interrupted manually` on the element that was in
 *   flight. That is what these cases assert.
 * - **the catalog does not answer 202 regardless of the outcome.** `sendKillExchangeRequest` maps
 *   the engine's 404 to a 404 that keeps the engine's message, so a kill aimed at nothing is a 404
 *   on both services, and an address that is no registered engine pod is a 404 of its own.
 */
import { test, expect } from "../../support/fixtures.js";
import { ENGINE_CASE_TIMEOUT, readCorpusState, seedChain } from "../../support/corpus.js";
import { element } from "../../support/sessions.js";
import { tokenized } from "../../support/run.js";
import { DEFAULT_DOMAIN } from "../../support/catalog.js";
import type { Catalog, LiveExchangeExtView } from "../../support/catalog.js";
import { hold, untilListed } from "../../support/held-call.js";
import type { LiveExchange } from "../../support/engine.js";

/** The fixture whose script holds the exchange. Its hold is a header, so one deployment serves all. */
const HOLDING_FIXTURE = "long-running";

/** The element the hold happens in, and therefore the one a kill interrupts. */
const HOLDING_ELEMENT = "Hold";

/** The message `ChainExecutionTerminatedException` carries into the trace. */
const KILLED_MESSAGE = "Chain was interrupted manually";

/** How long a held call stays in flight. Long enough to poll for it twice over on a loaded stack. */
const HOLD_MS = 12_000;

/** Waits until the platform reports nothing in flight, so an emptiness assertion means something. */
async function untilNothingInFlight(catalog: Catalog): Promise<void> {
  await expect
    .poll(async () => (await catalog.liveExchanges(100)).length, {
      message: "an exchange was still running when this case needed the platform idle",
    })
    .toBe(0);
}

test("with nothing in flight both services answer 204 rather than an empty list", { tag: ["@catalog", "@engine", "@tier1"] }, async ({ catalog, engine }) => {
  await untilNothingInFlight(catalog);

  const fromCatalog = await catalog.liveExchangesResponse();
  expect(fromCatalog.status()).toBe(204);
  expect(await fromCatalog.text()).toBe("");

  const fromEngine = await engine.raw("get", "/v1/engine/live-exchanges");
  expect(fromEngine.status()).toBe(204);
  expect(await fromEngine.text()).toBe("");
});

test("an exchange in flight is listed by both services with one identity, and leaves when it ends", { tag: ["@catalog", "@engine", "@tier1"] }, async ({ catalog, engine, env, request }) => {
  test.setTimeout(ENGINE_CASE_TIMEOUT);
  const chain = seedChain(readCorpusState(), HOLDING_FIXTURE);
  await untilNothingInFlight(catalog);

  const call = hold(request, env, chain.contextPath, HOLD_MS);
  try {
    const listed = await untilListed(catalog, chain.id);

    // The catalog row is the engine's row plus the two fields the catalog adds. Asserted as a pair
    // so a fan-out that lost the chain name, or one that reported the wrong pod, fails here.
    expect(listed.chainName).toBe(chain.name);
    expect(listed.podIp).toBe((await catalog.engineHosts())[DEFAULT_DOMAIN][0]);
    expect(listed.main).toBe(true);
    expect(listed.sessionLogLevel).toBe("DEBUG");
    expect(listed.duration, "the exchange reports no duration").toBeGreaterThan(0);
    expect(listed.sessionId).toBeTruthy();
    expect(listed.deploymentId).toBe((await catalog.listDeployments(chain.id))[0].id);

    const direct = await engine.liveExchanges();
    const same = direct.find((each) => each.exchangeId === listed.exchangeId);
    expect(same, "the engine does not report the exchange the catalog attributed to it").toBeDefined();
    expect(same).toMatchObject({
      exchangeId: listed.exchangeId,
      deploymentId: listed.deploymentId,
      sessionId: listed.sessionId,
      chainId: chain.id,
      main: true,
    });
    // The two extra fields are the catalog's, so the engine's own row must not carry them.
    expect(same).not.toHaveProperty("podIp");
    expect(same).not.toHaveProperty("chainName");

    // `limit` bounds the answer rather than filtering it: one is still this exchange, because it is
    // the only one running and the order is by duration.
    expect((await catalog.liveExchanges(1)).map((each) => each.exchangeId)).toEqual([
      listed.exchangeId,
    ]);

    // And it is gone once the call answers — which is the assertion the presence above earns. On
    // its own an empty list would be satisfied by a call that never started.
    expect((await call.answer).status()).toBe(200);
    await expect
      .poll(
        async () => (await catalog.liveExchanges(100)).some((e) => e.exchangeId === listed.exchangeId),
        { message: "the exchange was still listed after the chain had answered" },
      )
      .toBe(false);
    expect((await engine.liveExchanges()).map((each) => each.exchangeId)).not.toContain(
      listed.exchangeId,
    );
  } finally {
    await call.answer.catch(() => undefined);
  }
});

test("the filter form selects the chain it names and excludes it the other way round", { tag: ["@catalog", "@tier2"] }, async ({ catalog, env, request }) => {
  test.setTimeout(ENGINE_CASE_TIMEOUT);
  const chain = seedChain(readCorpusState(), HOLDING_FIXTURE);
  await untilNothingInFlight(catalog);

  const call = hold(request, env, chain.contextPath, HOLD_MS);
  try {
    const listed = await untilListed(catalog, chain.id);

    const selected = await catalog.filterLiveExchanges([
      { column: "CHAIN_NAME", condition: "CONTAINS", value: chain.name },
    ]);
    expect(selected.map((each) => each.exchangeId)).toEqual([listed.exchangeId]);

    // The same clause inverted removes it. This is the half that makes the selection above a
    // filter rather than a list that happened to hold one row.
    const excluded = await catalog.filterLiveExchanges([
      { column: "CHAIN_NAME", condition: "DOES_NOT_CONTAIN", value: chain.name },
    ]);
    expect(excluded.map((each) => each.exchangeId)).not.toContain(listed.exchangeId);

    // A numeric column, over the other filter implementation: the exchange has been running for
    // milliseconds, not minutes.
    expect(
      (
        await catalog.filterLiveExchanges([
          { column: "EXCHANGE_DURATION", condition: "GREATER_THAN", value: "600000" },
        ])
      ).map((each) => each.exchangeId),
    ).not.toContain(listed.exchangeId);
    expect(
      (
        await catalog.filterLiveExchanges([
          { column: "SESSION_ID", condition: "IS", value: listed.sessionId },
        ])
      ).map((each) => each.exchangeId),
    ).toEqual([listed.exchangeId]);

    // A column neither field filter handles is not refused and does not narrow: `isApplicable` is
    // false for both, so `allMatch` is vacuously true and the clause passes every row.
    expect(
      (
        await catalog.filterLiveExchanges([
          { column: "CHAIN_ID", condition: "CONTAINS", value: "nothing-carries-this" },
        ])
      ).map((each) => each.exchangeId),
    ).toContain(listed.exchangeId);

    // And so is a clause spelled with the Java field name instead of the JSON one, which is the
    // sharper of the two: `feature` looks right in the source and is dropped on the wire.
    const misspelled = await catalog.raw("post", "/v1/catalog/live-exchanges", {
      filters: [{ feature: "CHAIN_NAME", condition: "DOES_NOT_CONTAIN", value: chain.name }],
    });
    expect(misspelled.status()).toBe(200);
    expect(
      ((await misspelled.json()) as LiveExchangeExtView[]).map((each) => each.exchangeId),
      "a filter key the DTO does not bind narrowed the answer, so this note is stale",
    ).toContain(listed.exchangeId);

    expect((await call.answer).status()).toBe(200);
  } finally {
    await call.answer.catch(() => undefined);
  }
});

test("killing an exchange through the catalog interrupts the chain and the session records it", { tag: ["@catalog", "@engine", "@tier1"] }, async ({ catalog, env, request, sessions }) => {
  test.setTimeout(ENGINE_CASE_TIMEOUT);
  const chain = seedChain(readCorpusState(), HOLDING_FIXTURE);
  await untilNothingInFlight(catalog);

  const call = hold(request, env, chain.contextPath, HOLD_MS);
  try {
    // The `podIp` is learnable only from this list, which is the reason the kill is a global
    // operation rather than something a chain-scoped caller could do.
    const listed = await untilListed(catalog, chain.id);
    const killed = await catalog.killLiveExchange(
      listed.podIp,
      listed.deploymentId,
      listed.exchangeId,
    );
    expect(killed.status()).toBe(202);

    // The status says only that the request was dispatched, so the outcome is read off the chain
    // and off the trace. `QIP-0114` is the engine's code for a session shut down by hand.
    const response = await call.answer;
    expect(response.status()).toBe(500);
    const body = (await response.json()) as { code: string; extra: { sessionId: string } };
    expect(body.code).toBe("QIP-0114");
    expect(body.extra.sessionId).toBe(listed.sessionId);

    const session = await sessions.byExternalId(call.token, { elements: 2 });
    expect(session.id).toBe(listed.sessionId);
    expect(session.executionStatus).toBe("COMPLETED_WITH_ERRORS");
    // The element that was in flight is the one that carries the reason, and it is the script the
    // hold happens in rather than the trigger above it.
    expect(element(session, HOLDING_ELEMENT)?.executionStatus).toBe("COMPLETED_WITH_ERRORS");
    expect(element(session, HOLDING_ELEMENT)?.exceptionInfo?.message).toBe(KILLED_MESSAGE);

    // The positive half of "#848 writes the audit row only after the engine accepts the kill". The
    // case below asserts the absence for a kill that found nothing, and an absence on its own is
    // equally green when the audit log stopped working altogether. Polled because
    // `ActionsLogService.logAction` offers the row to a queue that `ActionWriterThread` drains on
    // its own thread, so it is not committed when the kill's response returns.
    await expect
      .poll(
        async () =>
          (
            await catalog.recentActions([
              { column: "ENTITY_ID", condition: "IS", value: listed.exchangeId },
            ])
          ).actionLogs.map((row) => `${row.entityType} ${row.operation}`),
        { message: "the accepted kill left no audit row" },
      )
      .toContain("EXCHANGE DELETE");
  } finally {
    await call.answer.catch(() => undefined);
  }
});

test("killing through the engine needs no pod address and does the same thing", { tag: ["@engine", "@tier1"] }, async ({ catalog, engine, env, request, sessions }) => {
  test.setTimeout(ENGINE_CASE_TIMEOUT);
  const chain = seedChain(readCorpusState(), HOLDING_FIXTURE);
  await untilNothingInFlight(catalog);

  const call = hold(request, env, chain.contextPath, HOLD_MS);
  try {
    // Straight to the engine, which is where the catalog's kill ends up: the deployment and the
    // exchange are all it needs, because a pod can only kill what it is running.
    let running: LiveExchange[] = [];
    await expect
      .poll(
        async () => {
          running = await engine.liveExchanges();
          return running.some((each) => each.chainId === chain.id);
        },
        { message: `the engine never reported an exchange for ${chain.name}` },
      )
      .toBe(true);
    const mine = running.find((each) => each.chainId === chain.id)!;

    expect((await engine.killExchange(mine.deploymentId, mine.exchangeId)).status()).toBe(202);

    expect((await call.answer).status()).toBe(500);
    const session = await sessions.byExternalId(call.token, { elements: 2 });
    expect(session.executionStatus).toBe("COMPLETED_WITH_ERRORS");
    expect(element(session, HOLDING_ELEMENT)?.exceptionInfo?.message).toBe(KILLED_MESSAGE);
  } finally {
    await call.answer.catch(() => undefined);
  }
});

test("a kill aimed at nothing is a 404 on both services, and leaves no audit row", { tag: ["@catalog", "@engine", "@tier2"] }, async ({ catalog, engine, run }) => {
  const host = (await catalog.engineHosts())[DEFAULT_DOMAIN][0];
  // The exchange id carries the run token, and that is what makes the audit assertion below an
  // assertion: `Catalog.recentActions` reads a ten-minute window, which is longer than a full run,
  // so a literal id would be satisfied by the row the previous run left.
  const exchangeId = tokenized(run, "no-such-exchange");

  // The engine is correct about it, and specific: an unknown deployment and a known deployment
  // with no such exchange are two different messages under one status.
  const noDeployment = await engine.killExchange("no-such-deployment", exchangeId);
  expect(noDeployment.status()).toBe(404);
  expect(await noDeployment.text()).toContain("No deployment found for id no-such-deployment");

  // Through the catalog this used to be a 500: `RestTemplate.delete` raised on the engine's 4xx and
  // `LiveExchangesService` did not catch it, so terminating an exchange that had already finished
  // reported a failure on the Live Exchanges screen. #848 maps the engine's 404 to 404 and keeps the
  // engine's message, which is the half that says *which* of the two 404s it is.
  const throughCatalog = await catalog.killLiveExchange(host, "no-such-deployment", exchangeId);
  expect(throughCatalog.status()).toBe(404);
  expect(await throughCatalog.text()).toContain("No deployment found for id no-such-deployment");

  // An address that is not a registered engine host is the other 404, and it names no address —
  // the kill used to send a DELETE to any host on port 8080 and put that URL in the error.
  const unknownPod = await catalog.killLiveExchange("10.255.255.1", "no-such-deployment", exchangeId);
  expect(unknownPod.status()).toBe(404);
  expect(await unknownPod.text()).toContain("No engine pod is registered at the given address");
  expect(await unknownPod.text(), "the refusal names no address").not.toContain("10.255.255.1");

  // The audit row is now written **after** the engine accepts the kill, so a kill that found
  // nothing is no longer logged like one that happened. Read rather than polled: a poll for an
  // absence only measures how long the poll waits. `ActionsLogService.logAction` offers the row to a
  // queue that `ActionWriterThread` drains on its own thread, so the wait above — two further HTTP
  // round trips — is what makes this read meaningful, and the engine's own 404 assertion at the top
  // of the case is what proves the kill really did reach it.
  expect(
    (await catalog.recentActions([{ column: "ENTITY_ID", condition: "IS", value: exchangeId }]))
      .actionLogs,
    "a kill that found nothing should leave no audit row",
  ).toEqual([]);
});

test("limit is validated, and the two services disagree about how", { tag: ["@catalog", "@engine", "@tier2"] }, async ({ catalog, engine }) => {
  // `@Positive` on both, and the same violation reaches the caller as two different statuses: the
  // catalog maps `ConstraintViolationException` to 400, the engine has no handler and falls through
  // to its generic 500. Pinning both is what would notice either one changing.
  const fromCatalog = await catalog.liveExchangesResponse(0);
  expect(fromCatalog.status()).toBe(400);
  expect(await fromCatalog.text()).toContain("must be greater than 0");

  const fromEngine = await engine.raw("get", "/v1/engine/live-exchanges?limit=0");
  expect(fromEngine.status()).toBe(500);
  expect(await fromEngine.text()).toContain("must be greater than 0");
});
