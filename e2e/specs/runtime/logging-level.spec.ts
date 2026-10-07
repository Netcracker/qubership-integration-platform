/**
 * `POST /v1/chains/{id}/properties/logging` decides whether there is a trace at all.
 *
 * Every trace assertion in the suite rests on the seed raising that
 * level between import and deploy. Nothing else asserts that the endpoint does what it says, so
 * when a runtime spec somewhere finds an empty trace there is no way to tell a broken chain from a
 * property that never reached the engine. This file is that diagnosis: when it is red, an empty
 * trace elsewhere has its cause.
 *
 * The chains are built and deployed **inside the spec**, in the worker's own folder, because the
 * shared corpus is read-only by construction — changing one chain's logging level would change it
 * for every other spec running at the same time.
 *
 * Measured, and worth knowing before reading the assertions. At `OFF` nothing is queryable at all.
 * At `ERROR` only a call that **failed** leaves anything behind: the session travels to OpenSearch
 * through its elements, and the only element kept at that level is a failed one, so a clean call is
 * as invisible as it is at `OFF`. At `DEBUG` every step is there, carrying the body.
 */
import { test, expect } from "../../support/fixtures.js";
import {
  ENGINE_CASE_TIMEOUT,
  SEED_LOGGING,
  type SeedChain,
  waitForDeployed,
  waitForRoutes,
} from "../../support/corpus.js";
import { createScriptChain } from "../../support/deployable.js";
import { callChain, elementNames, trace, type Sessions } from "../../support/sessions.js";
import { releaseChains, waitForFirstRecording } from "../../support/cleanup.js";
import type { Catalog, LoggingProperties } from "../../support/catalog.js";

/** The header the probe script reads to decide whether to throw. */
const FAIL_HEADER = "e2e-fail";

/** How long a "nothing was recorded" case waits before deciding nothing will arrive. */
const SHORT_WAIT = 8_000;

/** A body no other chain sends, so finding it in a trace is unambiguous. */
const MARKER = "logging-level-marker";

/** The script element's name, which is what the trace carries and what the assertions read. */
const PROBE_ELEMENT = "Probe Script";

const PROBE_SCRIPT =
  `if (exchange.getMessage().getHeader('${FAIL_HEADER}') == 'yes') ` +
  `throw new IllegalStateException('probe failure'); ` +
  `exchange.getMessage().setBody('{"probe":"ok"}')`;

/** The three levels this spec deploys a chain at. Bare `string` would admit a typo silently. */
type ProbeLevel = "OFF" | "ERROR" | "DEBUG";

/**
 * The corpus's own properties with one field changed.
 *
 * Spread rather than restated: the seed sets seven fields and every trace assertion in the suite
 * rests on them, so a copy here is a second definition that drifts the first time one of them moves.
 * `maskingEnabled` comes with it and costs nothing — the probe chain declares no masked field.
 */
function logging(level: ProbeLevel): LoggingProperties {
  return { ...SEED_LOGGING, sessionsLoggingLevel: level };
}

/** The shared builder with this spec's script in it, at the level under test. */
async function probeChain(
  catalog: Catalog,
  run: string,
  folderId: string,
  suffix: string,
  level: ProbeLevel,
  built: SeedChain[],
): Promise<SeedChain> {
  return await createScriptChain(
    catalog,
    run,
    {
      what: `log-${suffix}`,
      parentId: folderId,
      script: PROBE_SCRIPT,
      scriptName: PROBE_ELEMENT,
      logging: logging(level),
    },
    built,
  );
}

/**
 * That nothing was recorded for a token, said twice over.
 *
 * A bare `rejects.toThrow()` is satisfied by a refused connection and by a 500 as readily as by an
 * empty index, so the rejection is matched against the lookup's own message; and the endpoint's
 * status is read afterwards, because 404 is what "no such session" looks like and anything else is
 * the lookup breaking rather than the level holding.
 */
async function expectNoSession(sessions: Sessions, token: string, what: string): Promise<void> {
  await expect(sessions.byExternalId(token, { timeout: SHORT_WAIT }), what).rejects.toThrow(
    /no settled session carrying/,
  );
  expect((await sessions.attemptByExternalId(token)).status, what).toBe(404);
}

test("the session logging level decides whether a call leaves a trace", { tag: ["@engine", "@sessions", "@catalog", "@tier1"] }, async ({ request, env, sessions, catalog, folder, run }) => {
  test.setTimeout(ENGINE_CASE_TIMEOUT);

  // Inside the `try`, because the second chain can fail to build after the first one has already
  // written its Consul key, and the `finally` is the only thing that removes it.
  const chains: SeedChain[] = [];
  let bodyFailed = true;
  try {
    const off = await probeChain(catalog, run, folder.id, "off", "OFF", chains);
    const error = await probeChain(catalog, run, folder.id, "error", "ERROR", chains);
    const debug = await probeChain(catalog, run, folder.id, "debug", "DEBUG", chains);

    const snapshots = await Promise.all(chains.map((each) => catalog.createSnapshot(each.id)));
    for (const [index, chain] of chains.entries()) {
      await catalog.deploy(chain.id, snapshots[index].id);
    }
    await waitForDeployed(catalog, chains);
    await waitForRoutes(env, chains);

    // The ERROR chain's recording path, proven live before anything is read off it.
    //
    // `clean` below is an absence assertion, and a chain deployed in a batch with others can lose
    // the session of its first call — `docs/product-defects.md`, "A chain deployed in the same
    // engine batch as another can lose the session of its first call", measured at 2 of 12 calls
    // sent the moment the route answered. A lost session and a suppressed one read the same, so
    // without this gate a regression that started recording clean calls at ERROR would still pass.
    // The warm-up calls fail on purpose: a clean one records nothing at this level by design and
    // would never satisfy the gate. The OFF chain gets none, for the same reason — nothing it does
    // records — and what stands in for one there is `silentFailure`, its **second** call.
    await waitForFirstRecording(error.name, {
      send: async (marker) => {
        await callChain(request, env.chainUrl(error.contextPath), {
          token: marker,
          headers: { [FAIL_HEADER]: "yes" },
          data: {},
        });
      },
      recorded: async (marker) => (await sessions.attemptByExternalId(marker, false)).session !== null,
    });

    // OFF: nothing is queryable, and that holds for a call that failed as much as for one that did
    // not. The failing half is what separates OFF from ERROR, which are otherwise indistinguishable.
    const silent = await callChain(request, env.chainUrl(off.contextPath), { data: { m: MARKER } });
    expect(silent.response.status()).toBe(200);

    const silentFailure = await callChain(request, env.chainUrl(off.contextPath), {
      headers: { [FAIL_HEADER]: "yes" },
      data: { m: MARKER },
    });
    expect(silentFailure.response.status()).toBe(500);

    // ERROR: a call that did not fail leaves nothing queryable either. Measured, and worth stating
    // precisely, because the code reads the other way at first glance: `SessionsService` does put the
    // session in its cache at any level above OFF, but a session reaches OpenSearch only through its
    // **elements**, and at ERROR the only element kept is a failed one. No failed step, no session.
    const clean = await callChain(request, env.chainUrl(error.contextPath), { data: { m: MARKER } });
    expect(clean.response.status()).toBe(200);

    const silentTokens: Array<[string, string]> = [
      [silent.token, "OFF recorded a session, so the level is not being honored"],
      [silentFailure.token, "OFF recorded a failed call, which is what ERROR is for"],
      [clean.token, "ERROR recorded a call that did not fail"],
    ];

    const failed = await callChain(request, env.chainUrl(error.contextPath), {
      headers: { [FAIL_HEADER]: "yes" },
      data: { m: MARKER },
    });
    expect(failed.response.status()).toBe(500);
    const failedSession = await sessions.byExternalId(failed.token, { elements: 1 });
    expect(failedSession.executionStatus).toBe("COMPLETED_WITH_ERRORS");
    expect(elementNames(failedSession)).toContain(PROBE_ELEMENT);

    // DEBUG with BODY in logPayload: every step, and the body with it.
    const verbose = await callChain(request, env.chainUrl(debug.contextPath), { data: { m: MARKER } });
    expect(verbose.response.status()).toBe(200);
    const verboseSession = await sessions.byExternalId(verbose.token, { elements: 3 });
    expect(elementNames(verboseSession)).toContain(PROBE_ELEMENT);
    expect(
      trace(verboseSession).some((step) => (step.bodyBefore ?? "").includes(MARKER)),
      "DEBUG with BODY in logPayload recorded no body",
    ).toBe(true);

    // Only now are the three silent tokens read, and the ordering is the point.
    //
    // On their own the OFF and ERROR conclusions rest on an 8 s wait, and 8 s is a budget rather
    // than a proof: under eight workers an engine that had stopped honoring the levels altogether
    // would read the same as one honoring them. The DEBUG session arrived from a later call, so
    // indexing was live over this window, and a token still absent behind it is absent because
    // nothing was written for it. The gate after the deploy is the other half: the ERROR chain had
    // already recorded a session of its own before `clean` was sent, so `clean` is not the first
    // call the batch could have dropped.
    for (const [token, what] of silentTokens) await expectNoSession(sessions, token, what);
    bodyFailed = false;
  } finally {
    // In a `finally`: an assertion above leaves three chains deployed and holding routes for the
    // rest of the run, and the next spec to fail is not the one that leaked them. The helper
    // reports what it could not release rather than swallowing it, which is the whole point of the
    // block — and stays quiet when the body already failed, so it never displaces the finding.
    await releaseChains(catalog, chains, bodyFailed);
  }
});
