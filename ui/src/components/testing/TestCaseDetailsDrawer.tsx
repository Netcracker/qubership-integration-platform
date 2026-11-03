import React from "react";
import { TestCaseView } from "../../api/apiTypes.ts";
import { EMPTY } from "./testingAudit.tsx";
import { isTestCaseReady } from "./testCases.ts";
import { EnabledTag, ReadinessTag } from "./TestingTags.tsx";
import {
  auditSection,
  chainItem,
  elementItem,
  idItem,
  TestingDetailsDrawer,
} from "./TestingDetailsDrawer.tsx";

export type TestCaseDetailsDrawerProps = {
  testCase: TestCaseView | null;
  chainName: string;
  elementName: string;
  open: boolean;
  onClose: () => void;
};

export const TestCaseDetailsDrawer: React.FC<TestCaseDetailsDrawerProps> = ({
  testCase,
  chainName,
  elementName,
  open,
  onClose,
}) => {
  const chainId = testCase?.triggerReference?.chainId;
  const elementId = testCase?.triggerReference?.elementId;

  return (
    <TestingDetailsDrawer
      title="Test Case Details"
      open={open}
      onClose={onClose}
      sections={
        !testCase
          ? []
          : [
              {
                title: "general info",
                items: [
                  idItem(testCase.id),
                  { label: "Name", children: testCase.name || EMPTY },
                  {
                    label: "Description",
                    children: testCase.description || EMPTY,
                  },
                ],
              },
              {
                title: "endpoint",
                items: [
                  chainItem(chainId, chainName),
                  elementItem("Trigger", chainId, elementId, elementName),
                ],
              },
              {
                title: "status",
                items: [
                  {
                    label: "Enabled",
                    children: <EnabledTag enabled={testCase.enabled} />,
                  },
                  {
                    label: "Readiness",
                    children: (
                      <ReadinessTag ready={isTestCaseReady(testCase)} />
                    ),
                  },
                  { label: "Rules", children: testCase.validationRuleCount },
                  {
                    label: "Active rules",
                    children: testCase.enabledRuleCount,
                  },
                ],
              },
              auditSection(testCase),
            ]
      }
    />
  );
};
