import React from "react";
import { TestCaseView } from "../../api/apiTypes.ts";
import { isTestCaseReady } from "./testCases.ts";
import { EnabledTag, ReadinessTag } from "./TestingTags.tsx";
import {
  auditSection,
  chainItem,
  DetailsSidebar,
  elementItem,
  idItem,
  renderOptional,
} from "../DetailsSidebar.tsx";

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
    <DetailsSidebar
      title="Test Case Details"
      open={open}
      onClose={onClose}
      sections={[
        {
          key: "general-info",
          title: "general info",
          items: [
            idItem(testCase?.id),
            { label: "Name", value: renderOptional(testCase?.name) },
            {
              label: "Description",
              value: renderOptional(testCase?.description),
            },
          ],
        },
        {
          key: "endpoint",
          title: "endpoint",
          items: [
            chainItem(chainId, chainName),
            elementItem("Trigger", chainId, elementId, elementName),
          ],
        },
        {
          key: "status",
          title: "status",
          items: [
            {
              label: "Enabled",
              value: renderOptional(testCase?.enabled, (v) => (
                <EnabledTag enabled={v} />
              )),
            },
            {
              label: "Readiness",
              value: renderOptional(testCase, (v) => (
                <ReadinessTag ready={isTestCaseReady(v)} />
              )),
            },
            {
              label: "Rules",
              value: renderOptional(testCase?.validationRuleCount),
            },
            {
              label: "Active Rules",
              value: renderOptional(testCase?.enabledRuleCount),
            },
          ],
        },
        auditSection(testCase),
      ]}
    />
  );
};
