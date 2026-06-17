import { ReactFlow, useReactFlow } from "@xyflow/react";
import React, { useEffect, useMemo, useRef } from "react";
import { Chain } from "../../api/apiTypes.ts";
import { nodeTypes } from "../../components/graph/nodes/ChainGraphNodeTypes.ts";
import { captureGraphImage } from "../../hooks/graph/captureGraphImage.ts";
import {
  applyLightThemeFlip,
  isDarkThemeActive,
} from "../../hooks/graph/exportTheme.ts";
import { useChainGraph } from "../../hooks/graph/useChainGraph.tsx";
import { useChain } from "../../hooks/useChain.tsx";
import { sanitizeEdge } from "../../misc/chain-graph-utils.ts";
import { ChainContext } from "../../pages/ChainPage.tsx";
import type { ImageFormat } from "../../hooks/graph/captureGraphImage.ts";
import { prepareBatchGraphExport } from "./prepareBatchGraphExport.ts";
import { reportExportImagesProgress } from "./vscodeExportSink.ts";
import type { ExportImagesTarget } from "../../appConfig.ts";
import type { BatchExportItemResult } from "./types.ts";

function arrayBufferToBase64(buffer: ArrayBuffer): string {
  const bytes = new Uint8Array(buffer);
  const chunkSize = 0x8000;
  let binary = "";
  for (let i = 0; i < bytes.length; i += chunkSize) {
    binary += String.fromCharCode(...bytes.subarray(i, i + chunkSize));
  }
  return btoa(binary);
}

type BatchChainGraphExportProps = {
  target: ExportImagesTarget;
  imageFormat?: ImageFormat;
  onComplete: (result: BatchExportItemResult) => void;
  onSkip: (target: ExportImagesTarget) => void;
  onError: (error: unknown) => void;
};

const hiddenGraphStyle: React.CSSProperties = {
  position: "fixed",
  left: -20_000,
  top: 0,
  width: 1600,
  height: 900,
  opacity: 1,
  pointerEvents: "none",
  overflow: "visible",
};

const BatchChainGraphExportInner = ({
  target,
  imageFormat = "png",
  onComplete,
  onSkip,
  onError,
}: BatchChainGraphExportProps): React.JSX.Element => {
  const { chainId, outputName } = target;
  const containerRef = useRef<HTMLDivElement | null>(null);
  const hasExportedRef = useRef(false);
  const { getNodes } = useReactFlow();
  const {
    nodes,
    edges,
    decorativeEdges,
    isLoading,
    expandAllContainers,
    waitForNextAutoArrange,
  } = useChainGraph();

  const renderEdges = useMemo(
    () => [...edges, ...decorativeEdges].map(sanitizeEdge),
    [edges, decorativeEdges],
  );

  useEffect(() => {
    hasExportedRef.current = false;
  }, [chainId]);

  useEffect(() => {
    if (isLoading || hasExportedRef.current) {
      return;
    }

    if (!nodes.length) {
      hasExportedRef.current = true;
      onSkip(target);
      return;
    }

    hasExportedRef.current = true;

    const runExport = async () => {
      try {
        const container = containerRef.current;
        if (!container) {
          throw new Error(`Chain ${chainId} has no export container`);
        }

        const viewportEl = await prepareBatchGraphExport({
          container,
          getNodes: () => getNodes(),
          expandAllContainers,
          waitForNextAutoArrange,
          onLayoutWaitTimeout: () => {
            void reportExportImagesProgress("exportImagesItemWarning", {
              target,
              reason: "layout_timeout",
            });
          },
        });

        if (!viewportEl) {
          throw new Error(`Chain ${chainId} has no exportable graph`);
        }

        const restoreTheme = isDarkThemeActive() ? applyLightThemeFlip() : null;
        try {
          const content = await captureGraphImage({
            viewportEl,
            nodes: getNodes().filter((node) => !node.hidden),
            format: imageFormat,
          });
          const contentBase64 = arrayBufferToBase64(content);
          const fileName = `${outputName ?? chainId}.${imageFormat}`;

          onComplete({
            target,
            fileName,
            contentBase64,
          });
        } finally {
          restoreTheme?.();
        }
      } catch (error) {
        onError(error);
      }
    };

    void runExport();
  }, [
    expandAllContainers,
    getNodes,
    isLoading,
    nodes,
    imageFormat,
    chainId,
    outputName,
    onComplete,
    onSkip,
    onError,
    target,
    waitForNextAutoArrange,
  ]);

  return (
    <div ref={containerRef} style={hiddenGraphStyle} aria-hidden>
      <ReactFlow
        nodes={nodes.map((node) => ({
          ...node,
          draggable: false,
          connectable: false,
          selected: false,
        }))}
        nodeTypes={nodeTypes}
        defaultEdgeOptions={{ zIndex: 1001 }}
        edges={renderEdges}
        fitView
        onlyRenderVisibleElements={false}
        proOptions={{ hideAttribution: true }}
        zoomOnDoubleClick={false}
      />
    </div>
  );
};

export const BatchChainGraphExport = (
  props: BatchChainGraphExportProps,
): React.JSX.Element | null => {
  const { chainId, filePath } = props.target;
  const { chain, updateChain, getChain, isLoading } = useChain(chainId, {
    filePath,
  });

  const contextValue = useMemo(() => {
    if (!chain) {
      return undefined;
    }
    return {
      chain,
      update: async (changes: Partial<Chain>) => {
        await updateChain(changes);
        await getChain();
      },
      refresh: async () => {
        await getChain();
      },
    };
  }, [chain, getChain, updateChain]);

  if (isLoading || !contextValue) {
    return null;
  }

  return (
    <ChainContext.Provider value={contextValue}>
      <BatchChainGraphExportInner {...props} />
    </ChainContext.Provider>
  );
};
