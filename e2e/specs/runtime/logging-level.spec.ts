/**
 * `POST /v1/chains/{id}/properties/logging` decides whether there is a trace at all.
 *
 * Every trace assertion in the suite rests on the seed raising that
 * level between import and deploy. Nothing else asserts that the endpoint does what it says, so
 * when a runtime spec somewhere finds an empty trace there is no way to tell a broken chain from a
 * property that never reached the engine. This file is that diagnosis: when it is red, an empty
 * trace elsewhere has its cause.
 *
 * Three seeded chains only this file calls, `logging-level-off`, `-error` and `-debug`, get their
 * levels here, in place and with no redeploy. Both engines read the logging of every chain from one
 * Consul prefix and replace their whole map with each read, so the levels are written OFF first and
 * ERROR last: once a call to the ERROR chain records at ERROR, the OFF write has reached the engine
 * too. A rerun against a kept corpus writes the same levels again.
 *
 * Measured, and worth knowing before reading the assertions. At `OFF` nothing is queryable at all.
 * At `ERROR` only a call that **failed** leaves anything behind: the session travels to OpenSearch
 * through its elements, and the only element kept at that level is a failed one, so a clean call is
 * as invisible as it is at `OFF`. At `DEBUG` every step is there, carrying the body.
 */
import { test, expect } from "../../support/fixtures.js";
import { DEPLOY_TIMEOUT, readCorpusState, SEED_LOGGING, seedChain } from "../../support/corpus.js";
import { callChain, elementNames, trace, type Sessions } from "../../support/sessions.js";
import type { LoggingProperties } from "../../support/catalog.js";

/** The header the probe script reads to decide whether to throw. */
const FAIL_HEADER = "e2e-fail";

/** How long a "nothing was recorded" case waits before deciding nothing will arrive. */
const SHORT_WAIT = 8_000;

/** A body no other chain sends, so finding it in a trace is unambiguous. */
const MARKER = "logging-level-marker";

/** The script element's name, which is what the trace carries and what the assertions read. */
const PROBE_ELEMENT = "Probe Script";

/** The three levels this spec sets. Bare `string` would admit a typo silently. */
type ProbeLevel = "OFF" | "ERROR" | "DEBUG";

/**
 * The corpus's own properties with one field changed.
 *
 * Spread rather than restated: the seed sets seven fields and every trace assertion in the suite
 * rests on them, so a copy here is a second definition that drifts the first time one of them moves.
 */
function logging(level: ProbeLevel): LoggingProperties {
  return { ...SEED_LOGGING, sessionsLoggingLevel: level };
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

test("the session logging level decides whether a call leaves a trace", { tag: ["@engine", "@sessions", "@catalog", "@tier1"] }, async ({ request, env, sessions, catalog }) => {
  const corpus = readCorpusState();
  const off = seedChain(corpus, "logging-level-off");
  const error = seedChain(corpus, "logging-level-error");
  const debug = seedChain(corpus, "logging-level-debug");

  // ERROR last, as the header explains.
  await catalog.saveLoggingProperties(debug.id, logging("DEBUG"));
  await catalog.saveLoggingProperties(off.id, logging("OFF"));
  await catalog.saveLoggingProperties(error.id, logging("ERROR"));

  // The ERROR chain at ERROR, proven before anything is read off the three chains, by the level the
  // session records. Its element list cannot gate: a DEBUG session can be read while only its
  // failed step is indexed (CI run 38677650288). Each call carries its own token, so a call the
  // change did not reach yet only costs an interval.
  await expect
    .poll(
      async () => {
        const failed = await callChain(request, env.chainUrl(error.contextPath), { headers: { [FAIL_HEADER]: "yes" }, data: {} });
        return (await sessions.byExternalId(failed.token, { elements: 1 })).loggingLevel;
      },
      { timeout: DEPLOY_TIMEOUT, message: "the ERROR chain still records a failed call at another level" },
    )
    .toBe("ERROR");

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
  expect(failedSession.loggingLevel).toBe("ERROR");
  expect(elementNames(failedSession)).toEqual([PROBE_ELEMENT]);

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
  // nothing was written for it. The gate above is the other half: the ERROR chain had already
  // recorded a session of its own before `clean` was sent.
  for (const [token, what] of silentTokens) await expectNoSession(sessions, token, what);
});
