import React from "react";
import { TestingMatcher } from "../../../api/apiTypes.ts";
import { useTestCaseEditor } from "../../../pages/testing/TestCasePage.tsx";
import { MatchersTable } from "../MatchersTable.tsx";

export const TestCaseResponseValidationTab: React.FC = () => {
  const { testCase, readonly, onChange } = useTestCaseEditor();

  return (
    <MatchersTable
      kind="response"
      matchers={testCase.responseValidationRules}
      readonly={readonly}
      onChange={(responseValidationRules: TestingMatcher[]) =>
        onChange({ responseValidationRules })
      }
    />
  );
};
