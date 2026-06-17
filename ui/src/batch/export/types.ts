import type {
  ExportImagesStartupPayload,
  ExportImagesTarget,
} from "../../appConfig.ts";

export type BatchExportRequest = ExportImagesStartupPayload;

export type BatchExportItemResult = {
  target: ExportImagesTarget;
  fileName: string;
  contentBase64: string;
};

export type BatchExportItemError = {
  target: ExportImagesTarget;
  message: string;
};

export type BatchExportItemSkip = {
  target: ExportImagesTarget;
  message: string;
};

export type BatchExportSummary = {
  total: number;
  succeeded: number;
  skipped: BatchExportItemSkip[];
  failed: BatchExportItemError[];
};
