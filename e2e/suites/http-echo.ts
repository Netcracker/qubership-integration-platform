import { expect, type TestType } from "@playwright/test";
import type { EchoSuiteFixtures } from "./types.js";
import { callToken } from "../support/run-token.js";
import { callChain, elementNames, failedElements } from "../support/sessions.js";
import { covers } from "../registry/covers.js";

export function registerHttpEchoTests<T extends EchoSuiteFixtures, W extends object>(test: TestType<T, W>): void {
  test.describe("HTTP echo chain", { tag: ["@engine"] }, () => {
    test("answers on its deployed route", async ({ request, echo }) => {
      // The two element families this chain is made of, at their defaults. This case closes the element
      // rows; the axis values have cases of their own.
      covers("http-trigger");
      covers("header-modification");

      // Without an explicit content type the servlet reads the body as form data and the chain fails
      // with "Invalid parameter, expected to be a pair", which reads as a broken chain.
      const answer = await request.post(echo.url, {
        headers: { "Content-Type": "application/json" },
        data: { ping: "runtime" },
      });

      expect(answer.status()).toBe(200);
      expect(JSON.parse(await answer.text()), "the trigger echoes what it was sent").toEqual({
        ping: "runtime",
      });
      expect(answer.headers()[echo.markerHeader]).toBe(echo.marker);
    });

    test("a call's own token finds the trace the engine recorded", { tag: ["@sessions"] }, async ({ request, echo, sessions }) => {
      const { token, response } = await callChain(request, echo.url, {
        data: { ping: "trace" },
      });
      expect(response.status()).toBe(200);

      // Three steps, and the lookup waits for all three: a session is queryable before its elements
      // finish being indexed, so a read that stops at the first one asserts over a partial trace.
      const session = await sessions.byExternalId(token, { elements: 3 });
      expect(session.chainId).toBe(echo.id);
      expect(session.externalSessionCipId).toBe(token);
      // Recursive, and that is the assertion: `Validate Request` is a child of the trigger, so a
      // reader that stops at the top level cannot see which branch an exchange took.
      expect(elementNames(session)).toEqual(echo.elementNames);
      expect(failedElements(session)).toEqual([]);
    });

    test("two calls to one chain record two sessions", { tag: ["@sessions"] }, async ({ request, echo, sessions }) => {
      // The reason the token is per call and not per run: `external-id` answers a *single* session, so
      // a shared token would hand a spec whichever of these two the index returned.
      const first = await callChain(request, echo.url, { data: { ping: 1 } });
      const second = await callChain(request, echo.url, { data: { ping: 2 } });
      expect(first.token).not.toBe(second.token);

      const one = await sessions.byExternalId(first.token);
      const two = await sessions.byExternalId(second.token);
      expect(one.id).not.toBe(two.id);
    });

    test("a lookup by an unsent token times out naming the token", { tag: ["@sessions"] }, async ({ sessions }) => {
      const never = callToken("never-sent");

      // The mutation check for the lookup. `GET /v1/sessions/external-id/{unknown}` answers 404 with
      // the same body as a session that has not landed yet, so nothing in the response separates the
      // two: the helper separates them by bounding the wait and naming the token, and this is what
      // proves it does. A short budget on purpose — the case is that nothing ever arrives.
      let failure = "";
      try {
        await sessions.byExternalId(never, { timeout: 3_000 });
      } catch (cause) {
        failure = String(cause);
      }
      expect(failure, "the lookup returned a session for a token no chain ever sent").not.toBe("");
      expect(failure).toContain(never);
      expect(failure).toContain("external-session-cip-id");
    });
  });
}
