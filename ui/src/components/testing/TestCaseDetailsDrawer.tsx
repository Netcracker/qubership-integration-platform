import React from "react";
import { Descriptions, Divider, Drawer, Typography } from "antd";
import { useNavigate } from "react-router";
import { TestCaseView } from "../../api/apiTypes.ts";
import { EMPTY, formatAudit } from "./testingAudit.tsx";
import { isTestCaseReady } from "./testCases.ts";
import { EnabledTag, ReadinessTag } from "./TestingTags.tsx";

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
  const navigate = useNavigate();
  const chainId = testCase?.triggerReference?.chainId;
  const elementId = testCase?.triggerReference?.elementId;

  return (
    <Drawer
      title="Test Case Details"
      placement="right"
      size={380}
      open={open}
      onClose={onClose}
      destroyOnHidden
    >
      {!testCase ? null : (
        <>
          <Descriptions column={1} size="small" layout="vertical" colon={false}>
            <Descriptions.Item label="Id">
              <Typography.Text copyable style={{ wordBreak: "break-all" }}>
                {testCase.id}
              </Typography.Text>
            </Descriptions.Item>
            <Descriptions.Item label="Name">
              {testCase.name || EMPTY}
            </Descriptions.Item>
            <Descriptions.Item label="Description">
              {testCase.description || EMPTY}
            </Descriptions.Item>
          </Descriptions>
          <Divider style={{ margin: "12px 0" }} />
          <Descriptions column={1} size="small" layout="vertical" colon={false}>
            <Descriptions.Item label="Chain">
              {chainId ? (
                <a onClick={() => void navigate(`/chains/${chainId}`)}>
                  {chainName || chainId}
                </a>
              ) : (
                EMPTY
              )}
            </Descriptions.Item>
            <Descriptions.Item label="Trigger">
              {chainId && elementId ? (
                <a
                  onClick={() =>
                    void navigate(`/chains/${chainId}/graph/${elementId}`)
                  }
                >
                  {elementName || elementId}
                </a>
              ) : (
                EMPTY
              )}
            </Descriptions.Item>
          </Descriptions>
          <Divider style={{ margin: "12px 0" }} />
          <Descriptions column={1} size="small" layout="vertical" colon={false}>
            <Descriptions.Item label="Status">
              <EnabledTag enabled={testCase.enabled} />
            </Descriptions.Item>
            <Descriptions.Item label="Readiness">
              <ReadinessTag ready={isTestCaseReady(testCase)} />
            </Descriptions.Item>
            <Descriptions.Item label="Rules">
              {testCase.validationRuleCount}
            </Descriptions.Item>
            <Descriptions.Item label="Active rules">
              {testCase.enabledRuleCount}
            </Descriptions.Item>
          </Descriptions>
          <Divider style={{ margin: "12px 0" }} />
          <Descriptions column={1} size="small" layout="vertical" colon={false}>
            <Descriptions.Item label="Created">
              {formatAudit(testCase.createdBy, testCase.createdAt)}
            </Descriptions.Item>
            <Descriptions.Item label="Updated">
              {formatAudit(testCase.updatedBy, testCase.updatedAt)}
            </Descriptions.Item>
          </Descriptions>
        </>
      )}
    </Drawer>
  );
};
