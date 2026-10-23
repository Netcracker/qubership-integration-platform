/**
 * The trace reader, against a session this stack actually recorded.
 *
 * No stack: `fixtures/sessions/recorded-session.json` is the response
 * `GET /v1/sessions/external-id/{token}?includeDetails=true` gave for a call to the `http-echo`
 * fixture chain, captured once and committed verbatim. So the shapes asserted
 * below are the shapes the service produces, not the shapes the helper was written against — which
 * is the whole reason the payload is a file rather than a literal in this spec.
 *
 * The run token in the recording (`zsess1`) is the one that captured it and is deliberately left
 * alone. Rewriting it to a placeholder would make the file a paraphrase of a response instead of a
 * response.
 *
 * The lookup against a stack is not exercised here. It needs port 8093, and this project is defined
 * as needing no stack; `specs/runtime/http-echo.spec.ts` is where the same helper is proven against
 * the engine. The `lookups` cases run `byExternalId` and `onlyOf` over a stubbed transport instead,
 * on a base that matches no operation row, so a stubbed call is never recorded as reached.
 */
import { test, expect, type APIRequestContext } from "@playwright/test";
import fs from "node:fs";
import { RECORDED_SESSION_FIXTURE } from "../../fixtures/templating.js";
import {
  CORRELATION_HEADER,
  element,
  elementNames,
  elementsOfType,
  failedElements,
  hasTrace,
  isSettled,
  Sessions,
  stepsInProgress,
  trace,
  type RecordedSession,
} from "../../support/sessions.js";

function recorded(): RecordedSession {
  return JSON.parse(fs.readFileSync(RECORDED_SESSION_FIXTURE, "utf-8")) as RecordedSession;
}

/** A sessions host that exists nowhere, under a path prefix the registry does not describe. */
const STUB_BASE = "http://sessions.invalid/stub";

/**
 * The recording as the index can return it for about one refresh interval: the session completed,
 * and `Validate Request` still at its first version.
 */
function halfIndexed(): RecordedSession {
  const session = recorded();
  return {
    ...session,
    sessionElements: (session.sessionElements ?? []).map((each) => ({
      ...each,
      children: (each.children ?? []).map((child) => ({
        ...child,
        executionStatus: "IN_PROGRESS" as const,
        headersAfter: null,
        bodyAfter: null,
      })),
    })),
  };
}

/**
 * A client whose transport returns the next of `reads` to each read of one session, and the last
 * one once they run out. A search returns the session in a light page.
 */
function stubbedSessions(reads: RecordedSession[]): { sessions: Sessions; served: () => number } {
  let served = 0;
  const api = {
    fetch(url: string, options: { method?: string } = {}) {
      const body = options.method === "POST" && url.includes("/v1/sessions/chains/")
        ? { offset: 1, sessions: [{ ...reads[0], sessionElements: null }] }
        : reads[Math.min(served++, reads.length - 1)];
      return Promise.resolve({ ok: () => true, status: () => 200, text: () => Promise.resolve(JSON.stringify(body)) });
    },
  };
  return { sessions: new Sessions(api as unknown as APIRequestContext, { base: STUB_BASE }), served: () => served };
}

test.describe("sessions helper", { tag: ["@harness"] }, () => {
  test("the recording is a DEBUG session of http-echo", () => {
    const session = recorded();
    expect(session.chainName).toContain("http-echo");
    expect(session.executionStatus).toBe("COMPLETED_NORMALLY");
    // The correlation actually worked in the recording, which is what makes it evidence.
    expect(session.externalSessionCipId).toBe("call-recorded000001");
    // Sessions are off by default, so a recording at any other level would mean the seed's logging
    // step had not run and the trace below would be an accident.
    expect(session.loggingLevel).toBe("DEBUG");
  });

  test("trace walks the children of each element", () => {
    const session = recorded();

    // The distinction the whole helper exists for: `Validate Request` is a child of the trigger, so
    // a reader that stops at `sessionElements` reports two steps where three ran.
    expect(session.sessionElements?.map((each) => each.elementName)).toEqual([
      "HTTP Trigger",
      "Header Modification",
    ]);
    expect(elementNames(session)).toEqual([
      "HTTP Trigger",
      "Validate Request",
      "Header Modification",
    ]);
    // Depth first, parents before their children: the order a person reads an execution in.
    expect(trace(session).map((each) => each.executionStatus)).toEqual([
      "COMPLETED_NORMALLY",
      "COMPLETED_NORMALLY",
      "COMPLETED_NORMALLY",
    ]);
  });

  test("elementsOfType returns every instance of a type", () => {
    const session = recorded();

    // Two steps of one type in a three-step chain. Asserting on `camelName` here would silently
    // assert over whichever of the two the search happened to reach first.
    expect(elementsOfType(session, "http-trigger").map((each) => each.elementName)).toEqual([
      "HTTP Trigger",
      "Validate Request",
    ]);
    expect(element(session, "Header Modification")?.camelName).toBe("header-modification");
    expect(element(session, "Validate Request")?.parentElement).toBe(
      element(session, "HTTP Trigger")?.elementId,
    );
    expect(element(session, "no such element")).toBeUndefined();
  });

  test("a step carries its headers before and after it ran", () => {
    const session = recorded();
    const modification = element(session, "Header Modification");

    // The header the fixture adds appears only in `headersAfter`, which is how a trace shows that an
    // element ran rather than that it merely appeared in the route.
    expect(modification?.headersBefore?.["e2e-fixture"]).toBeUndefined();
    expect(modification?.headersAfter?.["e2e-fixture"]).toBe("zsess1");
    // The correlation token the call sent is visible on the trigger's headers, which is what makes
    // the external-id lookup a lookup rather than a guess.
    expect(element(session, "HTTP Trigger")?.headersBefore?.[CORRELATION_HEADER]).toBe(
      session.externalSessionCipId,
    );
    expect(failedElements(session)).toEqual([]);
  });

  test("a session read without details has no trace", () => {
    // Measured: `GET /v1/sessions/external-id/{token}` without the parameter answers the key present
    // and null. `"sessionElements" in session` is true for both shapes, so key presence is the one
    // test that cannot tell them apart.
    const light = { ...recorded(), sessionElements: null };

    expect("sessionElements" in light).toBe(true);
    expect(hasTrace(light)).toBe(false);
    expect(hasTrace(recorded())).toBe(true);
    // A reader over a light session answers empty rather than throwing: the caller decides whether
    // that is a failure, and `byExternalId` keeps polling until the elements arrive.
    expect(trace(light)).toEqual([]);
    expect(failedElements(light)).toEqual([]);
  });

  test("failedElements names a nested failed step", () => {
    const session = recorded();
    const broken: RecordedSession = {
      ...session,
      sessionElements: (session.sessionElements ?? []).map((each) => ({
        ...each,
        children: (each.children ?? []).map((child) => ({
          ...child,
          executionStatus: "COMPLETED_WITH_ERRORS" as const,
        })),
      })),
    };

    // The nested one, specifically: a top-level scan reports a clean run over a chain that failed.
    expect(failedElements(broken).map((each) => each.elementName)).toEqual(["Validate Request"]);
  });

  // Shown failing: dropping the step check from `byExternalId` turned its two cases red, and dropping
  // it from `settledOf` turned the `onlyOf` case red.
  test.describe("lookups", () => {
    test("a completed session with a step in progress is not settled", () => {
      expect(stepsInProgress(halfIndexed())).toEqual(["Validate Request"]);
      expect(isSettled(halfIndexed())).toBe(false);
      expect(stepsInProgress(recorded())).toEqual([]);
      expect(isSettled(recorded())).toBe(true);
      expect(isSettled({ ...recorded(), executionStatus: "IN_PROGRESS" })).toBe(false);
    });

    test("byExternalId reads again until no step is in progress", async () => {
      const { sessions, served } = stubbedSessions([halfIndexed(), recorded()]);
      const found = await sessions.byExternalId("call-recorded000001", { elements: 3 });

      expect(served()).toBe(2);
      expect(stepsInProgress(found)).toEqual([]);
      expect(element(found, "Validate Request")?.headersAfter).not.toBeNull();
    });

    test("byExternalId names the steps in progress on a timeout", async () => {
      const { sessions } = stubbedSessions([halfIndexed()]);

      await expect(sessions.byExternalId("call-recorded000001", { elements: 3, timeout: 300 })).rejects.toThrow(
        /with Validate Request still IN_PROGRESS/,
      );
    });

    test("onlyOf reads again until no step is in progress", async () => {
      const { sessions, served } = stubbedSessions([halfIndexed(), recorded()]);
      const found = await sessions.onlyOf("chain-1", 3, () => true);

      expect(served()).toBe(2);
      expect(stepsInProgress(found)).toEqual([]);
    });
  });
});
