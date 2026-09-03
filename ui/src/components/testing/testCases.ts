import { TestCase } from "../../api/apiTypes.ts";

/**
 * Whether a run would have anything to do with the case: it names a trigger,
 * carries request settings, and has at least one enabled validation rule.
 *
 * Nothing server-side reports this, and no selection feature covers it either,
 * so readiness is a display-only column.
 */
export function isTestCaseReady(testCase: TestCase): boolean {
  return Boolean(
    testCase.triggerReference?.chainId &&
      testCase.triggerReference?.elementId &&
      testCase.requestSettings &&
      testCase.responseValidationRules?.some((rule) => rule.enabled),
  );
}
