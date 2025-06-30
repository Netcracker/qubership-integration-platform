import { formatSnakeCased } from "../../misc/format-utils.ts";
import React, { useEffect, useState } from "react";
import { Tag, Tooltip } from "antd";
import {
  ImportEntityStatus,
  ImportInstructionAction,
  ImportInstructionStatus,
  SystemImportStatus,
} from "../../api/apiTypes.ts";
import type { PresetStatusColorType } from "antd/es/_util/colors";

export type CombinedImportStatus =
  | ImportEntityStatus
  | SystemImportStatus
  | ImportInstructionStatus
  | ImportInstructionAction;

export type ImportStatusProps = {
  status?: CombinedImportStatus;
  message?: string;
};

function getStatusColor(status: CombinedImportStatus): PresetStatusColorType {
  switch (status) {
    case SystemImportStatus.CREATED:
    case ImportEntityStatus.CREATED:
      return "success";
    case ImportInstructionStatus.OVERRIDDEN:
    case SystemImportStatus.UPDATED:
    case ImportEntityStatus.UPDATED:
    case ImportInstructionAction.OVERRIDE:
      return "processing";
    case ImportInstructionStatus.NO_ACTION:
    case ImportInstructionStatus.IGNORED:
    case SystemImportStatus.NO_ACTION:
    case SystemImportStatus.IGNORED:
    case ImportEntityStatus.IGNORED:
    case ImportInstructionAction.IGNORE:
      return "default";
    case ImportEntityStatus.SKIPPED:
    case ImportInstructionStatus.DELETED:
    case ImportInstructionAction.DELETE:
      return "warning";
    case ImportInstructionStatus.ERROR_ON_OVERRIDE:
    case ImportInstructionStatus.ERROR_ON_DELETE:
    case SystemImportStatus.ERROR:
    case ImportEntityStatus.ERROR:
      return "error";
    default:
      return "default";
  }
}

export const ImportStatus: React.FC<ImportStatusProps> = ({
  status,
  message,
}) => {
  const [color, setColor] = useState<PresetStatusColorType>("default");

  useEffect(() => {
    setColor(status ? getStatusColor(status) : "default");
  }, [status]);

  const statusNode = <Tag color={color}>{formatSnakeCased(status ?? "")}</Tag>;
  return message ? <Tooltip title={message}>{statusNode}</Tooltip> : statusNode;
};
