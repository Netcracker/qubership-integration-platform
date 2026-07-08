import { formatSnakeCased } from "../../misc/format-utils.ts";
import React from "react";
import { Tag, theme, Tooltip } from "antd";
import {
  BulkDeploymentStatus,
  ImportEntityStatus,
  ImportInstructionAction,
  ImportInstructionStatus,
  SystemImportStatus,
} from "../../api/apiTypes.ts";
import type { PresetStatusColor } from "../../types/antd.ts";

type CombinedStatus =
  | ImportEntityStatus
  | SystemImportStatus
  | ImportInstructionStatus
  | ImportInstructionAction
  | BulkDeploymentStatus;

type StatusColor = PresetStatusColor | "neutral";

function getStatusColor(status?: CombinedStatus): StatusColor {
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
  const { token } = theme.useToken();
  const statusColor = getStatusColor(status);
  const neutral = statusColor === "neutral";
  const color = neutral ? undefined : statusColor;
  const neutralStyle: React.CSSProperties | undefined = neutral
    ? {
        backgroundColor: token.colorFillQuaternary,
        borderColor: token.colorBorderSecondary,
        color: token.colorTextSecondary,
      }
    : undefined;

  const statusNode = (
    <Tag variant="solid" color={color} style={neutralStyle}>
      {formatSnakeCased(status ?? "")}
    </Tag>
  );
  return message ? <Tooltip title={message}>{statusNode}</Tooltip> : statusNode;
};
