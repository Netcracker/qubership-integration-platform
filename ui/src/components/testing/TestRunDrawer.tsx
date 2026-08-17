import React from "react";
import { Descriptions, Divider, Drawer, Typography } from "antd";
import { useNavigate } from "react-router";
import { TestsRunView } from "../../api/apiTypes.ts";
import { formatTimestamp } from "../../misc/format-utils.ts";
import { EMPTY, formatAudit } from "./testingAudit.tsx";
import { RunStatusTag } from "./TestingTags.tsx";

export type TestRunDrawerProps = {
  run: TestsRunView | null;
  /** Route of the case runs this run assembled. */
  caseRunsPath?: string;
  open: boolean;
  onClose: () => void;
};

export const TestRunDrawer: React.FC<TestRunDrawerProps> = ({
  run,
  caseRunsPath,
  open,
  onClose,
}) => {
  const navigate = useNavigate();

  return (
    <Drawer
      title="Test Run Details"
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
            <Descriptions.Item label="Status">
              <RunStatusTag status={run.status} />
            </Descriptions.Item>
          </Descriptions>
          <Divider style={{ margin: "12px 0" }} />
          <Descriptions column={1} size="small" layout="vertical" colon={false}>
            <Descriptions.Item label="Start">
              {run.start ? formatTimestamp(run.start) : EMPTY}
            </Descriptions.Item>
            <Descriptions.Item label="Finish">
              {run.finish ? formatTimestamp(run.finish) : EMPTY}
            </Descriptions.Item>
            <Descriptions.Item label="Test cases">
              {caseRunsPath ? (
                <a onClick={() => void navigate(caseRunsPath)}>
                  {run.testCases}
                </a>
              ) : (
                run.testCases
              )}
            </Descriptions.Item>
            {/* The aggregate counts the cases that failed, not the errors they recorded. */}
            <Descriptions.Item label="Test cases with errors">
              {run.errors}
            </Descriptions.Item>
          </Descriptions>
          <Divider style={{ margin: "12px 0" }} />
          <Descriptions column={1} size="small" layout="vertical" colon={false}>
            <Descriptions.Item label="Created">
              {formatAudit(run.createdBy, run.createdAt)}
            </Descriptions.Item>
            <Descriptions.Item label="Updated">
              {formatAudit(run.updatedBy, run.updatedAt)}
            </Descriptions.Item>
          </Descriptions>
        </>
      )}
    </Drawer>
  );
};
