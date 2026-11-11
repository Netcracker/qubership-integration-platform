import React from "react";
import { TestsRunView } from "../../api/apiTypes.ts";
import { formatTimestamp } from "../../misc/format-utils.ts";
import { RunStatusTag } from "./TestingTags.tsx";
import {
  auditSection,
  DetailsLink,
  DetailsSidebar,
  idItem,
  renderOptional,
} from "../DetailsSidebar.tsx";

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
  <DetailsSidebar
    title="Test Run Details"
    open={open}
    onClose={onClose}
    sections={[
      { key: "general-info", title: "general info", items: [idItem(run?.id)] },
      {
        key: "status",
        title: "status",
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
            label: "Test cases",
            value: renderOptional(run?.testCases, (v) =>
              caseRunsPath ? (
                <DetailsLink to={caseRunsPath}>{v}</DetailsLink>
              ) : (
                v
              ),
            ),
          },
          // The aggregate counts the cases that failed, not the errors they recorded.
          {
            label: "Test cases with errors",
            value: renderOptional(run?.errors),
          },
        ],
      },
      auditSection(run),
    ]}
  />
);
