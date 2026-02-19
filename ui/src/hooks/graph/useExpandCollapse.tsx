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

function isGroupContainer(node: ChainGraphNode): boolean {
  return node.type === "container" && node.data?.elementType === "container";
}

function normalizeEdgeHandles(edge: Edge): Edge {
  const next: Edge = { ...edge };
  if ((next as any).sourceHandle == null) {
    delete (next as any).sourceHandle;
  }
  if ((next as any).targetHandle == null) {
    delete (next as any).targetHandle;
  }
  return next;
}

function applyGroupContainerProxyHandles(node: ChainGraphNode): ChainGraphNode {
  if (!isGroupContainer(node)) return node;

  const collapsed = !!node.data?.collapsed;
  const data = {
    ...node.data,
    inputEnabled: collapsed ? true : undefined,
    outputEnabled: collapsed ? true : undefined,
  };

  return {
    ...node,
    data,
    connectable: false,
  };
}

function reapplyNodeFlags(
  nodesList: ChainGraphNode[],
  hiddenIds: Set<string>,
): ChainGraphNode[] {
  return nodesList.map((node) => {
    const hidden = hiddenIds.has(node.id);
    const base: ChainGraphNode = {
      ...node,
      hidden,
      selected: hidden ? false : node.selected,
    };
    return applyGroupContainerProxyHandles(base);
  });
}

function reapplyEdgeFlags(
  nodesList: ChainGraphNode[],
  edgesList: Edge[],
): Edge[] {
  const hiddenIds = computeHiddenNodeIds(nodesList);
  return edgesList.map((edge) => {
    const normalized = normalizeEdgeHandles(edge);
    return {
      ...normalized,
      hidden:
        hiddenIds.has(normalized.source) || hiddenIds.has(normalized.target),
    };
  });
}

function buildDecorativeEdgesFrom(
  nodesList: ChainGraphNode[],
  edgesList: Edge[],
): Edge[] {
  const nodeMap = new Map(nodesList.map((n) => [n.id, n]));
  const hiddenIds = computeHiddenNodeIds(nodesList);

  const collapsedIds = new Set(
    nodesList
      .filter((n) => n.type === "container" && !!n.data?.collapsed)
      .map((n) => n.id),
  );

  const getVisibleCollapsedProxy = (id: string): string | undefined => {
    let parentId = nodeMap.get(id)?.parentId;
    const seen = new Set<string>();
    while (parentId && !seen.has(parentId)) {
      if (collapsedIds.has(parentId) && !hiddenIds.has(parentId))
        return parentId;
      seen.add(parentId);
      parentId = nodeMap.get(parentId)?.parentId;
    }
    return undefined;
  };

  const out: Edge[] = [];
  const used = new Set<string>();

  for (const raw of edgesList) {
    const edge = normalizeEdgeHandles(raw);

    const sourceHidden = hiddenIds.has(edge.source);
    const targetHidden = hiddenIds.has(edge.target);
    if (!sourceHidden && !targetHidden) continue;

    const newSource = sourceHidden
      ? getVisibleCollapsedProxy(edge.source)
      : edge.source;
    const newTarget = targetHidden
      ? getVisibleCollapsedProxy(edge.target)
      : edge.target;

    if (!newSource || !newTarget) continue;
    if (newSource === newTarget) continue;

    const sourceNode = nodeMap.get(newSource);
    const targetNode = nodeMap.get(newTarget);
    if (!sourceNode || !targetNode) continue;
    if (hiddenIds.has(newSource) || hiddenIds.has(newTarget)) continue;

    const expandContainerIds = Array.from(
      new Set([
        ...(sourceHidden ? [newSource] : []),
        ...(targetHidden ? [newTarget] : []),
      ]),
    );

    const id = `decorative:${edge.id}:${newSource}:${newTarget}`;
    if (used.has(id)) continue;
    used.add(id);

    out.push({
      id,
      source: newSource,
      target: newTarget,
      type: edge.type,
      label: edge.label,
      markerEnd: edge.markerEnd,
      markerStart: edge.markerStart,
      style: edge.style,
      className: edge.className,
      animated: edge.animated,
      selectable: true,
      deletable: true,
      data: {
        decorative: true,
        originalEdgeId: edge.id,
        expandContainerIds,
      } as any,
      zIndex: (edge.zIndex ?? 0) + 1,
    });
  }

  return out;
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
        reapplyNodeFlags(toggledNodes, hiddenIds),
      );

      const processedEdges = reapplyEdgeFlags(processedNodes, edges);

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
      return reapplyNodeFlags(nodesList, hiddenIds);
    },
    [],
  );

  const reapplyEdgesVisibility = useCallback(
    (nodesList: ChainGraphNode[], edgesList: Edge[]): Edge[] =>
      reapplyEdgeFlags(nodesList, edgesList),
    [],
  );

  const buildDecorativeEdges = useCallback(
    (nodesList: ChainGraphNode[], edgesList: Edge[]): Edge[] =>
      buildDecorativeEdgesFrom(nodesList, edgesList),
    [],
  );

  const expandContainers = useCallback(
    (containerIds: string[]) => {
      const ids = Array.from(new Set(containerIds)).filter(Boolean);
      if (!ids.length) return;

      const nextNodes = nodes.map((n) => {
        if (!ids.includes(n.id)) return n;
        if (n.type !== "container") return n;
        if (!n.data?.collapsed) return n;
        return { ...n, data: { ...n.data, collapsed: false } };
      });

      const hiddenIds = computeHiddenNodeIds(nextNodes);
      const processedNodes = setNestedUnitCounts(
        reapplyNodeFlags(nextNodes, hiddenIds),
      );
      const processedEdges = reapplyEdgeFlags(processedNodes, edges);

      setNodes(processedNodes);
      setEdges(processedEdges);

      structureChanged(ids);
    },
    [nodes, edges, setNodes, setEdges, setNestedUnitCounts, structureChanged],
  );

  return {
    attachToggle,
    setNestedUnitCounts,
    reapplyNodesVisibility,
    reapplyEdgesVisibility,
    buildDecorativeEdges,
    expandContainers,
    toggle,
  };
}
