import React from "react";
import { Tag } from "antd";
import { TestingImportStatus, TestRunStatus } from "../../api/apiTypes.ts";
import { formatSnakeCased, PLACEHOLDER } from "../../misc/format-utils.ts";

export const EnabledTag: React.FC<{ enabled: boolean }> = ({ enabled }) => (
  <Tag color={enabled ? "green" : "default"}>
    {enabled ? "Enabled" : "Disabled"}
  </Tag>
);

export const ReadinessTag: React.FC<{ ready: boolean }> = ({ ready }) => (
  <Tag color={ready ? "blue" : "warning"}>{ready ? "Ready" : "Incomplete"}</Tag>
);

const RUN_STATUS_COLORS: Record<TestRunStatus, string> = {
  [TestRunStatus.PENDING]: "default",
  [TestRunStatus.RUNNING]: "processing",
  [TestRunStatus.FINISHED]: "success",
  [TestRunStatus.CANCELED]: "warning",
  [TestRunStatus.SKIPPED]: "default",
};

export const RunStatusTag: React.FC<{ status: TestRunStatus | null }> = ({
  status,
}) =>
  status ? (
    <Tag color={RUN_STATUS_COLORS[status] ?? "default"}>
      {formatSnakeCased(status)}
    </Tag>
  ) : (
    <>{PLACEHOLDER}</>
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
