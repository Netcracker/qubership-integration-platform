/**
 * `settleMicroDefects` over hand-built `TestInfo` readings: a case pinned with a `MicroDefect` stays
 * an expected failure only while every error it raised is one the defect `matches`.
 */
import { test, expect } from "@playwright/test";
import type { TestInfo } from "@playwright/test";
import { MICRO_XSLT, settleMicroDefects } from "../../support/known-defect.js";

/** The xslt case's status assertion on the micro engine, with the engine log line it puts in the message. */
const XSLT_500 =
  "Error: the xslt chain failed: org.apache.camel.NoSuchEndpointException: No endpoint could be " +
  "found for: xslt://file:/tmp/chain_tmp/e2e-vhlns6-stylesheet.xsl, please check your classpath " +
  "contains the needed Camel component jar.\n\nexpect(received).toBe(expected) // Object.is equality\n\n" +
  "Expected: 200\nReceived: 500";

const OTHER_500 =
  "Error: the xslt chain failed: no unresolved xslt endpoint in the engine log\n\n" +
  "expect(received).toBe(expected) // Object.is equality\n\nExpected: 200\nReceived: 500";

function reading(descriptions: string[], messages: string[]): TestInfo {
  return {
    expectedStatus: "failed",
    annotations: descriptions.map((description) => ({ type: "fail", description })),
    errors: messages.map((message) => ({ message })),
  } as unknown as TestInfo;
}

function narrowed(descriptions: string[], messages: string[]): string {
  const info = reading(descriptions, messages);
  settleMicroDefects(info);
  return info.expectedStatus;
}

test("a pinned micro case whose only error is the defect stays an expected failure", { tag: ["@infra", "@tier1"] }, () => {
  expect(narrowed([MICRO_XSLT.title], [XSLT_500])).toBe("failed");
});

test("a pinned micro case that failed on something else becomes a real failure", { tag: ["@infra", "@tier1"] }, () => {
  expect(narrowed([MICRO_XSLT.title], [XSLT_500.replace("500", "400")])).toBe("passed");
  // A 500 for another reason does not stand in for the xslt defect.
  expect(narrowed([MICRO_XSLT.title], [OTHER_500])).toBe("passed");
  // One matching error does not excuse a second one that matches nothing.
  expect(narrowed([MICRO_XSLT.title], [XSLT_500, "Error: timeout"])).toBe("passed");
});

test("a test.fail case pinned for anything else is left to narrow itself", { tag: ["@infra", "@tier1"] }, () => {
  expect(narrowed(["the catalog cannot read a pod status on Kubernetes v1.36"], ["Error: timeout"])).toBe("failed");
});

test("a micro case whose body reached a test.fail() of its own is left to narrow itself", { tag: ["@infra", "@tier1"] }, () => {
  // `test.fail()` with no argument, as a case adds it for a defect both engines share.
  const info = reading([MICRO_XSLT.title], ["Error: the trigger now receives the id"]);
  info.annotations.push({ type: "fail" });
  settleMicroDefects(info);
  expect(info.expectedStatus).toBe("failed");
});
