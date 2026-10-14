/**
 * The `matches` of every `ConditionalDefect`, against the failure messages it has to recognize and the
 * ones it must leave a real failure. No stack.
 */
import { test, expect } from "@playwright/test";
import { MOCK_BYPASSED } from "../runtime/service-call.js";
import { CONTEXT_STORE_UNTRANSACTED } from "../runtime/context-storage.js";

const TAGS = { tag: ["@infra", "@tier1"] };

test("a mocked service call that reached its real address is the known defect, and an unmatched mock is not", TAGS, () => {
  // The two failures the micro engine produced, as `strikesAsKnown` reads them.
  const unresolved = [
    "Error: {",
    '  "code" : "QIP-0101",',
    '  "reason" : "Chain execution failed due to requesting missing endpoint",',
    "}",
    "",
    "expect(received).toBe(expected) // Object.is equality",
  ].join("\n");
  const egress = "Error: no healthy upstream\n\nexpect(received).toBe(expected) // Object.is equality\n\nExpected: 201\nReceived: 503";
  // A test case run of `http-out`, whose rule read the real endpoint's answer.
  const health = [
    `Error: the case run's outbound call was not mocked: '{"status":"UP","groups":["liveness","readiness"],"components":{"ping":{"status":"UP"}}}' doesn't contain '"mocked":"e2e-s7rodt-runs-mocked"'`,
    "",
    "expect(received).toEqual(expected) // deep equality",
  ].join("\n");
  expect(MOCK_BYPASSED.matches(unresolved)).toBe(true);
  expect(MOCK_BYPASSED.matches(egress)).toBe(true);
  expect(MOCK_BYPASSED.matches(health)).toBe(true);

  // The testing service answers a request no mock matched with 404.
  expect(MOCK_BYPASSED.matches("Error: no mock matched: the element sent another path, query or body. \n\nExpected: 201\nReceived: 404")).toBe(false);
  expect(MOCK_BYPASSED.matches("Error: expect(received).toBe(expected)\n\nExpected: 201\nReceived: 500")).toBe(false);
  // The mock answered, with another body than the rule expects.
  expect(MOCK_BYPASSED.matches(`Error: the case run's outbound call was not mocked: '{"mocked":"other"}' doesn't contain '"mocked":"e2e-s7rodt-runs-mocked"'`)).toBe(false);
});

test("a context SET the micro engine ran outside a transaction is the known defect, and another failure is not", TAGS, () => {
  // The failure as `expectStored` in misc-elements.spec.ts reports it: the step's recorded exception.
  const untransacted = [
    "Error: Transaction is not active, consider adding @Transactional to your method to automatically activate one.",
    "",
    "expect(received).toBe(expected) // Object.is equality",
    "",
    "Expected: 200",
    "Received: 500",
  ].join("\n");
  expect(CONTEXT_STORE_UNTRANSACTED.matches(untransacted)).toBe(true);

  expect(CONTEXT_STORE_UNTRANSACTED.matches("Error: expect(received).toBe(expected)\n\nExpected: 200\nReceived: 500")).toBe(false);
  expect(CONTEXT_STORE_UNTRANSACTED.matches("Error: Connection refused\n\nExpected: 200\nReceived: 500")).toBe(false);
});
