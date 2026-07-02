import { ReactFlowProvider } from "@xyflow/react";
import React, { useCallback, useEffect, useMemo, useState } from "react";
import { Result, Typography } from "antd";
import { useLocation } from "react-router";
import { LibraryProvider } from "../../components/LibraryContext.tsx";
import type {
  ExportImagesStartupPayload,
  ExportImagesTarget,
} from "../../appConfig.ts";
import { BatchChainGraphExport } from "./BatchChainGraphExport.tsx";
import {
  reportExportImagesProgress,
  saveExportedImageToVsCode,
} from "./vscodeExportSink.ts";
import type {
  BatchExportItemError,
  BatchExportItemSkip,
  BatchExportSummary,
} from "./types.ts";

const hiddenRootStyle: React.CSSProperties = {
  position: "fixed",
  left: 0,
  top: 0,
  width: 1600,
  height: 900,
  opacity: 0.01,
  zIndex: -1,
  pointerEvents: "none",
  overflow: "hidden",
};

function useExportImagesRequest(): ExportImagesStartupPayload | undefined {
  const location = useLocation();
  return location.state as ExportImagesStartupPayload | undefined;
}

export const BatchExportRunner: React.FC = () => {
  const request = useExportImagesRequest();
  const targets = useMemo(() => request?.targets ?? [], [request]);
  const [currentIndex, setCurrentIndex] = useState(0);
  const [failed, setFailed] = useState<BatchExportItemError[]>([]);
  const [skipped, setSkipped] = useState<BatchExportItemSkip[]>([]);
  const [succeeded, setSucceeded] = useState(0);
  const [done, setDone] = useState(false);

  useEffect(() => {
    if (!request) return;
    void reportExportImagesProgress("exportImagesStarted", {
      total: targets.length,
      outputDir: request.exportConfig.outputDir,
    });
  }, [request, targets.length]);

  const finishBatch = useCallback((summary: BatchExportSummary) => {
    setDone(true);
    void reportExportImagesProgress("exportImagesComplete", summary);
  }, []);

  useEffect(() => {
    if (!request || done || currentIndex < targets.length) return;
    finishBatch({ total: targets.length, succeeded, skipped, failed });
  }, [
    currentIndex,
    done,
    failed,
    skipped,
    finishBatch,
    request,
    succeeded,
    targets.length,
  ]);

  const advance = useCallback(() => {
    setCurrentIndex((index) => index + 1);
  }, []);

  const handleComplete = useCallback(
    (result: {
      target: ExportImagesTarget;
      fileName: string;
      contentBase64: string;
    }) => {
      if (!request) return;

      const run = async () => {
        const current = currentIndex + 1;
        await reportExportImagesProgress("exportImagesProgress", {
          current,
          total: targets.length,
          fileName: result.fileName,
          percentage: Math.round((current / targets.length) * 100),
        });
        await saveExportedImageToVsCode(request.exportConfig.outputDir, result);
        setSucceeded((value) => value + 1);
        await reportExportImagesProgress("exportImagesItemDone", {
          target: result.target,
          fileName: result.fileName,
        });
        advance();
      };

      void run().catch((error) => {
        setFailed((items) => [
          ...items,
          {
            target: result.target,
            message: error instanceof Error ? error.message : String(error),
          },
        ]);
        void reportExportImagesProgress("exportImagesItemFailed", {
          target: result.target,
          error: error instanceof Error ? error.message : String(error),
        });
        advance();
      });
    },
    [advance, currentIndex, request, targets.length],
  );

  const handleSkip = useCallback(
    (target: ExportImagesTarget) => {
      const label = target.outputName ?? target.chainId;
      const message = `Chain "${label}" is empty. No image file was created.`;
      const current = currentIndex + 1;

      void reportExportImagesProgress("exportImagesProgress", {
        current,
        total: targets.length,
        fileName: label,
      });
      void reportExportImagesProgress("exportImagesItemWarning", {
        target,
        reason: "empty_chain",
        message,
      });
      setSkipped((items) => [...items, { target, message }]);
      advance();
    },
    [advance, currentIndex, targets.length],
  );

  const handleError = useCallback(
    (error: unknown) => {
      const target = targets[currentIndex];
      if (!target) return;
      const message = error instanceof Error ? error.message : String(error);
      setFailed((items) => [...items, { target, message }]);
      void reportExportImagesProgress("exportImagesItemFailed", {
        target,
        error: message,
      });
      advance();
    },
    [advance, currentIndex, targets],
  );

  if (!request) {
    return (
      <Result
        status="error"
        title="Export request is missing"
        subTitle="The batch export route must be opened from the VS Code exportImages command."
      />
    );
  }

  if (!targets.length) {
    return (
      <Result
        status="error"
        title="No export targets provided"
        subTitle="Provide targets when calling qip.exportImages."
      />
    );
  }

  if (done) {
    const skippedCount = skipped.length;
    const failedCount = failed.length;
    const status = failedCount > 0 || skippedCount > 0 ? "warning" : "success";
    const parts = [`${succeeded} of ${targets.length} image file(s) exported.`];
    if (skippedCount > 0) {
      parts.push(`${skippedCount} empty chain(s) skipped.`);
    }
    if (failedCount > 0) {
      parts.push(`${failedCount} failed.`);
    }

    return (
      <Result
        status={status}
        title="Export completed"
        subTitle={parts.join(" ")}
      />
    );
  }

  const target = targets[currentIndex];

  return (
    <>
      <Result
        title="Exporting chain PNG files"
        subTitle={`Processing ${currentIndex + 1} of ${targets.length}`}
      >
        <Typography.Text type="secondary">
          {target?.outputName ?? target?.chainId}
        </Typography.Text>
      </Result>
      {target ? (
        <div style={hiddenRootStyle}>
          <LibraryProvider>
            <ReactFlowProvider>
              <BatchChainGraphExport
                key={`${target.chainId}:${currentIndex}`}
                target={target}
                imageFormat={request.exportConfig.imageFormat ?? "png"}
                onComplete={handleComplete}
                onSkip={handleSkip}
                onError={handleError}
              />
            </ReactFlowProvider>
          </LibraryProvider>
        </div>
      ) : null}
    </>
  );
};
