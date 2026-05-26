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
  useContext,
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
import { getErrorMessage } from "../../misc/error-utils.ts";
import { ChainContext } from "../../pages/ChainPage.tsx";
import { traverseElementsDepthFirst } from "../../misc/tree-utils.ts";

type NodeBounds = {
  minX: number;
  maxX: number;
  minY: number;
  maxY: number;
};

type StructureChangedOptions = {
  resolveOverlaps?: boolean;
};

const RELAYOUT_OVERLAP_GAP = 40;
const MAX_OVERLAP_RESOLVE_PASSES = 8;

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

const getNodeBounds = (node: ChainGraphNode): NodeBounds => {
  const x = node.position?.x ?? 0;
  const y = node.position?.y ?? 0;
  const width = node.width ?? node.measured?.width ?? 150;
  const height = node.height ?? node.measured?.height ?? 50;

  return {
    minX: x,
    minY: y,
    maxX: x + width,
    maxY: y + height,
  };
};

const translateBounds = (
  bounds: NodeBounds,
  dx: number,
  dy: number,
): NodeBounds => ({
  minX: bounds.minX + dx,
  maxX: bounds.maxX + dx,
  minY: bounds.minY + dy,
  maxY: bounds.maxY + dy,
});

const overlaps1D = (aMin: number, aMax: number, bMin: number, bMax: number) =>
  aMin < bMax && bMin < aMax;

const resolveSiblingOverlapsAfterResize = (
  sourceNodes: ChainGraphNode[],
  resizedNodeIds: string[],
  direction: string,
): ChainGraphNode[] => {
  if (!resizedNodeIds.length) return sourceNodes;

  const isHorizontal = direction === "RIGHT";

  let nextNodes = sourceNodes;
  let activeIds = new Set(resizedNodeIds);

  for (let pass = 0; pass < MAX_OVERLAP_RESOLVE_PASSES; pass += 1) {
    const nodeMap = new Map(nextNodes.map((node) => [node.id, node]));
    const affectedParentIds = new Set<string | undefined>();

    for (const id of activeIds) {
      const node = nodeMap.get(id);
      if (node) affectedParentIds.add(node.parentId);
    }

    if (!affectedParentIds.size) break;

    let passChanged = false;
    const nextActiveIds = new Set<string>();

    for (const parentId of affectedParentIds) {
      const siblings = nextNodes.filter(
        (node) => !node.hidden && node.parentId === parentId,
      );

      if (siblings.length < 2) continue;

      const orderedSiblings = [...siblings].sort((left, right) => {
        const leftMain = isHorizontal
          ? left.position?.x ?? 0
          : left.position?.y ?? 0;
        const rightMain = isHorizontal
          ? right.position?.x ?? 0
          : right.position?.y ?? 0;

        if (leftMain !== rightMain) return leftMain - rightMain;

        const leftSecondary = isHorizontal
          ? left.position?.y ?? 0
          : left.position?.x ?? 0;
        const rightSecondary = isHorizontal
          ? right.position?.y ?? 0
          : right.position?.x ?? 0;

        if (leftSecondary !== rightSecondary) {
          return leftSecondary - rightSecondary;
        }

        return left.id.localeCompare(right.id);
      });

      const shiftedPositions = new Map<string, { x: number; y: number }>();
      const placedBounds: NodeBounds[] = [];

      for (const sibling of orderedSiblings) {
        const originalBounds = getNodeBounds(sibling);

        let dx = 0;
        let dy = 0;

        for (const previousBounds of placedBounds) {
          const currentBounds = translateBounds(originalBounds, dx, dy);

          const orthogonalOverlap = isHorizontal
            ? overlaps1D(
              currentBounds.minY,
              currentBounds.maxY,
              previousBounds.minY,
              previousBounds.maxY,
            )
            : overlaps1D(
              currentBounds.minX,
              currentBounds.maxX,
              previousBounds.minX,
              previousBounds.maxX,
            );

          if (!orthogonalOverlap) continue;

          if (isHorizontal) {
            const requiredDx =
              previousBounds.maxX + RELAYOUT_OVERLAP_GAP - currentBounds.minX;

            if (requiredDx > 0) {
              dx += requiredDx;
            }
          } else {
            const requiredDy =
              previousBounds.maxY + RELAYOUT_OVERLAP_GAP - currentBounds.minY;

            if (requiredDy > 0) {
              dy += requiredDy;
            }
          }
        }

        const resolvedBounds = translateBounds(originalBounds, dx, dy);
        placedBounds.push(resolvedBounds);

        if (dx === 0 && dy === 0) continue;

        shiftedPositions.set(sibling.id, {
          x: (sibling.position?.x ?? 0) + dx,
          y: (sibling.position?.y ?? 0) + dy,
        });

        nextActiveIds.add(sibling.id);
        passChanged = true;
      }

      if (!shiftedPositions.size) continue;

      nextNodes = nextNodes.map((node) => {
        const shiftedPosition = shiftedPositions.get(node.id);
        if (!shiftedPosition) return node;

        return {
          ...node,
          position: shiftedPosition,
        };
      });
    }

    if (!passChanged) break;

    activeIds = nextActiveIds;
  }

  return nextNodes;
};

export const useChainGraph = () => {
  const chainContext = useContext(ChainContext);
  const { screenToFlowPosition, getIntersectingNodes } = useReactFlow();
  const { libraryElements, isLibraryLoading } = useLibraryContext();

  const [nodes, setNodes] = useNodesState<Node<ChainGraphNodeData>>([]);
  const [edges, setEdges] = useEdgesState<Edge>([]);
  const [isLoading, setIsLoading] = useState(true);

  const isInitialized = useRef<boolean>(false);
  const nodesRef = useRef(nodes);

  const notificationService = useNotificationService();
  const { arrangeNodes, direction, toggleDirection } = useAutoLayout();

  const prevArrangeNodesRef = useRef<typeof arrangeNodes | null>(null);
  const structureChangedRef = useRef<boolean>(false);
  const structureChangedParentIdsRef = useRef<string[] | null>(null);
  const structureChangedResolveOverlapsRef = useRef<boolean>(true);

  const onChainUpdate = useCallback(async () => {
    if (chainContext?.refresh) {
      await chainContext.refresh();
    }
  }, [chainContext]);

  useEffect(() => {
    nodesRef.current = nodes;
  }, [nodes]);

  const { decorativeEdges, setDecorativeEdges } = useDecorativeEdges(
    nodes,
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
    (parentIds?: string[], options?: StructureChangedOptions) => {
      structureChangedRef.current = true;
      structureChangedResolveOverlapsRef.current =
        options?.resolveOverlaps ?? true;

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
    nodes,
    setNodes,
    edges,
    setEdges,
    structureChanged,
  );

  useEffect(() => {
    const directionChanged =
      prevArrangeNodesRef.current !== null &&
      prevArrangeNodesRef.current !== arrangeNodes;
    prevArrangeNodesRef.current = arrangeNodes;

    const autoArrange = async () => {
      const shouldRunForDirection =
        directionChanged && isInitialized.current && nodes.length > 0;

      if (!structureChangedRef.current && !shouldRunForDirection) return;

      structureChangedRef.current = false;

      const shouldResolveOverlaps =
        structureChangedResolveOverlapsRef.current && !shouldRunForDirection;

      const parentIds = shouldRunForDirection
        ? null
        : structureChangedParentIdsRef.current;

      let arrangedNodes: ChainGraphNode[];

      if (parentIds && parentIds.length) {
        const nodeMap = new Map(
          nodes.map((node) => [node.id, node]),
        );
        const sorted = [...new Set(parentIds)].sort(
          (left, right) => depthOf(right, nodeMap) - depthOf(left, nodeMap),
        );

        let current = nodes;
        for (const parentId of sorted) {
          const subNodes = collectSubgraphByParents([parentId], current);
          const subEdges = edgesForSubgraph(edges, subNodes);
          const laidSubset = await arrangeNodes(subNodes, subEdges);

          const pinned = new Set(expandWithParent([parentId], current));
          current = mergeWithPinnedPositions(current, laidSubset, pinned);
        }

        arrangedNodes = current;
      } else {
        arrangedNodes = await arrangeNodes(nodes, edges);
      }

      const overlapResolvedNodes =
        shouldResolveOverlaps && parentIds && parentIds.length
          ? resolveSiblingOverlapsAfterResize(
            arrangedNodes,
            parentIds,
            direction,
          )
          : arrangedNodes;

      const withToggle = attachToggle(overlapResolvedNodes);
      const visibleNodes = reapplyNodesVisibility(withToggle);
      const withCounts = setNestedUnitCounts(visibleNodes);
      const orderedVisibleNodes = sortParentsBeforeChildren(withCounts);
      const visibleEdges = reapplyEdgesVisibility(withToggle, edges);

      setNodes(orderedVisibleNodes);
      setEdges(visibleEdges);

      structureChangedParentIdsRef.current = null;
      structureChangedResolveOverlapsRef.current = true;
    };

    void autoArrange();
  }, [
    nodes,
    edges,
    arrangeNodes,
    direction,
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
    if (!chainContext?.chain) return;

    isInitialized.current = true;

    const fetchData = async () => {
      if (!chainContext?.chain) {
        return;
      }

      setIsLoading(true);

      try {
        const elements: Element[] = [];

        traverseElementsDepthFirst(chainContext.chain.elements, (element) =>
          elements.push(element),
        );

        const newNodes: ChainGraphNode[] = elements
          .map((element: Element) =>
            getNodeFromElement(
              element,
              getLibraryElement(element, libraryElements),
              direction,
            ),
          )
          .filter((n): n is ChainGraphNode => !!n);

        const newEdges: Edge[] = chainContext.chain.dependencies.map(
          (connection: Connection) => ({
            id: connection.id,
            source: connection.from,
            target: connection.to,
          }),
        );

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
  }, [chainContext, isLibraryLoading]);

  const onConnect = useCallback(
    async (connection: ReactFlowConnection) => {
      if (!chainContext?.chain) return;

      try {
        const response = await api.createConnection(
          { from: connection.source, to: connection.target },
          chainContext.chain.id,
        );

        const createdId = response.createdDependencies?.[0]?.id;
        if (!createdId) return;

        const edge: Edge = { ...connection, id: createdId };
        setEdges((eds) => addEdge(edge, eds));

        const sourceParent = nodes.find(
          (node) => node.id === connection.source,
        )?.parentId;
        const targetParent = nodes.find(
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
    [nodes, notificationService, setEdges, structureChanged, onChainUpdate, chainContext?.chain],
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
      if (!chainContext?.chain) return;

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
        const response = await api.createElement(
          createElementRequest,
          chainContext.chain.id,
        );

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

        const allNodes = nodes
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
          structureChanged([parentNode?.id ?? newNode.parentId], {
            resolveOverlaps: false,
          });
        }

        if (onChainUpdate) {
          void onChainUpdate();
        }

        clearDragVisuals();
      } catch (error) {
        notificationService.errorWithDetails(
          "Failed to create element",
          getErrorMessage(error, "Failed to create element"),
          error,
        );
      }
    },
    [screenToFlowPosition, getIntersectingNodes, libraryElements, direction, nodes, edges, arrangeNodes, attachToggle, setNestedUnitCounts, setNodes, notificationService, structureChanged, clearDragVisuals, onChainUpdate, chainContext?.chain],
  );

  const onEdgesChange = useCallback(
    (changes: EdgeChange[]) => {
      if (!chainContext?.chain) return;

      const baseChanges = changes.filter(
        (change) => !isDecorativeEdgeId((change as { id: string }).id),
      );
      const decoChanges = changes.filter((change) =>
        isDecorativeEdgeId((change as { id: string }).id),
      );

      const baseNonRemove = baseChanges.filter(
        (change) => change.type !== "remove",
      );
      const decoNonRemove = decoChanges.filter(
        (change) => change.type !== "remove",
      );

      if (baseNonRemove.length) {
        setEdges((eds) => applyEdgeChanges(baseNonRemove, eds));
      }

      if (decoNonRemove.length) {
        setDecorativeEdges((eds) => applyEdgeChanges(decoNonRemove, eds));
      }
    },
    [setEdges, setDecorativeEdges, chainContext?.chain],
  );

  const onNodesChange = useCallback(
    (changes: NodeChange<ChainGraphNode>[]) => {
      if (!chainContext?.chain) return;

      const nonRemove = changes.filter((change) => change.type !== "remove");
      if (!nonRemove.length) return;

      setNodes((nds) => {
        const next = applyNodeChanges(nonRemove, nds);
        return sortParentsBeforeChildren(next as ChainGraphNode[]);
      });
    },
    [setNodes, chainContext?.chain],
  );

  const onDelete = useCallback(
    async (changes: OnDeleteEvent) => {
      if (!chainContext?.chain) return;

      const nodesToDelete: ChainGraphNode[] = changes.nodes;
      const edgesToDelete: Edge[] = changes.edges;

      const normalizedEdges = edgesToDelete
        .map((edge) => {
          if (isDecorativeEdgeId(edge.id)) {
            const data = (edge.data ?? {}) as Partial<DecorativeEdgeData>;
            const id =
              data.originalEdgeId ?? originalEdgeIdFromDecorative(edge.id);
            const source = data.originalSource ?? "";
            const target = data.originalTarget ?? "";

            if (!id || !source || !target) return null;

            return { id, source, target };
          }

          return { id: edge.id, source: edge.source, target: edge.target };
        })
        .filter(
          (edge): edge is { id: string; source: string; target: string } =>
            !!edge,
        );

      const affectedParents = new Set<string>();

      for (const parentContainer of getContainerIdsForEdges(
        normalizedEdges,
        nodes,
      )) {
        affectedParents.add(parentContainer);
      }

      const removingNodeIds = new Set(nodesToDelete.map((node) => node.id));
      const parentMap = new Map<string, string | undefined>();

      nodes.forEach((node) => {
        if (removingNodeIds.has(node.id)) {
          parentMap.set(node.id, node.parentId);
        }
      });

      const rootIdsToDelete = Array.from(removingNodeIds).filter((id) => {
        const parent = parentMap.get(id);
        return parent === undefined || !removingNodeIds.has(parent);
      });

      for (const id of rootIdsToDelete) {
        const node = nodes.find((x) => x.id === id);
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
            chainContext.chain.id,
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
          chainContext.chain.id,
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

        const allNodes = nodes.filter(
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
        notificationService.requestFailed(
          getErrorMessage(error, "Failed to delete element"),
          error,
        );
      }
    },
    [nodes, edges, libraryElements, notificationService, arrangeNodes, setNodes, setEdges, structureChanged, onChainUpdate, chainContext?.chain],
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
      if (!chainContext?.chain) return;
      if (isLibraryLoading) return;

      clearHoverTimer();
      setNodes((curr) => applyHighlight(curr));

      const allBefore = nodesRef.current;

      const originalNode = allBefore.find((node) => node.id === draggedNode.id);
      if (!originalNode) return;

      const selected = allBefore.filter((n) => n.selected).map((n) => n.id);
      const selectedIds = selected.length ? selected : [originalNode.id];

      const originalParentId = originalNode.parentId;

      let newParentNode: Node | undefined = undefined;
      const possibleGraphIntersect: Node | undefined =
        getPossibleGraphIntersection(
          getIntersectingNodes(draggedNode),
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

        const response = await api.transferElement(
          request,
          chainContext.chain.id,
        );

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
          getErrorMessage(error, "Failed to drag element"),
          error,
        );

        finalParentId = originalParentId;
      }

      setNodes((prev) => {
        const snapshot = nodesRef.current;
        const parentAbs = getParentAbsolutePosition(finalParentId, snapshot);

        const next = prev.map((node) => {
          if (!selectedIds.includes(node.id)) return node;

          const currentNode = snapshot.find((z) => z.id === node.id);
          if (!currentNode) return node;

          const nowAbs = getAbsolutePosition(currentNode, snapshot);

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
        nodesRef.current,
      );

      structureChanged(
        affectedParentIds.length ? affectedParentIds : undefined,
      );
    },
    [isLibraryLoading, getIntersectingNodes, libraryElements, notificationService, setNodes, structureChanged, clearHoverTimer, onChainUpdate, chainContext?.chain],
  );

  const updateNodeData = useCallback(
    (element: Element, node: ChainGraphNode) => {
      setNodes((prevNodes) =>
        prevNodes.map((prevNode) => {
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
