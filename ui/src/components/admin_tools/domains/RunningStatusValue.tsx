import { RunningStatus } from "../../../api/apiTypes.ts";
import { Tag } from "antd";
import React from "react";
import { capitalize } from "../../../misc/format-utils.ts";

export type RunningStatusValueProps = {
  status: RunningStatus;
};

const statusColors: Record<RunningStatus, string> = {
  [RunningStatus.RUNNING]: "green",
  [RunningStatus.PENDING]: "gold",
  [RunningStatus.FAILED]: "red",
  [RunningStatus.UNKNOWN]: "default",
};

function getStatusText(status: RunningStatus): string {
  return capitalize(status);
}

export const RunningStatusValue: React.FC<RunningStatusValueProps> = ({
  status,
}) => {
  return (
    <Tag color={statusColors[status]} variant={"outlined"}>
      {getStatusText(status)}
    </Tag>
  );
};
