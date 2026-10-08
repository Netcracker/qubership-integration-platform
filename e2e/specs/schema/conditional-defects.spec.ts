import { test, expect } from "@playwright/test";
import { MICRO_CONTAINER_PARENTS, MISPLACED_BRANCH_STEPS } from "../../support/known-defect.js";

test("the container-parents pin recognizes a misplaced async branch step and nothing else", { tag: ["@infra", "@tier1"] }, () => {
  const misplaced = `Error: ${MISPLACED_BRANCH_STEPS} "First Async Script" under Split Async\n\nexpect(received).toEqual(expected) // deep equality`;
  const slow = "Error: the answer waited for the sleeping branch\n\nexpect(received).toBeLessThan(expected)\n\nExpected: < 3000\nReceived:   3204";
  expect(MICRO_CONTAINER_PARENTS.matches(misplaced)).toBe(true);
  expect(MICRO_CONTAINER_PARENTS.matches(slow)).toBe(false);
});
