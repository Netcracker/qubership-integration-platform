import { RunningStatus } from "../../../api/apiTypes.ts";
import { Tag } from "antd";
import React from "react";

export type RunningStatusValueProps = {
  status: RunningStatus;
};

const statusColors: Record<RunningStatus, string> = {
  [RunningStatus.RUNNING]: "green",
  [RunningStatus.PENDING]: "gold",
  [RunningStatus.FAILED]: "red",
  [RunningStatus.UNKNOWN]: "default",
};

export const RunningStatusValue: React.FC<RunningStatusValueProps> = ({
  status,
}) => {
  return <Tag color={statusColors[status]}>{status}</Tag>;
};
