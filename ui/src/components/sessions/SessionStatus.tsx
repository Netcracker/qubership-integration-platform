import React, { useEffect, useState } from "react";
import { Badge } from "antd";
import { ExecutionStatus } from "../../api/apiTypes";
import { formatSnakeCased } from "../../misc/format-utils.ts";
import type { PresetStatusColorType } from "antd/es/_util/colors";

type SessionStatusProps = {
  status: ExecutionStatus;
  suffix?: string;
};

export const SessionStatus: React.FC<SessionStatusProps> = ({
  status,
  suffix,
}) => {
  const [color, setColor] = useState<PresetStatusColorType | undefined>(
    undefined,
  );

  useEffect(() => {
    setColor(getStatusColor());
  }, [status]);

  const getStatusColor = (): PresetStatusColorType => {
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
  };

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
