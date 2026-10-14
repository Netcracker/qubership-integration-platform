/**
 * The `matches` of every `ConditionalDefect`, against the failure messages it has to recognize and the
 * ones it must leave a real failure. No stack.
 */
import { test, expect } from "@playwright/test";
import { MOCK_BYPASSED } from "../runtime/service-call.js";

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
  expect(MOCK_BYPASSED.matches(unresolved)).toBe(true);
  expect(MOCK_BYPASSED.matches(egress)).toBe(true);

  // The testing service answers a request no mock matched with 404.
  expect(MOCK_BYPASSED.matches("Error: no mock matched: the element sent another path, query or body. \n\nExpected: 201\nReceived: 404")).toBe(false);
  expect(MOCK_BYPASSED.matches("Error: expect(received).toBe(expected)\n\nExpected: 201\nReceived: 500")).toBe(false);
});
