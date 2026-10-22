/**
 * Parallel calls to one seeded chain keep their exchanges apart: each call gets back its own body
 * and records its own complete session.
 *
 * Every other runtime case sends one call at a time, so a body, a header, or a session step that
 * leaked between two exchanges in flight would pass all of them. This is a guard case: no product
 * change on this stack is known to turn it red.
 *
 * Shown failing: one of the twenty calls sending a body other than its token turns the echo check
 * red. The session checks have not been shown failing, because the local cluster records no
 * session a lookup finds.
 *
 * Each session is found by its call's own token. On the micro engine `global` deletes every session
 * in OpenSearch while `runtime-micro` runs, so a count of the chain's sessions could drop at any time.
 *
 * Two calls under one context-storage `contextId` are left out. Calls under different context ids
 * write different Postgres rows and share nothing, and calls under one context id lose a key by the
 * product's design, because a SET rewrites the whole JSON map of the row without a lock.
 */
import { test, expect } from "../../support/fixtures.js";
import { readCorpusState, seedChain } from "../../support/corpus.js";
import { callToken } from "../../support/run.js";
import { callChain, elementNames, failedElements } from "../../support/sessions.js";
import { coversBehavior } from "../../registry/covers.js";

const CALLS = 20;
const STEPS = ["HTTP Trigger", "Validate Request", "Header Modification"];

test.describe("concurrent calls", { tag: ["@engine", "@sessions"] }, () => {
  test("20 parallel calls get their own body and session each", async ({ request, env, sessions }) => {
    coversBehavior("http-trigger", "concurrent-calls");
    const chain = seedChain(readCorpusState(), "http-echo");

    // All twenty requests are in flight together, each with its own token in the correlation header
    // and in the body the trigger echoes.
    const calls = await Promise.all(
      Array.from({ length: CALLS }, () => {
        const token = callToken("concurrent");
        return callChain(request, env.chainUrl(chain.contextPath), { token, data: { ping: token } });
      }),
    );
    for (const { token, response } of calls) {
      expect(response.status(), token).toBe(200);
      expect(JSON.parse(await response.text()), "the echo carries the call's own token").toEqual({ ping: token });
    }

    const recorded = await Promise.all(calls.map(async ({ token }) => sessions.settled(await sessions.byExternalId(token, { elements: STEPS.length }))));
    expect(new Set(recorded.map((session) => session.id)).size, "one session per call").toBe(CALLS);
    for (const session of recorded) {
      expect(session.chainId).toBe(chain.id);
      expect(session.executionStatus, session.externalSessionCipId ?? session.id).toBe("COMPLETED_NORMALLY");
      expect(elementNames(session)).toEqual(STEPS);
      expect(failedElements(session)).toEqual([]);
    }
  });
});
