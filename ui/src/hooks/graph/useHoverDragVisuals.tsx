import { useCallback, useEffect, useRef } from "react";
import { Node, useReactFlow } from "@xyflow/react";
import {
  applyHighlight,
  collectChildren,
  getPossibleGraphIntersection,
} from "../../misc/chain-graph-utils.ts";
import {
  ChainGraphNode,
  ChainGraphNodeData,
} from "../../components/graph/nodes/ChainGraphNodeTypes.ts";

const HOVER_EXPAND_DELAY_MS = 250;

type SetNodesUpdater = (
  updater: (
    nodes: Node<ChainGraphNodeData>[],
  ) => Node<ChainGraphNodeData>[],
) => void;

export const useHoverDragVisuals = (
  nodes: Node<ChainGraphNodeData>[],
  setNodes: SetNodesUpdater,
) => {
  const { getIntersectingNodes } = useReactFlow();
  const hoverExpandTimerRef = useRef<ReturnType<typeof setTimeout> | null>(
    null,
  );
  const lastHoverContainerIdRef = useRef<string | null>(null);
  const nodesRef = useRef(nodes);

  useEffect(() => {
    nodesRef.current = nodes;
  }, [nodes]);

  const clearHoverTimer = useCallback(() => {
    if (hoverExpandTimerRef.current !== null) {
      globalThis.clearTimeout(hoverExpandTimerRef.current);
      hoverExpandTimerRef.current = null;
    }
    lastHoverContainerIdRef.current = null;
  }, []);

  const clearHighlight = useCallback(() => {
    setNodes((curr) => applyHighlight(curr));
  }, [setNodes]);

  const clearDragVisuals = useCallback(() => {
    clearHoverTimer();
    clearHighlight();
  }, [clearHoverTimer, clearHighlight]);

  const highlightDragIntersections = useCallback(
    (draggedNode: ChainGraphNode) => {
      const possibleIntersections = getPossibleGraphIntersection(
        getIntersectingNodes(draggedNode),
        collectChildren(draggedNode.id, nodes),
      )?.id;
      setNodes((curr) => applyHighlight(curr, possibleIntersections));
    },
    [getIntersectingNodes, nodes, setNodes],
  );

  const expandDragIntersection = useCallback(
    (draggedNode: ChainGraphNode) => {
      const possibleGraphIntersect: ChainGraphNode | undefined =
        (getPossibleGraphIntersection(
          getIntersectingNodes(draggedNode) as ChainGraphNode[],
          collectChildren(draggedNode.id, nodes),
        ) as ChainGraphNode) ?? undefined;

      const candidateId = possibleGraphIntersect?.id ?? null;

      if (candidateId === lastHoverContainerIdRef.current) return;

      clearHoverTimer();
      lastHoverContainerIdRef.current = candidateId;

      if (!possibleGraphIntersect?.data?.collapsed) return;

      hoverExpandTimerRef.current = globalThis.setTimeout(() => {
        if (lastHoverContainerIdRef.current !== candidateId) return;
        const current = nodesRef.current.find((n) => n.id === candidateId);
        if (current?.type === "container" && current.data?.collapsed) {
          current.data.onToggleCollapse?.();
        }
      }, HOVER_EXPAND_DELAY_MS);
    },
    [getIntersectingNodes, nodes, clearHoverTimer],
  );

  useEffect(() => () => clearHoverTimer(), [clearHoverTimer]);

  return {
    clearHoverTimer,
    clearHighlight,
    clearDragVisuals,
    highlightDragIntersections,
    expandDragIntersection,
  };
};
