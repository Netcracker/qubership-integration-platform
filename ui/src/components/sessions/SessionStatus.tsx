import React from "react";
import { Badge } from "antd";
import { ExecutionStatus } from "../../api/apiTypes";
import { formatSnakeCased } from "../../misc/format-utils.ts";
import type { PresetStatusColor } from "../../types/antd.ts";

type SessionStatusProps = {
  status: ExecutionStatus;
  suffix?: string;
};

function getStatusColor(status: ExecutionStatus): PresetStatusColor {
  switch (status) {
    case ExecutionStatus.IN_PROGRESS:
      return "processing";
    case ExecutionStatus.COMPLETED_NORMALLY:
      return "success";
    case ExecutionStatus.COMPLETED_WITH_ERRORS:
      return "error";
    case ExecutionStatus.COMPLETED_WITH_WARNINGS:
      return "warning";
    case ExecutionStatus.CANCELLED_OR_UNKNOWN:
      return "default";
    default:
      return "default";
  }
}

export const SessionStatus: React.FC<SessionStatusProps> = ({
  status,
  suffix,
}) => {
  const color = getStatusColor(status);

  return (
    <Badge
      status={color}
      text={
        suffix
          ? `${formatSnakeCased(status)} ${suffix}`
          : formatSnakeCased(status)
      }
    ></Badge>
  );
};
