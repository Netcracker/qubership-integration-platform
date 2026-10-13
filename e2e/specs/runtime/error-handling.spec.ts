/**
 * Try-Catch-Finally at tier 1: the try branch throws on purpose.
 *
 * The interesting assertion is the per-element status rather than the response. The chain answers
 * **200** because the catch branch handled the exception, so a suite reading only the status code
 * cannot tell a handled failure from a chain that never failed at all. The trace can: the try
 * branch is `COMPLETED_WITH_ERRORS`, the container is `COMPLETED_WITH_WARNINGS`, and the catch and
 * finally branches are normal.
 */
import { test, expect } from "../../support/fixtures.js";
import { readCorpusState, seedChain } from "../../support/corpus.js";
import { callChain, element, elementNames } from "../../support/sessions.js";
import { covers } from "../../registry/covers.js";

test("a caught exception leaves the chain green and the failing step red", { tag: ["@engine", "@sessions", "@tier1"] }, async ({ request, env, sessions }) => {
  covers("try-catch-finally-2");
  covers("try-2");
  covers("catch-2");
  covers("finally-2");

  const chain = seedChain(readCorpusState(), "try-catch-finally");

  const call = await callChain(request, env.chainUrl(chain.contextPath), {
    data: { ping: "try" },
  });
  expect(call.response.status()).toBe(200);
  expect(JSON.parse(await call.response.text())).toEqual({ handled: true });
  // The finally branch is a header modification, so the response itself carries proof it ran.
  expect(call.response.headers()["e2e-finally"]).toBe("ran");

  const session = await sessions.byExternalId(call.token, { elements: 9 });
  expect(session.executionStatus).toBe("COMPLETED_WITH_WARNINGS");
  expect(elementNames(session)).toEqual([
    "HTTP Trigger",
    "Validate Request",
    "Try-Catch-Finally",
    "Try",
    "Throwing Script",
    "Catch",
    "Catch Branch",
    "Finally",
    "Finally Branch",
  ]);
  expect(element(session, "Throwing Script")?.executionStatus).toBe("COMPLETED_WITH_ERRORS");
  expect(element(session, "Catch Branch")?.executionStatus).toBe("COMPLETED_NORMALLY");
  expect(element(session, "Finally Branch")?.executionStatus).toBe("COMPLETED_NORMALLY");
});
