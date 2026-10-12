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
 * The live lookup is not exercised here. It needs port 8093, and this project is defined as needing
 * no stack; `specs/runtime/http-echo.spec.ts` is where the same helper is proven against the engine.
 */
import { test, expect } from "@playwright/test";
import fs from "node:fs";
import { RECORDED_SESSION_FIXTURE } from "../../fixtures/templating.js";
import {
  CORRELATION_HEADER,
  SESSION_TIMEOUT,
  element,
  elementNames,
  elementsOfType,
  failedElements,
  hasTrace,
  trace,
  type RecordedSession,
} from "../../support/sessions.js";

function recorded(): RecordedSession {
  return JSON.parse(fs.readFileSync(RECORDED_SESSION_FIXTURE, "utf-8")) as RecordedSession;
}

test("the committed recording is a session of the http-echo fixture", { tag: ["@infra", "@tier1"] }, () => {
  const session = recorded();
  expect(session.chainName).toContain("http-echo");
  expect(session.executionStatus).toBe("COMPLETED_NORMALLY");
  // The correlation actually worked in the recording, which is what makes it evidence.
  expect(session.externalSessionCipId).toBe("call-recorded000001");
  // Sessions are off by default, so a recording at any other level would mean the seed's logging
  // step had not run and the trace below would be an accident.
  expect(session.loggingLevel).toBe("DEBUG");
});

test("the trace walks children, not only the top level", { tag: ["@infra", "@tier1"] }, () => {
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

test("camelName names a type and repeats; elementName names the instance", { tag: ["@infra", "@tier1"] }, () => {
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

test("a step carries what the fixture did, so a spec can assert the branch and not only the code", { tag: ["@infra", "@tier1"] }, () => {
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

test("a lookup without includeDetails is not a session with no elements", { tag: ["@infra", "@tier1"] }, () => {
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

test("a failed step is reported by name", { tag: ["@infra", "@tier1"] }, () => {
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

test("the indexing lag budget stays above what was measured", { tag: ["@infra", "@tier1"] }, () => {
  // A session is queryable roughly 1-2 s after the chain answers 200. A budget anywhere near that
  // flakes on its own, and this is the one place the number is visible without a stack.
  expect(SESSION_TIMEOUT).toBeGreaterThanOrEqual(10_000);
});
