import React from "react";
import { Typography } from "antd";
import { TestCaseRunView } from "../../api/apiTypes.ts";
import { formatTimestamp } from "../../misc/format-utils.ts";
import { EMPTY } from "./testingAudit.tsx";
import { RunStatusTag } from "./TestingTags.tsx";
import {
  chainItem,
  DetailsLink,
  idItem,
  TestingDetailsDrawer,
} from "./TestingDetailsDrawer.tsx";

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

/** The session is a link once its route is resolved, and plain text while it is not. */
function sessionCell(
  sessionId: string | null | undefined,
  sessionPath: string | undefined,
): React.ReactNode {
  if (!sessionId) {
    return EMPTY;
  }
  if (sessionPath) {
    return <DetailsLink to={sessionPath}>{sessionId}</DetailsLink>;
  }
  return (
    <Typography.Text style={{ wordBreak: "break-all" }}>
      {sessionId}
    </Typography.Text>
  );
}

export const TestCaseRunDrawer: React.FC<TestCaseRunDrawerProps> = ({
  run,
  chainName,
  testCasePath,
  errorsPath,
  sessionPath,
  open,
  onClose,
}) => (
  <TestingDetailsDrawer
    title="Test Case Run Details"
    open={open}
    onClose={onClose}
    sections={
      !run
        ? []
        : [
            [
              idItem(run.id),
              {
                label: "Test case",
                children:
                  run.testCaseName && testCasePath ? (
                    <DetailsLink to={testCasePath}>
                      {run.testCaseName}
                    </DetailsLink>
                  ) : (
                    (run.testCaseName ?? EMPTY)
                  ),
              },
              {
                label: "Description",
                children: run.testCaseDescription || EMPTY,
              },
              chainItem(run.chainId, chainName),
            ],
            [
              {
                label: "Status",
                children: <RunStatusTag status={run.status} />,
              },
              {
                label: "Start",
                children: run.start ? formatTimestamp(run.start) : EMPTY,
              },
              {
                label: "Finish",
                children: run.finish ? formatTimestamp(run.finish) : EMPTY,
              },
              {
                label: "Errors",
                children: errorsPath ? (
                  <DetailsLink to={errorsPath}>{run.errors}</DetailsLink>
                ) : (
                  run.errors
                ),
              },
            ],
            [
              {
                label: "Session",
                children: sessionCell(run.sessionId, sessionPath),
              },
            ],
          ]
    }
  />
);
