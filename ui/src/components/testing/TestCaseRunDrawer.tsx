import React from "react";
import { TestCaseRunView } from "../../api/apiTypes.ts";
import { formatTimestamp } from "../../misc/format-utils.ts";
import { RunStatusTag } from "./TestingTags.tsx";
import {
  chainItem,
  DetailsLink,
  DetailsSidebar,
  idItem,
  IdText,
  Placeholder,
  renderOptional,
} from "../DetailsSidebar.tsx";

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
    return <Placeholder />;
  }
  if (sessionPath) {
    return <DetailsLink to={sessionPath}>{sessionId}</DetailsLink>;
  }
  return <IdText value={sessionId} />;
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
  <DetailsSidebar
    title="Test Case Run Details"
    open={open}
    onClose={onClose}
    sections={[
      {
        key: "general-info",
        title: "general info",
        items: [
          idItem(run?.id),
          {
            label: "Test case",
            value:
              run?.testCaseName && testCasePath ? (
                <DetailsLink to={testCasePath}>{run?.testCaseName}</DetailsLink>
              ) : (
                (run?.testCaseName ?? <Placeholder />)
              ),
          },
          {
            label: "Description",
            value: renderOptional(run?.testCaseDescription),
          },
          chainItem(run?.chainId, chainName),
        ],
      },
      {
        key: "status",
        title: "Status",
        items: [
          {
            label: "Status",
            value: renderOptional(run?.status, (v) => (
              <RunStatusTag status={v} />
            )),
          },
          {
            label: "Start",
            value: renderOptional(run?.start, formatTimestamp),
          },
          {
            label: "Finish",
            value: renderOptional(run?.finish, formatTimestamp),
          },
          {
            label: "Errors",
            value: renderOptional(run?.errors, (v) =>
              errorsPath ? <DetailsLink to={errorsPath}>{v}</DetailsLink> : v,
            ),
          },
          {
            label: "Session",
            value: sessionCell(run?.sessionId, sessionPath),
          },
        ],
      },
    ]}
  />
);
