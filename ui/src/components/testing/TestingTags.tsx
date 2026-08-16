import React from "react";
import { Tag } from "antd";
import { TestingImportStatus } from "../../api/apiTypes.ts";

export const EnabledTag: React.FC<{ enabled: boolean }> = ({ enabled }) => (
  <Tag color={enabled ? "green" : "default"}>
    {enabled ? "Enabled" : "Disabled"}
  </Tag>
);

export const ReadinessTag: React.FC<{ ready: boolean }> = ({ ready }) => (
  <Tag color={ready ? "blue" : "warning"}>{ready ? "Ready" : "Incomplete"}</Tag>
);

const IMPORT_STATUS_COLORS: Record<TestingImportStatus, string> = {
  [TestingImportStatus.CREATED]: "green",
  [TestingImportStatus.UPDATED]: "blue",
  [TestingImportStatus.ERROR]: "error",
};

const IMPORT_STATUS_LABELS: Record<TestingImportStatus, string> = {
  [TestingImportStatus.CREATED]: "Created",
  [TestingImportStatus.UPDATED]: "Updated",
  [TestingImportStatus.ERROR]: "Error",
};

export const ImportResultTag: React.FC<{ status: TestingImportStatus }> = ({
  status,
}) => (
  <Tag color={IMPORT_STATUS_COLORS[status] ?? "default"}>
    {IMPORT_STATUS_LABELS[status] ?? status}
  </Tag>
);
