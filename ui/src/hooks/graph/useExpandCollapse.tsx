import { Edge } from "@xyflow/react";
import React, { useCallback, useEffect, useRef } from "react";
import { ChainGraphNode } from "../../components/graph/nodes/ChainGraphNodeTypes";
import { computeNestedUnitCounts } from "../../misc/chain-graph-utils.ts";

type SetNodesFn = React.Dispatch<React.SetStateAction<ChainGraphNode[]>>;
type SetEdgesFn = React.Dispatch<React.SetStateAction<Edge[]>>;

function collectDescendantIds(
  nodes: ChainGraphNode[],
  containerId: string,
): Set<string> {
  const childrenByParentId = new Map<string, ChainGraphNode[]>();

  for (const node of nodes) {
    if (!node.parentId) continue;

    const children = childrenByParentId.get(node.parentId) ?? [];
    children.push(node);
    childrenByParentId.set(node.parentId, children);
  }

  const result = new Set<string>();
  const stack = [...(childrenByParentId.get(containerId) ?? [])];

  while (stack.length) {
    const node = stack.pop()!;

    if (result.has(node.id)) continue;

    result.add(node.id);
    stack.push(...(childrenByParentId.get(node.id) ?? []));
  }

  return result;
}

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
  if (next.sourceHandle == null) {
    delete next.sourceHandle;
  }
  if (next.targetHandle == null) {
    delete next.targetHandle;
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
  hiddenIds: Set<string> = computeHiddenNodeIds(nodesList),
): Edge[] {
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
      },
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

  const applyVisibility = useCallback(
    (
      nodesList: ChainGraphNode[],
      edgesList: Edge[],
    ): {
      processedNodes: ChainGraphNode[];
      processedEdges: Edge[];
    } => {
      const hiddenIds = computeHiddenNodeIds(nodesList);
      const processedNodes = reapplyNodeFlags(nodesList, hiddenIds);
      const processedEdges = reapplyEdgeFlags(
        processedNodes,
        edgesList,
        hiddenIds,
      );

      return {
        processedNodes,
        processedEdges,
      };
    },
    [],
  );

  const structureChangedFrameRef = useRef<number | null>(null);
  const pendingStructureChangedTargetsRef = useRef<Set<string>>(new Set());

  const scheduleStructureChanged = useCallback(
    (targetIds: string[]) => {
      targetIds.filter(Boolean).forEach((id) => {
        pendingStructureChangedTargetsRef.current.add(id);
      });

      if (structureChangedFrameRef.current !== null) {
        return;
      }

      structureChangedFrameRef.current = window.requestAnimationFrame(() => {
        structureChangedFrameRef.current = null;

        const targets = Array.from(pendingStructureChangedTargetsRef.current);
        pendingStructureChangedTargetsRef.current.clear();

        structureChanged(targets.length ? targets : undefined);
      });
    },
    [structureChanged],
  );

  const processToggle = useCallback(
    (toggledContainerId: string) => {
      const toggled = nodes.find((node) => node.id === toggledContainerId);

      if (!toggled || toggled.type !== "container") {
        return;
      }

      const isCollapsed = !!toggled.data?.collapsed;
      const nextCollapsed = !isCollapsed;

      const toggledNodes = nodes.map((node) =>
        node.id === toggledContainerId
          ? {
            ...node,
            data: {
              ...node.data,
              collapsed: nextCollapsed,
            },
          }
          : node,
      );

      const hiddenIds = computeHiddenNodeIds(toggledNodes);

      if (!nextCollapsed) {
        const descendantIds = collectDescendantIds(
          toggledNodes,
          toggledContainerId,
        );

        descendantIds.forEach((id) => {
          hiddenIds.add(id);
        });
      }

      const processedNodes = reapplyNodeFlags(toggledNodes, hiddenIds);
      const processedEdges = reapplyEdgeFlags(processedNodes, edges, hiddenIds);

      structureChanged([toggledContainerId]);

      setNodes(processedNodes);
      setEdges(processedEdges);
    },
    [nodes, edges, setNodes, setEdges, structureChanged],
  );

  useEffect(
    () => () => {
      if (structureChangedFrameRef.current !== null) {
        window.cancelAnimationFrame(structureChangedFrameRef.current);
        structureChangedFrameRef.current = null;
      }

      pendingStructureChangedTargetsRef.current.clear();
    },
    [],
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

      const idSet = new Set(ids);
      const changedIds: string[] = [];

      const nextNodes = nodes.map((node) => {
        if (!idSet.has(node.id)) return node;
        if (node.type !== "container") return node;
        if (!node.data?.collapsed) return node;

        changedIds.push(node.id);

        return {
          ...node,
          data: {
            ...node.data,
            collapsed: false,
          },
        };
      });

      if (!changedIds.length) {
        return;
      }

      const { processedNodes, processedEdges } = applyVisibility(
        nextNodes,
        edges,
      );

      setNodes(processedNodes);
      setEdges(processedEdges);

      scheduleStructureChanged(changedIds);
    },
    [
      nodes,
      edges,
      setNodes,
      setEdges,
      applyVisibility,
      scheduleStructureChanged,
    ],
  );

  const setAllContainersCollapsed = useCallback(
    (collapsed: boolean) => {
      const changedIds: string[] = [];

      const nextNodes = nodes.map((node) => {
        if (node.type !== "container") {
          return node;
        }

        if (!!node.data?.collapsed === collapsed) {
          return node;
        }

        changedIds.push(node.id);

        return {
          ...node,
          data: {
            ...node.data,
            collapsed,
          },
        };
      });

      if (!changedIds.length) {
        return;
      }

      const { processedNodes, processedEdges } = applyVisibility(
        nextNodes,
        edges,
      );

      setNodes(processedNodes);
      setEdges(processedEdges);

      scheduleStructureChanged(changedIds);
    },
    [
      nodes,
      edges,
      setNodes,
      setEdges,
      applyVisibility,
      scheduleStructureChanged,
    ],
  );

  const expandAllContainers = useCallback(() => {
    setAllContainersCollapsed(false);
  }, [setAllContainersCollapsed]);

  const collapseAllContainers = useCallback(() => {
    setAllContainersCollapsed(true);
  }, [setAllContainersCollapsed]);

  return {
    attachToggle,
    setNestedUnitCounts,
    reapplyNodesVisibility,
    reapplyEdgesVisibility,
    buildDecorativeEdges,
    expandContainers,
    toggle,
    expandAllContainers,
    collapseAllContainers,
  };
}
