import React from "react";
import { TestingImportStatus, TestRunStatus } from "../../api/apiTypes.ts";
import { StatusToneTag, type StatusTone } from "../labels/StatusToneTag.tsx";
import { formatSnakeCased, PLACEHOLDER } from "../../misc/format-utils.ts";

export const EnabledTag: React.FC<{ enabled: boolean }> = ({ enabled }) => (
  <StatusToneTag tone={enabled ? "success" : "neutral"}>
    {enabled ? "Enabled" : "Disabled"}
  </StatusToneTag>
);

export const ReadinessTag: React.FC<{ ready: boolean }> = ({ ready }) => (
  <StatusToneTag tone={ready ? "processing" : "warning"}>
    {ready ? "Ready" : "Incomplete"}
  </StatusToneTag>
);

const RUN_STATUS_TONES: Record<TestRunStatus, StatusTone> = {
  [TestRunStatus.PENDING]: "neutral",
  [TestRunStatus.RUNNING]: "processing",
  [TestRunStatus.FINISHED]: "success",
  [TestRunStatus.CANCELED]: "warning",
  [TestRunStatus.SKIPPED]: "neutral",
};

export const RunStatusTag: React.FC<{ status: TestRunStatus | null }> = ({
  status,
}) =>
  status ? (
    <StatusToneTag tone={RUN_STATUS_TONES[status]}>
      {formatSnakeCased(status)}
    </StatusToneTag>
  ) : (
    <>{PLACEHOLDER}</>
  );

const IMPORT_STATUS_TONES: Record<TestingImportStatus, StatusTone> = {
  [TestingImportStatus.CREATED]: "success",
  [TestingImportStatus.UPDATED]: "processing",
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
  <StatusToneTag tone={IMPORT_STATUS_TONES[status]}>
    {IMPORT_STATUS_LABELS[status]}
  </StatusToneTag>
);
