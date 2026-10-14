import { formatSnakeCased } from "../../misc/format-utils.ts";
import React from "react";
import { Tooltip } from "antd";
import {
  BulkDeploymentStatus,
  ImportEntityStatus,
  ImportInstructionAction,
  ImportInstructionStatus,
  SystemImportStatus,
} from "../../api/apiTypes.ts";
import { StatusToneTag, type StatusTone } from "./StatusToneTag.tsx";

type CombinedStatus =
  | ImportEntityStatus
  | SystemImportStatus
  | ImportInstructionStatus
  | ImportInstructionAction
  | BulkDeploymentStatus;

function getStatusColor(status?: CombinedStatus): StatusTone {
  if (!status) return "neutral";

  switch (status) {
    case SystemImportStatus.CREATED:
    case ImportEntityStatus.CREATED:
    case BulkDeploymentStatus.CREATED:
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
    case BulkDeploymentStatus.IGNORED:
      return "neutral";
    case ImportEntityStatus.SKIPPED:
    case ImportInstructionStatus.DELETED:
    case ImportInstructionAction.DELETE:
      return "warning";
    case ImportInstructionStatus.ERROR_ON_OVERRIDE:
    case ImportInstructionStatus.ERROR_ON_DELETE:
    case SystemImportStatus.ERROR:
    case ImportEntityStatus.ERROR:
    case BulkDeploymentStatus.FAILED_DEPLOY:
    case BulkDeploymentStatus.FAILED_SNAPSHOT:
      return "error";
    default:
      return "neutral";
  }
}

export const StatusTag: React.FC<{
  status?: CombinedStatus;
  message?: string;
}> = ({ status, message }) => {
  const statusNode = (
    <StatusToneTag tone={getStatusColor(status)}>
      {formatSnakeCased(status ?? "")}
    </StatusToneTag>
  );
  return message ? <Tooltip title={message}>{statusNode}</Tooltip> : statusNode;
};
