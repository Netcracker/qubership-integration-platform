import React from "react";
import { TestsRunView } from "../../api/apiTypes.ts";
import { formatTimestamp } from "../../misc/format-utils.ts";
import { EMPTY } from "./testingAudit.tsx";
import { RunStatusTag } from "./TestingTags.tsx";
import {
  auditSection,
  DetailsLink,
  idItem,
  TestingDetailsDrawer,
} from "./TestingDetailsDrawer.tsx";

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
}) => (
  <TestingDetailsDrawer
    title="Test Run Details"
    open={open}
    onClose={onClose}
    sections={
      !run
        ? []
        : [
            [
              idItem(run.id),
              {
                label: "Status",
                children: <RunStatusTag status={run.status} />,
              },
            ],
            [
              {
                label: "Start",
                children: run.start ? formatTimestamp(run.start) : EMPTY,
              },
              {
                label: "Finish",
                children: run.finish ? formatTimestamp(run.finish) : EMPTY,
              },
              {
                label: "Test cases",
                children: caseRunsPath ? (
                  <DetailsLink to={caseRunsPath}>{run.testCases}</DetailsLink>
                ) : (
                  run.testCases
                ),
              },
              // The aggregate counts the cases that failed, not the errors they recorded.
              { label: "Test cases with errors", children: run.errors },
            ],
            auditSection(run),
          ]
    }
  />
);
