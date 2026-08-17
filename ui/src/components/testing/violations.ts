import { EndpointMock, TestCase } from "../../api/apiTypes.ts";
import {
  isHttpFieldName,
  isHttpFieldValue,
} from "../../misc/http-field-utils.ts";
import { matchersViolations } from "./matchers.ts";

/**
 * What an entity carries that the save-time rules refuse, one key per offending
 * value.
 *
 * An update may keep a value the stored entity already carries: the service
 * refuses only what the caller introduces, so a row written before a rule
 * existed stays editable. An editor holds the keys of the entity it read and
 * compares them with the ones its draft produces, which keeps the save open for
 * a legacy value and shut for a value the user has just broken. Replacing a bad
 * value with a different bad value counts as introducing one, in the editors as
 * in the service.
 */
export function endpointMockViolations(
  endpointMock: EndpointMock | null,
): string[] {
  if (!endpointMock) {
    return [];
  }
  const violations = matchersViolations(endpointMock.requestMatchers);
  for (const header of endpointMock.responseSettings?.message?.headers ?? []) {
    if (!isHttpFieldName(header.name)) {
      violations.push(`response header name ${JSON.stringify(header.name)}`);
    } else if (!isHttpFieldValue(header.value)) {
      violations.push(
        `response header ${JSON.stringify(header.name)} value ${JSON.stringify(header.value)}`,
      );
    }
  }
  return violations;
}

export function testCaseViolations(testCase: TestCase | null): string[] {
  return testCase ? matchersViolations(testCase.responseValidationRules) : [];
}

/** Whether the draft carries a value the stored entity did not already carry. */
export function introducesViolation(
  draft: string[],
  stored: string[],
): boolean {
  const storedKeys = new Set(stored);
  return draft.some((violation) => !storedKeys.has(violation));
}
