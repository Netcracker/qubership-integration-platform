import React from "react";
import { Descriptions, Divider, Drawer, Typography } from "antd";
import { useNavigate } from "react-router";
import { TestCaseRunView } from "../../api/apiTypes.ts";
import { formatTimestamp } from "../../misc/format-utils.ts";
import { EMPTY } from "./testingAudit.tsx";
import { RunStatusTag } from "./TestingTags.tsx";

export type TestCaseRunDrawerProps = {
  run: TestCaseRunView | null;
  chainName: string;
  /** Route of the test case editor, absent when the run names no case. */
  testCasePath?: string;
  /** Route of the errors of this run. */
  errorsPath?: string;
  /** Route of the session, absent while it is unresolved or was not found. */
  sessionPath?: string;
  open: boolean;
  onClose: () => void;
};

export const TestCaseRunDrawer: React.FC<TestCaseRunDrawerProps> = ({
  run,
  chainName,
  testCasePath,
  errorsPath,
  sessionPath,
  open,
  onClose,
}) => {
  const navigate = useNavigate();

  return (
    <Drawer
      title="Test Case Run Details"
      placement="right"
      size={380}
      open={open}
      onClose={onClose}
      destroyOnHidden
    >
      {!run ? null : (
        <>
          <Descriptions column={1} size="small" layout="vertical" colon={false}>
            <Descriptions.Item label="Id">
              <Typography.Text copyable style={{ wordBreak: "break-all" }}>
                {run.id}
              </Typography.Text>
            </Descriptions.Item>
            <Descriptions.Item label="Test case">
              {run.testCaseName && testCasePath ? (
                <a onClick={() => void navigate(testCasePath)}>
                  {run.testCaseName}
                </a>
              ) : (
                (run.testCaseName ?? EMPTY)
              )}
            </Descriptions.Item>
            <Descriptions.Item label="Description">
              {run.testCaseDescription || EMPTY}
            </Descriptions.Item>
            <Descriptions.Item label="Chain">
              {run.chainId ? (
                <a onClick={() => void navigate(`/chains/${run.chainId}`)}>
                  {chainName || run.chainId}
                </a>
              ) : (
                EMPTY
              )}
            </Descriptions.Item>
          </Descriptions>
          <Divider style={{ margin: "12px 0" }} />
          <Descriptions column={1} size="small" layout="vertical" colon={false}>
            <Descriptions.Item label="Status">
              <RunStatusTag status={run.status} />
            </Descriptions.Item>
            <Descriptions.Item label="Start">
              {run.start ? formatTimestamp(run.start) : EMPTY}
            </Descriptions.Item>
            <Descriptions.Item label="Finish">
              {run.finish ? formatTimestamp(run.finish) : EMPTY}
            </Descriptions.Item>
            <Descriptions.Item label="Errors">
              {errorsPath ? (
                <a onClick={() => void navigate(errorsPath)}>{run.errors}</a>
              ) : (
                run.errors
              )}
            </Descriptions.Item>
          </Descriptions>
          <Divider style={{ margin: "12px 0" }} />
          <Descriptions column={1} size="small" layout="vertical" colon={false}>
            <Descriptions.Item label="Session">
              {!run.sessionId ? (
                EMPTY
              ) : sessionPath ? (
                <a onClick={() => void navigate(sessionPath)}>
                  {run.sessionId}
                </a>
              ) : (
                <Typography.Text style={{ wordBreak: "break-all" }}>
                  {run.sessionId}
                </Typography.Text>
              )}
            </Descriptions.Item>
          </Descriptions>
        </>
      )}
    </Drawer>
  );
};
