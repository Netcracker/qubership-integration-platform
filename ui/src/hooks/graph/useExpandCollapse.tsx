import { Edge } from "@xyflow/react";
import React, { useCallback, useEffect, useRef } from "react";
import { ChainGraphNode } from "../../components/graph/nodes/ChainGraphNodeTypes";
import { computeNestedUnitCounts } from "../../misc/chain-graph-utils.ts";

type SetNodesFn = React.Dispatch<React.SetStateAction<ChainGraphNode[]>>;
type SetEdgesFn = React.Dispatch<React.SetStateAction<Edge[]>>;

function computeHiddenNodeIds(nodes: ChainGraphNode[]): Set<string> {
  const nodeMap = new Map(nodes.map((node) => [node.id, node]));
  const collapsedNodeIds = new Set(
    nodes
      .filter((node) => node.type === "container" && !!node.data?.collapsed)
      .map((node) => node.id),
  );

  const hiddenNodeIds = new Set<string>();
  for (const node of nodes) {
    let parentId = nodeMap.get(node.id)?.parentId;
    const seen = new Set<string>();
    while (parentId && !seen.has(parentId)) {
      if (collapsedNodeIds.has(parentId)) {
        hiddenNodeIds.add(node.id);
        break;
      }
      seen.add(parentId);
      parentId = nodeMap.get(parentId)?.parentId;
    }
  }
  return hiddenNodeIds;
}

export function useExpandCollapse(
  nodes: ChainGraphNode[],
  setNodes: SetNodesFn,
  edges: Edge[],
  setEdges: SetEdgesFn,
  structureChanged: (parentIds?: string[]) => void,
) {
  const setNestedUnitCounts = useCallback(
    (nodesList: ChainGraphNode[]): ChainGraphNode[] => {
      const counts = computeNestedUnitCounts(nodesList);
      return nodesList.map((node) =>
        node.type === "container"
          ? {
              ...node,
              data: { ...node.data, unitCount: counts.get(node.id) ?? 0 },
            }
          : node,
      );
    },
    [],
  );

  const processToggle = useCallback(
    (toggledContainerId: string) => {
      const isCollapsed =
        nodes.find((node) => node.id === toggledContainerId)?.data?.collapsed ??
        false;

      const toggledNodes = nodes.map((node) =>
        node.id === toggledContainerId
          ? { ...node, data: { ...node.data, collapsed: !isCollapsed } }
          : node,
      );

      const hiddenIds = computeHiddenNodeIds(toggledNodes);

      const processedNodes = setNestedUnitCounts(
        toggledNodes.map((node) => {
          const hidden = hiddenIds.has(node.id);
          return {
            ...node,
            hidden,
            selected: hidden ? false : node.selected,
          };
        }),
      );

      const processedEdges = edges.map((edge) => ({
        ...edge,
        hidden: hiddenIds.has(edge.source) || hiddenIds.has(edge.target),
      }));

      setNodes(processedNodes);
      setEdges(processedEdges);

      const toggled = nodes.find((n) => n.id === toggledContainerId);
      const parentId = toggled?.parentId;
      const targets = parentId ? [parentId] : [toggledContainerId];

      structureChanged(targets);
    },
    [nodes, edges, setNodes, setEdges, setNestedUnitCounts, structureChanged],
  );

  const toggleRef = useRef(processToggle);
  useEffect(() => {
    toggleRef.current = processToggle;
  }, [processToggle]);

  const toggle = useCallback((toggledContainerId: string) => {
    toggleRef.current(toggledContainerId);
  }, []);

  const attachToggle = useCallback(
    (nodesList: ChainGraphNode[]): ChainGraphNode[] =>
      nodesList.map((node) =>
        node.type === "container"
          ? {
              ...node,
              data: { ...node.data, onToggleCollapse: () => toggle(node.id) },
            }
          : node,
      ),
    [toggle],
  );

  const reapplyNodesVisibility = useCallback(
    (nodesList: ChainGraphNode[]): ChainGraphNode[] => {
      const hiddenIds = computeHiddenNodeIds(nodesList);
      return nodesList.map((node) => {
        const hidden = hiddenIds.has(node.id);
        return {
          ...node,
          hidden,
          selected: hidden ? false : node.selected,
        };
      });
    },
    [],
  );

  const reapplyEdgesVisibility = useCallback(
    (nodesList: ChainGraphNode[], edgesList: Edge[]): Edge[] => {
      const hiddenIds = computeHiddenNodeIds(nodesList);
      return edgesList.map((edge) => ({
        ...edge,
        hidden: hiddenIds.has(edge.source) || hiddenIds.has(edge.target),
      }));
    },
    [],
  );

  return {
    attachToggle,
    setNestedUnitCounts,
    reapplyNodesVisibility,
    reapplyEdgesVisibility,
    toggle,
  };
}
