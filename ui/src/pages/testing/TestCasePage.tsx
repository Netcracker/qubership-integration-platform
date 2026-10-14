import React, { useMemo } from "react";
import { Breadcrumb, Empty, Flex, Skeleton, Tabs } from "antd";
import { Outlet, useOutletContext, useParams } from "react-router";
import { api } from "../../api/api.ts";
import { TestCase, TestCaseRequest } from "../../api/apiTypes.ts";
import { testCaseViolations } from "../../components/testing/violations.ts";
import {
  TestingEntityEditorConfig,
  useTestingEntityEditor,
} from "../../hooks/testing/useTestingEntityEditor.tsx";

const TABS = [
  { key: "general", label: "General" },
  { key: "request", label: "Request Parameters" },
  { key: "response-validation", label: "Response Validation" },
];

/** State the routed sub-tabs share with the editor that owns the draft. */
export type TestCaseEditorContext = {
  testCase: TestCase;
  /** Set when the editor was reached inside a chain; absent in the admin scope. */
  chainId?: string;
  readonly: boolean;
  onChange: (changes: Partial<TestCase>) => void;
};

export function useTestCaseEditor(): TestCaseEditorContext {
  return useOutletContext<TestCaseEditorContext>();
}

function toRequest(testCase: TestCase): TestCaseRequest {
  return {
    name: testCase.name.trim(),
    description: testCase.description,
    enabled: testCase.enabled,
    triggerReference: testCase.triggerReference,
    requestSettings: testCase.requestSettings,
    responseValidationRules: testCase.responseValidationRules,
  };
}

function isValid(testCase: TestCase): boolean {
  return (
    testCase.name.trim().length > 0 &&
    !!testCase.triggerReference?.chainId &&
    !!testCase.triggerReference?.elementId &&
    !!testCase.requestSettings?.method
  );
}

const EDITOR: TestingEntityEditorConfig<TestCase, TestCaseRequest> = {
  listSegment: "test-cases",
  tabs: TABS,
  nouns: { singular: "test case", listTitle: "Test Cases" },
  saveTestId: "test-case-save",
  get: (id) => api.getTestCase(id),
  update: (id, request) => api.updateTestCase(id, request),
  toRequest,
  violations: testCaseViolations,
  isValid,
};

export const TestCasePage: React.FC = () => {
  const { chainId, testCaseId } = useParams<{
    chainId?: string;
    testCaseId: string;
  }>();
  const {
    entity: testCase,
    loading,
    readonly,
    onChange,
    activeTab,
    onTabChange,
    breadcrumbItems,
  } = useTestingEntityEditor({ ...EDITOR, chainId, entityId: testCaseId });

  const editorContext = useMemo<TestCaseEditorContext | null>(
    () => (testCase ? { testCase, chainId, readonly, onChange } : null),
    [testCase, chainId, readonly, onChange],
  );

  if (loading) {
    return <Skeleton active />;
  }
  if (!testCase || !editorContext) {
    return <Empty description="Test case not found" />;
  }

  return (
    <Flex vertical gap={8} style={{ flex: 1, minWidth: 0 }}>
      <Breadcrumb items={breadcrumbItems} />
      <Tabs activeKey={activeTab} items={TABS} onChange={onTabChange} />
      <Outlet context={editorContext} />
    </Flex>
  );
};

export default TestCasePage;
