import {
  addEdge,
  applyEdgeChanges,
  applyNodeChanges,
  Connection as ReactFlowConnection,
  Edge,
  EdgeChange,
  Node,
  NodeChange,
  useEdgesState,
  useNodesState,
  useReactFlow,
} from "@xyflow/react";
import React, {
  DragEvent,
  useCallback,
  useEffect,
  useRef,
  useState,
} from "react";
import { api } from "../../api/api.ts";
import {
  Connection,
  CreateElementRequest,
  Element,
  TransferElementRequest,
} from "../../api/apiTypes.ts";
import { useAutoLayout } from "./useAutoLayout.tsx";
import { useExpandCollapse } from "./useExpandCollapse.tsx";
import { useNotificationService } from "../useNotificationService.tsx";
import { useLibraryContext } from "../../components/LibraryContext.tsx";
import {
  applyHighlight,
  buildGraphNodes,
  collectChildren,
  collectSubgraphByParents,
  depthOf,
  edgesForSubgraph,
  expandWithParent,
  findUpdatedElement,
  getContainerIdsForEdges,
  getDataFromElement,
  getEffectiveParentId,
  getFakeNode,
  getIntersectionParent,
  getLeastCommonParent,
  getLibraryElement,
  getNodeFromElement,
  getPossibleGraphIntersection,
  mergeWithPinnedPositions,
  sortParentsBeforeChildren,
} from "../../misc/chain-graph-utils.ts";
import {
  ChainGraphNode,
  ChainGraphNodeData,
  OnDeleteEvent,
} from "../../components/graph/nodes/ChainGraphNodeTypes.ts";
import {
  DecorativeEdgeData,
  isDecorativeEdgeId,
  originalEdgeIdFromDecorative,
  useDecorativeEdges,
} from "./useDecorativeEdges.tsx";
import { useHoverDragVisuals } from "./useHoverDragVisuals.tsx";

const getAbsolutePosition = (
  node: ChainGraphNode,
  allNodes: ChainGraphNode[],
) => {
  let x = node.position?.x ?? 0;
  let y = node.position?.y ?? 0;
  let parentId = node.parentId;
  while (parentId) {
    const parent = allNodes.find((n) => n.id === parentId);
    if (!parent) break;
    x += parent.position?.x ?? 0;
    y += parent.position?.y ?? 0;
    parentId = parent.parentId;
  }
  return { x, y };
};

const getParentAbsolutePosition = (
  parentId: string | undefined,
  allNodes: ChainGraphNode[],
) => {
  if (!parentId) return { x: 0, y: 0 };
  const parent = allNodes.find((n) => n.id === parentId);
  if (!parent) return { x: 0, y: 0 };
  return getAbsolutePosition(parent, allNodes);
};

const computeAffectedParents = (
  originalParentId: string | undefined,
  finalParentId: string | undefined,
  allNodes: ChainGraphNode[],
): string[] => {
  const affected = new Set<string>();
  if (originalParentId) affected.add(originalParentId);
  if (finalParentId && finalParentId !== originalParentId) {
    affected.add(finalParentId);
  }
  const leastCommonParent = getLeastCommonParent(
    originalParentId,
    finalParentId,
    allNodes,
  );
  if (leastCommonParent) affected.add(leastCommonParent);
  return Array.from(affected);
};

export const useChainGraph = (
  chainId?: string,
  onChainUpdate?: () => void | Promise<void>,
) => {
  const { screenToFlowPosition, getIntersectingNodes } = useReactFlow();
  const { libraryElements, isLibraryLoading } = useLibraryContext();

  const [nodes, setNodes] = useNodesState<Node<ChainGraphNodeData>>([]);
  const [edges, setEdges] = useEdgesState<Edge>([]);
  const [isLoading, setIsLoading] = useState(true);

  const isInitialized = useRef<boolean>(false);
  const nodesRef = useRef(nodes);

  const notificationService = useNotificationService();
  const { arrangeNodes, direction, toggleDirection } = useAutoLayout();

  const structureChangedRef = useRef<boolean>(false);
  const structureChangedParentIdsRef = useRef<string[] | null>(null);

  useEffect(() => {
    nodesRef.current = nodes;
  }, [nodes]);

  const { decorativeEdges, setDecorativeEdges } = useDecorativeEdges(
    nodes as ChainGraphNode[],
    edges,
  );

  const {
    clearHoverTimer,
    clearHighlight,
    clearDragVisuals,
    highlightDragIntersections,
    expandDragIntersection,
  } = useHoverDragVisuals(nodes, setNodes);

  const structureChanged = useCallback(
    (parentIds?: string[]) => {
      structureChangedRef.current = true;
      if (parentIds && parentIds.length) {
        const expanded = expandWithParent(parentIds, nodesRef.current);
        structureChangedParentIdsRef.current = Array.from(new Set(expanded));
      } else {
        structureChangedParentIdsRef.current = null;
      }
      clearHighlight();
    },
    [clearHighlight],
  );

  const {
    attachToggle,
    setNestedUnitCounts,
    reapplyNodesVisibility,
    reapplyEdgesVisibility,
    expandAllContainers,
    collapseAllContainers,
  } = useExpandCollapse(
    nodes as ChainGraphNode[],
    setNodes,
    edges,
    setEdges,
    structureChanged,
  );

  useEffect(() => {
    const autoArrange = async () => {
      if (!structureChangedRef.current) return;
      structureChangedRef.current = false;

      const parentIds = structureChangedParentIdsRef.current;
      let arrangedNodes: ChainGraphNode[];

      if (parentIds && parentIds.length) {
        const nodeMap = new Map(
          (nodes as ChainGraphNode[]).map((node) => [node.id, node]),
        );
        const sorted = [...new Set(parentIds)].sort(
          (left, right) => depthOf(right, nodeMap) - depthOf(left, nodeMap),
        );

        let current = nodes as ChainGraphNode[];
        for (const parents of sorted) {
          const subNodes = collectSubgraphByParents([parents], current);
          const subEdges = edgesForSubgraph(edges, subNodes);
          const laidSubset = await arrangeNodes(subNodes, subEdges);

          const pinned = new Set(expandWithParent([parents], current));
          current = mergeWithPinnedPositions(current, laidSubset, pinned);
        }
        arrangedNodes = current;
      } else {
        arrangedNodes = await arrangeNodes(nodes as ChainGraphNode[], edges);
      }

      const withToggle = attachToggle(arrangedNodes);
      const visibleNodes = reapplyNodesVisibility(withToggle);
      const withCounts = setNestedUnitCounts(visibleNodes);
      const orderedVisibleNodes = sortParentsBeforeChildren(withCounts);
      const visibleEdges = reapplyEdgesVisibility(withToggle, edges);

      setNodes(orderedVisibleNodes);
      setEdges(visibleEdges);

      structureChangedParentIdsRef.current = null;
    };

    void autoArrange();
  }, [
    nodes,
    edges,
    arrangeNodes,
    setNodes,
    setEdges,
    attachToggle,
    reapplyNodesVisibility,
    reapplyEdgesVisibility,
    setNestedUnitCounts,
  ]);

  useEffect(() => {
    if (isInitialized.current) return;
    if (isLibraryLoading) return;
    if (!chainId) return;

    isInitialized.current = true;

    const fetchData = async () => {
      setIsLoading(true);
      try {
        const [elementsResponse, connections] = await Promise.all([
          api.getElements(chainId),
          api.getConnections(chainId),
        ]);
        const elements = elementsResponse as unknown as Element[];

        const newNodes: ChainGraphNode[] = elements
          .map((element: Element) =>
            getNodeFromElement(
              element,
              getLibraryElement(element, libraryElements),
              direction,
            ),
          )
          .filter((n): n is ChainGraphNode => !!n);

        const newEdges: Edge[] = connections.map((c: Connection) => ({
          id: c.id,
          source: c.from,
          target: c.to,
        }));

        const arranged = await arrangeNodes(newNodes, newEdges);
        const withToggle = attachToggle(arranged);
        const visibleNodes = reapplyNodesVisibility(withToggle);
        const withCount = setNestedUnitCounts(visibleNodes);
        const ordered = sortParentsBeforeChildren(withCount);

        setNodes(ordered);
        setEdges(newEdges);

        structureChanged();
      } catch (error) {
        notificationService.requestFailed(
          "Failed to load elements or connections",
          error,
        );
      } finally {
        setIsLoading(false);
      }
    };

    void fetchData();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [chainId, isLibraryLoading]);

  const onConnect = useCallback(
    async (connection: ReactFlowConnection) => {
      if (!chainId) return;
      try {
        const response = await api.createConnection(
          { from: connection.source, to: connection.target },
          chainId,
        );
        const createdId = response.createdDependencies?.[0]?.id;
        if (!createdId) return;
        const edge: Edge = { ...connection, id: createdId };
        setEdges((eds) => addEdge(edge, eds));

        const sourceParent = (nodes as ChainGraphNode[]).find(
          (node) => node.id === connection.source,
        )?.parentId;
        const targetParent = (nodes as ChainGraphNode[]).find(
          (node) => node.id === connection.target,
        )?.parentId;
        const parents = Array.from(
          new Set([sourceParent, targetParent].filter(Boolean) as string[]),
        );
        if (parents.length) structureChanged(parents);
        if (onChainUpdate) {
          void onChainUpdate();
        }
      } catch (error) {
        notificationService.requestFailed("Failed to create connection", error);
      }
    },
    [
      chainId,
      nodes,
      notificationService,
      setEdges,
      structureChanged,
      onChainUpdate,
    ],
  );

  const onDragOver = useCallback(
    (event: DragEvent<HTMLDivElement>) => {
      event.preventDefault();
      clearHoverTimer();
      event.dataTransfer.dropEffect = "move";

      const currentDragPosition = screenToFlowPosition({
        x: event.clientX,
        y: event.clientY,
      });

      const fakeDragNode = getFakeNode(currentDragPosition);
      highlightDragIntersections(fakeDragNode);
      expandDragIntersection(fakeDragNode);
    },
    [
      clearHoverTimer,
      expandDragIntersection,
      highlightDragIntersections,
      screenToFlowPosition,
    ],
  );

  const onDrop = useCallback(
    async (event: React.DragEvent) => {
      event.preventDefault();
      if (!chainId) return;

      const name = event.dataTransfer.getData("application/reactflow");
      if (!name) return;

      const dropPosition = screenToFlowPosition({
        x: event.clientX,
        y: event.clientY,
      });

      const fakeNode = getFakeNode(dropPosition);
      const intersecting = getIntersectingNodes(fakeNode).filter(
        (node) => node.type === "container" || node.type === "swimlane",
      );

      const parentNode = intersecting.sort((a, b) => {
        const areaA = (a.width ?? 0) * (a.height ?? 0);
        const areaB = (b.width ?? 0) * (b.height ?? 0);
        return areaA - areaB;
      })[0];

      let createElementRequest: CreateElementRequest = { type: name };
      if (parentNode) {
        createElementRequest = {
          ...createElementRequest,
          ...(parentNode.type === "swimlane"
            ? { swimlaneId: parentNode.id }
            : { parentElementId: parentNode.id }),
        };
      }

      try {
        const response = await api.createElement(createElementRequest, chainId);
        const createdElement = response.createdElements?.[0];
        if (!createdElement) return;

        const newNode: ChainGraphNode = getNodeFromElement(
          createdElement,
          getLibraryElement(createdElement, libraryElements),
          direction,
          dropPosition,
        );
        if (!newNode) return;

        const childNodes: ChainGraphNode[] = createdElement?.children
          ? createdElement.children.map((child: Element) =>
              getNodeFromElement(
                child,
                getLibraryElement(child, libraryElements),
                direction,
                dropPosition,
              ),
            )
          : [];

        const updatedNodes = buildGraphNodes(
          response.updatedElements ?? [],
          libraryElements,
        );

        const arrangedNodes = await arrangeNodes(
          childNodes.concat(newNode, ...updatedNodes),
          edges,
        );
        const allNodes = (nodes as ChainGraphNode[])
          .filter((node) => !arrangedNodes.some((n) => n.id === node.id))
          .concat(arrangedNodes);
        const withToggle = attachToggle(allNodes);
        const withCount = setNestedUnitCounts(withToggle);

        const withDropPosition = withCount.map((node: ChainGraphNode) =>
          node.id === newNode.id ? { ...node, position: dropPosition } : node,
        );

        const ordered = sortParentsBeforeChildren(withDropPosition);
        setNodes(ordered);

        if (parentNode || newNode.parentId) {
          structureChanged([parentNode?.id ?? newNode.parentId]);
        }

        if (onChainUpdate) {
          void onChainUpdate();
        }

        clearDragVisuals();
      } catch (error) {
        notificationService.requestFailed("Failed to create element", error);
      }
    },
    [
      chainId,
      screenToFlowPosition,
      getIntersectingNodes,
      libraryElements,
      direction,
      nodes,
      arrangeNodes,
      attachToggle,
      setNestedUnitCounts,
      setNodes,
      notificationService,
      structureChanged,
      clearDragVisuals,
      onChainUpdate,
    ],
  );

  const onEdgesChange = useCallback(
    (changes: EdgeChange[]) => {
      if (!chainId) return;

      const baseChanges = changes.filter(
        (c) => !isDecorativeEdgeId((c as { id: string }).id),
      );
      const decoChanges = changes.filter((c) =>
        isDecorativeEdgeId((c as { id: string }).id),
      );

      const baseNonRemove = baseChanges.filter((c) => c.type !== "remove");
      const decoNonRemove = decoChanges.filter((c) => c.type !== "remove");

      if (baseNonRemove.length) {
        setEdges((eds) => applyEdgeChanges(baseNonRemove, eds));
      }
      if (decoNonRemove.length) {
        setDecorativeEdges((eds) => applyEdgeChanges(decoNonRemove, eds));
      }
    },
    [chainId, setEdges, setDecorativeEdges],
  );

  const onNodesChange = useCallback(
    (changes: NodeChange<ChainGraphNode>[]) => {
      if (!chainId) return;
      const nonRemove = changes.filter((c) => c.type !== "remove");
      if (!nonRemove.length) return;
      setNodes((nds) => {
        const next = applyNodeChanges(nonRemove, nds);
        return sortParentsBeforeChildren(next as ChainGraphNode[]);
      });
    },
    [chainId, setNodes],
  );

  const onDelete = useCallback(
    async (changes: OnDeleteEvent) => {
      if (!chainId) return;

      const nodesToDelete: ChainGraphNode[] = changes.nodes as ChainGraphNode[];
      const edgesToDelete: Edge[] = changes.edges;

      const normalizedEdges = edgesToDelete
        .map((e) => {
          if (isDecorativeEdgeId(e.id)) {
            const d = (e.data ?? {}) as Partial<DecorativeEdgeData>;
            const id = d.originalEdgeId ?? originalEdgeIdFromDecorative(e.id);
            const source = d.originalSource ?? "";
            const target = d.originalTarget ?? "";
            if (!id || !source || !target) return null;
            return { id, source, target };
          }
          return { id: e.id, source: e.source, target: e.target };
        })
        .filter(
          (x): x is { id: string; source: string; target: string } => !!x,
        );

      const affectedParents = new Set<string>();
      for (const parentContainer of getContainerIdsForEdges(
        normalizedEdges as unknown as Edge[],
        nodes as ChainGraphNode[],
      )) {
        affectedParents.add(parentContainer);
      }

      const removingNodeIds = new Set(nodesToDelete.map((node) => node.id));
      const parentMap = new Map<string, string | undefined>();
      (nodes as ChainGraphNode[]).forEach((node) => {
        if (removingNodeIds.has(node.id)) parentMap.set(node.id, node.parentId);
      });

      const rootIdsToDelete = Array.from(removingNodeIds).filter((id) => {
        const parent = parentMap.get(id);
        return parent === undefined || !removingNodeIds.has(parent);
      });

      for (const id of rootIdsToDelete) {
        const node = (nodes as ChainGraphNode[]).find((x) => x.id === id);
        if (node?.parentId) affectedParents.add(node.parentId);
      }

      const deletedEdgeIds: string[] = [];

      const separateEdgesToDelete = normalizedEdges.filter(
        (edge) =>
          !nodesToDelete.find(
            (node) => node.id === edge.source || node.id === edge.target,
          ),
      );

      if (separateEdgesToDelete.length > 0) {
        try {
          const response = await api.deleteConnections(
            separateEdgesToDelete.map((edge) => edge.id),
            chainId,
          );
          response.removedDependencies?.forEach((connection) =>
            deletedEdgeIds.push(connection.id),
          );
        } catch (error) {
          notificationService.requestFailed(
            "Failed to delete connections",
            error,
          );
        }
      }

      try {
        const elementsDeleteResponse = await api.deleteElements(
          rootIdsToDelete,
          chainId,
        );

        const deletedNodeIds = (
          elementsDeleteResponse.removedElements || []
        ).map((element) => element.id);

        elementsDeleteResponse.removedDependencies?.forEach((connection) =>
          deletedEdgeIds.push(connection.id),
        );

        const updatedNodes = buildGraphNodes(
          elementsDeleteResponse.updatedElements ?? [],
          libraryElements,
        );
        const updatedNodeIds = new Set(updatedNodes.map((node) => node.id));

        const allNodes = (nodes as ChainGraphNode[]).filter(
          (node) =>
            !deletedNodeIds.includes(node.id) && !updatedNodeIds.has(node.id),
        );
        const allEdges = edges.filter(
          (edge) => !deletedEdgeIds.includes(edge.id),
        );
        allNodes.push(...(await arrangeNodes(updatedNodes, allEdges)));

        const ordered = sortParentsBeforeChildren(allNodes);
        setNodes(ordered);
        setEdges(allEdges);

        if (onChainUpdate) {
          void onChainUpdate();
        }

        const ids = Array.from(affectedParents);
        if (ids.length) {
          structureChanged(ids);
        }
      } catch (error) {
        notificationService.requestFailed("Failed to delete element", error);
      }
    },
    [
      chainId,
      nodes,
      edges,
      notificationService,
      setNodes,
      setEdges,
      structureChanged,
      onChainUpdate,
    ],
  );

  const handleDragInteraction = useCallback(
    (_: React.MouseEvent, draggedNode: ChainGraphNode) => {
      clearHoverTimer();
      highlightDragIntersections(draggedNode);
      expandDragIntersection(draggedNode);
    },
    [clearHoverTimer, expandDragIntersection, highlightDragIntersections],
  );

  const onNodeDragStop = useCallback(
    async (_event: React.MouseEvent, draggedNode: ChainGraphNode) => {
      if (!chainId) return;
      if (isLibraryLoading) return;

      clearHoverTimer();
      setNodes((curr) => applyHighlight(curr));

      const allBefore = nodesRef.current as ChainGraphNode[];

      const originalNode = allBefore.find((node) => node.id === draggedNode.id);
      if (!originalNode) return;

      const selected = allBefore.filter((n) => n.selected).map((n) => n.id);
      const selectedIds = selected.length ? selected : [originalNode.id];

      const originalParentId = originalNode.parentId;

      let newParentNode: Node | undefined = undefined;
      const possibleGraphIntersect: Node | undefined =
        getPossibleGraphIntersection(
          getIntersectingNodes(draggedNode) as ChainGraphNode[],
          collectChildren(draggedNode.id, allBefore),
        ) ?? undefined;

      if (possibleGraphIntersect !== undefined) {
        newParentNode = getIntersectionParent(
          draggedNode,
          possibleGraphIntersect,
          libraryElements ?? [],
        );
      }

      const parentNodeId = newParentNode?.id ?? undefined;
      const isParentChanged = parentNodeId !== originalParentId;

      if (!isParentChanged) return;

      let finalParentId = originalParentId;
      try {
        const request: TransferElementRequest = {
          parentId:
            newParentNode?.type === "container"
              ? (newParentNode?.id ?? null)
              : null,
          swimlaneId:
            newParentNode?.type === "swimlane"
              ? (newParentNode?.id ?? null)
              : null,
          elements: selectedIds,
        };
        const response = await api.transferElement(request, chainId);
        const updatedElement = findUpdatedElement(
          response.updatedElements,
          draggedNode.id,
        );
        finalParentId = getEffectiveParentId(updatedElement);
        if (onChainUpdate) {
          void onChainUpdate();
        }
      } catch (error) {
        notificationService.errorWithDetails(
          "Drag element failed",
          "Failed to drag element",
          error,
        );
        finalParentId = originalParentId;
      }

      setNodes((prev) => {
        const snapshot = nodesRef.current as ChainGraphNode[];
        const parentAbs = getParentAbsolutePosition(finalParentId, snapshot);

        const next = (prev as ChainGraphNode[]).map((node) => {
          if (!selectedIds.includes(node.id)) return node;

          const nowAbs = getAbsolutePosition(
            snapshot.find((z) => z.id === node.id)!,
            snapshot,
          );

          return {
            ...node,
            parentId: finalParentId ?? undefined,
            position: {
              x: nowAbs.x - parentAbs.x,
              y: nowAbs.y - parentAbs.y,
            },
          };
        });

        return sortParentsBeforeChildren(next);
      });

      const affectedParentIds = computeAffectedParents(
        originalParentId,
        finalParentId,
        nodesRef.current as ChainGraphNode[],
      );
      structureChanged(
        affectedParentIds.length ? affectedParentIds : undefined,
      );
    },
    [
      chainId,
      isLibraryLoading,
      getIntersectingNodes,
      libraryElements,
      notificationService,
      setNodes,
      structureChanged,
      clearHoverTimer,
      onChainUpdate,
    ],
  );

  const updateNodeData = useCallback(
    (element: Element, node: ChainGraphNode) => {
      setNodes((prevNodes) =>
        (prevNodes as ChainGraphNode[]).map((prevNode) => {
          if (prevNode.id === node.id) {
            const updatedNode: ChainGraphNode = {
              ...prevNode,
              data: {
                ...prevNode.data,
                ...getDataFromElement(element),
              },
              parentId: getEffectiveParentId(element),
            };
            return updatedNode;
          }
          return prevNode;
        }),
      );
    },
    [setNodes],
  );

  return {
    nodes,
    setNodes,
    edges,
    setEdges,
    decorativeEdges,
    onConnect,
    onDragOver,
    onDrop,
    onDelete,
    onEdgesChange,
    onNodesChange,
    onNodeDragStart: handleDragInteraction,
    onNodeDrag: handleDragInteraction,
    onNodeDragStop,
    direction,
    toggleDirection,
    updateNodeData,
    isLoading: isLoading || isLibraryLoading,
    expandAllContainers,
    collapseAllContainers,
    structureChanged,
  };
};
