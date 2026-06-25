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

const requestHoverVisualsFrame = (callback: FrameRequestCallback): number => {
  if (
    typeof window !== "undefined" &&
    typeof window.requestAnimationFrame === "function"
  ) {
    return window.requestAnimationFrame(callback);
  }

  return globalThis.setTimeout(
    () => callback(Date.now()),
    16,
  ) as unknown as number;
};

const cancelHoverVisualsFrame = (frameId: number): void => {
  if (
    typeof window !== "undefined" &&
    typeof window.cancelAnimationFrame === "function"
  ) {
    window.cancelAnimationFrame(frameId);
    return;
  }

  globalThis.clearTimeout(frameId);
};

type ChainGraphNodeMap = Map<string, ChainGraphNode>;

const getRelayoutRootIdsForContentChanges = (
  changedContainerIds: string[],
  sourceNodes: ChainGraphNode[],
): string[] | undefined => {
  const nodeMap = buildNodeMap(sourceNodes);
  const relayoutRootIds = new Set<string>();

  for (const changedContainerId of changedContainerIds) {
    const changedContainer = nodeMap.get(changedContainerId);

    if (!changedContainer) {
      continue;
    }

    if (changedContainer.parentId) {
      relayoutRootIds.add(changedContainer.parentId);
    } else {
      return undefined;
    }
  }

  return relayoutRootIds.size ? Array.from(relayoutRootIds) : undefined;
};

const buildNodeMap = (nodes: ChainGraphNode[]): ChainGraphNodeMap =>
  new Map(nodes.map((node) => [node.id, node]));

const getAbsolutePosition = (
  node: ChainGraphNode,
  nodeMap: ChainGraphNodeMap,
) => {
  let x = node.position?.x ?? 0;
  let y = node.position?.y ?? 0;
  let parentId = node.parentId;

  const seen = new Set<string>();

  while (parentId && !seen.has(parentId)) {
    seen.add(parentId);

    const parent = nodeMap.get(parentId);

    if (!parent) {
      break;
    }

    x += parent.position?.x ?? 0;
    y += parent.position?.y ?? 0;
    parentId = parent.parentId;
  }

  return { x, y };
};

const getParentAbsolutePosition = (
  parentId: string | undefined,
  nodeMap: ChainGraphNodeMap,
) => {
  if (!parentId) {
    return { x: 0, y: 0 };
  }

  const parent = nodeMap.get(parentId);

  if (!parent) {
    return { x: 0, y: 0 };
  }

  return getAbsolutePosition(parent, nodeMap);
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
          ? (left.position?.x ?? 0)
          : (left.position?.y ?? 0);
        const rightMain = isHorizontal
          ? (right.position?.x ?? 0)
          : (right.position?.y ?? 0);

        if (leftMain !== rightMain) return leftMain - rightMain;

        const leftSecondary = isHorizontal
          ? (left.position?.y ?? 0)
          : (left.position?.x ?? 0);
        const rightSecondary = isHorizontal
          ? (right.position?.y ?? 0)
          : (right.position?.x ?? 0);

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
  const edgesRef = useRef(edges);

  const hoverVisualsFrameRef = useRef<number | null>(null);
  const pendingHoverVisualsJobRef = useRef<
    (() => ChainGraphNode | undefined) | null
  >(null);

  const notificationService = useNotificationService();
  const { arrangeNodes, direction, toggleDirection } = useAutoLayout();

  const prevArrangeNodesRef = useRef<typeof arrangeNodes | null>(null);
  const structureChangedRef = useRef<boolean>(false);
  const structureChangedParentIdsRef = useRef<string[] | null>(null);
  const structureChangedResolveOverlapsRef = useRef<boolean>(true);

  const layoutRequestIdRef = useRef(0);
  type LayoutIdleWaiter = {
    resolve: () => void;
    reject: (reason?: unknown) => void;
  };
  const layoutIdleWaitersRef = useRef<LayoutIdleWaiter[]>([]);

  const waitForNextAutoArrange = useCallback(
    () =>
      new Promise<void>((resolve, reject) => {
        layoutIdleWaitersRef.current.push({ resolve, reject });
      }),
    [],
  );

  const onChainUpdate = useCallback(async () => {
    if (chainContext?.refresh) {
      await chainContext.refresh();
    }
  }, [chainContext]);

  useEffect(() => {
    nodesRef.current = nodes;
  }, [nodes]);

  useEffect(() => {
    edgesRef.current = edges;
  }, [edges]);

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

  const cancelPendingHoverVisuals = useCallback(() => {
    if (hoverVisualsFrameRef.current !== null) {
      cancelHoverVisualsFrame(hoverVisualsFrameRef.current);
      hoverVisualsFrameRef.current = null;
    }

    pendingHoverVisualsJobRef.current = null;
    clearHoverTimer();
  }, [clearHoverTimer]);

  const flushHoverVisuals = useCallback(() => {
    hoverVisualsFrameRef.current = null;

    const job = pendingHoverVisualsJobRef.current;
    pendingHoverVisualsJobRef.current = null;

    if (!job) return;

    const node = job();

    if (!node) return;

    clearHoverTimer();
    highlightDragIntersections(node);
    expandDragIntersection(node);
  }, [clearHoverTimer, expandDragIntersection, highlightDragIntersections]);

  const scheduleHoverVisuals = useCallback(
    (job: () => ChainGraphNode | undefined) => {
      pendingHoverVisualsJobRef.current = job;

      if (hoverVisualsFrameRef.current !== null) {
        return;
      }

      hoverVisualsFrameRef.current =
        requestHoverVisualsFrame(flushHoverVisuals);
    },
    [flushHoverVisuals],
  );

  useEffect(() => cancelPendingHoverVisuals, [cancelPendingHoverVisuals]);

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
  } = useExpandCollapse(nodes, setNodes, edges, setEdges, structureChanged);

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

      let hadArrangeFailure = false;
      let arrangeError: unknown;
      try {
        const shouldResolveOverlaps =
          structureChangedResolveOverlapsRef.current && !shouldRunForDirection;

        const parentIds = shouldRunForDirection
          ? null
          : structureChangedParentIdsRef.current;

        let arrangedNodes: ChainGraphNode[];

        const layoutSourceNodes = reapplyNodesVisibility(nodes);
        const layoutSourceEdges = reapplyEdgesVisibility(
          layoutSourceNodes,
          edges,
        );

        if (parentIds && parentIds.length) {
          const nodeMap = new Map(
            layoutSourceNodes.map((node) => [node.id, node]),
          );

          const sorted = [...new Set(parentIds)].sort(
            (left, right) => depthOf(right, nodeMap) - depthOf(left, nodeMap),
          );

          let current = layoutSourceNodes;

          for (const parentId of sorted) {
            const subNodes = collectSubgraphByParents([parentId], current);
            const subEdges = edgesForSubgraph(layoutSourceEdges, subNodes);
            const laidSubset = await arrangeNodes(subNodes, subEdges);

            const pinned = new Set(expandWithParent([parentId], current));
            current = mergeWithPinnedPositions(current, laidSubset, pinned);
          }

          arrangedNodes = current;
        } else {
          arrangedNodes = await arrangeNodes(
            layoutSourceNodes,
            layoutSourceEdges,
          );
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
        const visibleEdges = reapplyEdgesVisibility(
          visibleNodes,
          layoutSourceEdges,
        );

        setNodes(orderedVisibleNodes);
        setEdges(visibleEdges);

        structureChangedParentIdsRef.current = null;
        structureChangedResolveOverlapsRef.current = true;
      } catch (err) {
        hadArrangeFailure = true;
        arrangeError = err;
      } finally {
        requestAnimationFrame(() => {
          requestAnimationFrame(() => {
            const waiters = layoutIdleWaitersRef.current.splice(0);
            if (hadArrangeFailure) {
              for (const { reject } of waiters) {
                reject(arrangeError);
              }
            } else {
              for (const { resolve } of waiters) {
                resolve();
              }
            }
          });
        });
      }
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

  const layoutAndCommit = useCallback(
    async (
      sourceNodes: ChainGraphNode[],
      sourceEdges: Edge[],
      parentIds?: string[],
      options?: StructureChangedOptions,
    ) => {
      const requestId = ++layoutRequestIdRef.current;
      const shouldResolveOverlaps = options?.resolveOverlaps ?? true;

      let arrangedNodes: ChainGraphNode[];

      if (parentIds && parentIds.length) {
        const expandedParentIds = Array.from(
          new Set(expandWithParent(parentIds, sourceNodes)),
        );

        const relayoutParentIds = [...expandedParentIds];

        const nodeMap = new Map(sourceNodes.map((node) => [node.id, node]));

        const sorted = [...expandedParentIds].sort(
          (left, right) => depthOf(right, nodeMap) - depthOf(left, nodeMap),
        );

        let current = sourceNodes;

        for (const parentId of sorted) {
          const subNodes = collectSubgraphByParents([parentId], current);
          const subEdges = edgesForSubgraph(sourceEdges, subNodes);
          const laidSubset = await arrangeNodes(subNodes, subEdges);

          const pinned = new Set(expandWithParent([parentId], current));
          current = mergeWithPinnedPositions(current, laidSubset, pinned);
        }

        arrangedNodes = current;

        if (shouldResolveOverlaps) {
          arrangedNodes = resolveSiblingOverlapsAfterResize(
            arrangedNodes,
            relayoutParentIds,
            direction,
          );
        }
      } else {
        arrangedNodes = await arrangeNodes(sourceNodes, sourceEdges);
      }

      if (requestId !== layoutRequestIdRef.current) {
        return;
      }

      const withToggle = attachToggle(arrangedNodes);
      const visibleNodes = reapplyNodesVisibility(withToggle);
      const withCounts = setNestedUnitCounts(visibleNodes);
      const orderedNodes = sortParentsBeforeChildren(withCounts);
      const visibleEdges = reapplyEdgesVisibility(visibleNodes, sourceEdges);

      setNodes(orderedNodes);
      setEdges(visibleEdges);
    },
    [
      arrangeNodes,
      direction,
      attachToggle,
      reapplyNodesVisibility,
      reapplyEdgesVisibility,
      setNestedUnitCounts,
      setNodes,
      setEdges,
    ],
  );

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
    [
      nodes,
      notificationService,
      setEdges,
      structureChanged,
      onChainUpdate,
      chainContext?.chain,
    ],
  );

  const onDragOver = useCallback(
    (event: DragEvent<HTMLDivElement>) => {
      event.preventDefault();
      event.dataTransfer.dropEffect = "move";

      const { clientX, clientY } = event;

      scheduleHoverVisuals(() => {
        const currentDragPosition = screenToFlowPosition({
          x: clientX,
          y: clientY,
        });

        return getFakeNode(currentDragPosition);
      });
    },
    [scheduleHoverVisuals, screenToFlowPosition],
  );

  const onDrop = useCallback(
    async (event: React.DragEvent) => {
      event.preventDefault();
      cancelPendingHoverVisuals();

      if (!chainContext?.chain) return;

      const name = event.dataTransfer.getData("application/reactflow");
      if (!name) return;

      const currentNodes = nodesRef.current;
      const currentEdges = edgesRef.current;

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

      const targetParentId = parentNode?.id;

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

        const nodeMap = buildNodeMap(currentNodes);

        const rootCreatedNodePosition = (() => {
          if (!targetParentId) {
            return dropPosition;
          }

          const parentAbs = getParentAbsolutePosition(targetParentId, nodeMap);

          return {
            x: dropPosition.x - parentAbs.x,
            y: dropPosition.y - parentAbs.y,
          };
        })();

        const createdElements: Element[] = [];

        traverseElementsDepthFirst([createdElement], (element) => {
          createdElements.push(element);
        });

        const createdNodes: ChainGraphNode[] = createdElements
          .map((element) => {
            const node = getNodeFromElement(
              element,
              getLibraryElement(element, libraryElements),
              direction,
              element.id === createdElement.id
                ? rootCreatedNodePosition
                : undefined,
            );

            if (!node) return undefined;

            if (element.id === createdElement.id && targetParentId) {
              return {
                ...node,
                parentId: targetParentId,
                position: rootCreatedNodePosition,
              };
            }

            return node;
          })
          .filter((node): node is ChainGraphNode => !!node);

        const newNode = createdNodes.find(
          (node) => node.id === createdElement.id,
        );

        if (!newNode) return;

        const updatedNodes = buildGraphNodes(
          response.updatedElements ?? [],
          libraryElements,
        );

        const draftNodeById = new Map<string, ChainGraphNode>();

        for (const node of currentNodes) {
          draftNodeById.set(node.id, node);
        }

        for (const node of updatedNodes) {
          draftNodeById.set(node.id, node);
        }

        for (const node of createdNodes) {
          draftNodeById.set(node.id, node);
        }

        const draftNodes = sortParentsBeforeChildren(
          Array.from(draftNodeById.values()),
        );

        const hasCreatedSubtree = createdNodes.some(
          (node) => node.id !== newNode.id,
        );

        const changedContainerId = targetParentId ?? newNode.parentId;

        if (changedContainerId) {
          const relayoutRootIds = getRelayoutRootIdsForContentChanges(
            [changedContainerId],
            draftNodes,
          );

          await layoutAndCommit(draftNodes, currentEdges, relayoutRootIds);
        } else if (hasCreatedSubtree) {
          await layoutAndCommit(draftNodes, currentEdges, [newNode.id]);
        } else {
          layoutRequestIdRef.current += 1;

          const withToggle = attachToggle(draftNodes);
          const visibleNodes = reapplyNodesVisibility(withToggle);
          const withCount = setNestedUnitCounts(visibleNodes);
          const ordered = sortParentsBeforeChildren(withCount);
          const visibleEdges = reapplyEdgesVisibility(
            visibleNodes,
            currentEdges,
          );

          setNodes(ordered);
          setEdges(visibleEdges);
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
    [
      chainContext?.chain,
      screenToFlowPosition,
      getIntersectingNodes,
      libraryElements,
      direction,
      layoutAndCommit,
      attachToggle,
      reapplyNodesVisibility,
      reapplyEdgesVisibility,
      setNestedUnitCounts,
      setNodes,
      setEdges,
      notificationService,
      clearDragVisuals,
      cancelPendingHoverVisuals,
      onChainUpdate,
    ],
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

        if (node?.parentId) {
          affectedParents.add(node.parentId);
        }
      }

      const deletedEdgeIds = new Set<string>();

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
            deletedEdgeIds.add(connection.id),
          );
        } catch (error) {
          notificationService.requestFailed(
            "Failed to delete connections",
            error,
          );
        }
      }

      try {
        const elementsDeleteResponse =
          rootIdsToDelete.length > 0
            ? await api.deleteElements(rootIdsToDelete, chainContext.chain.id)
            : undefined;

        const deletedNodeIds = new Set(
          (elementsDeleteResponse?.removedElements ?? []).map(
            (element) => element.id,
          ),
        );

        elementsDeleteResponse?.removedDependencies?.forEach((connection) =>
          deletedEdgeIds.add(connection.id),
        );

        const updatedNodes = buildGraphNodes(
          elementsDeleteResponse?.updatedElements ?? [],
          libraryElements,
        );

        const updatedNodeIds = new Set(updatedNodes.map((node) => node.id));

        const draftNodes = nodes
          .filter(
            (node) =>
              !deletedNodeIds.has(node.id) && !updatedNodeIds.has(node.id),
          )
          .concat(updatedNodes);

        const draftEdges = edges.filter((edge) => !deletedEdgeIds.has(edge.id));

        const affectedParentIds = Array.from(affectedParents);

        if (affectedParentIds.length) {
          const relayoutRootIds = getRelayoutRootIdsForContentChanges(
            affectedParentIds,
            draftNodes,
          );

          await layoutAndCommit(draftNodes, draftEdges, relayoutRootIds);
        } else {
          layoutRequestIdRef.current += 1;

          const withToggle = attachToggle(draftNodes);
          const visibleNodes = reapplyNodesVisibility(withToggle);
          const withCounts = setNestedUnitCounts(visibleNodes);
          const orderedNodes = sortParentsBeforeChildren(withCounts);
          const visibleEdges = reapplyEdgesVisibility(visibleNodes, draftEdges);

          setNodes(orderedNodes);
          setEdges(visibleEdges);
        }

        if (onChainUpdate) {
          void onChainUpdate();
        }
      } catch (error) {
        notificationService.requestFailed(
          getErrorMessage(error, "Failed to delete element"),
          error,
        );
      }
    },
    [
      chainContext?.chain,
      nodes,
      edges,
      libraryElements,
      notificationService,
      layoutAndCommit,
      attachToggle,
      reapplyNodesVisibility,
      reapplyEdgesVisibility,
      setNestedUnitCounts,
      setNodes,
      setEdges,
      onChainUpdate,
    ],
  );

  const handleDragInteraction = useCallback(
    (_: React.MouseEvent, draggedNode: ChainGraphNode) => {
      scheduleHoverVisuals(() => draggedNode);
    },
    [scheduleHoverVisuals],
  );

  const isNodeInsideForbiddenSubtree = (
    nodeId: string | undefined,
    forbiddenRootIds: Set<string>,
    nodeMap: ChainGraphNodeMap,
  ): boolean => {
    if (!nodeId) {
      return false;
    }

    let currentId: string | undefined = nodeId;
    const seen = new Set<string>();

    while (currentId && !seen.has(currentId)) {
      if (forbiddenRootIds.has(currentId)) {
        return true;
      }

      seen.add(currentId);
      currentId = nodeMap.get(currentId)?.parentId;
    }

    return false;
  };

  const onNodeDragStop = useCallback(
    async (_event: React.MouseEvent, draggedNode: ChainGraphNode) => {
      if (!chainContext?.chain) return;
      if (isLibraryLoading) return;

      cancelPendingHoverVisuals();

      const allBefore = applyHighlight(nodesRef.current);
      const nodeMap = buildNodeMap(allBefore);

      const originalNode = nodeMap.get(draggedNode.id);

      if (!originalNode) {
        return;
      }

      const selected = allBefore
        .filter((node) => node.selected)
        .map((node) => node.id);

      const selectedIds = selected.length ? selected : [originalNode.id];
      const selectedIdSet = new Set(selectedIds);

      const originalParentId = originalNode.parentId;

      const draggedChildren = collectChildren(draggedNode.id, allBefore);
      const draggedSubtreeIds = new Set<string>([
        draggedNode.id,
        ...draggedChildren.map((node) => node.id),
      ]);

      let newParentNode: Node | undefined = undefined;

      const possibleGraphIntersect: Node | undefined =
        getPossibleGraphIntersection(
          getIntersectingNodes(draggedNode).filter(
            (node) => !draggedSubtreeIds.has(node.id),
          ),
          draggedChildren,
        ) ?? undefined;

      if (possibleGraphIntersect !== undefined) {
        newParentNode = getIntersectionParent(
          draggedNode,
          possibleGraphIntersect,
          libraryElements ?? [],
        );
      }

      const parentNodeId = newParentNode?.id ?? undefined;

      const isInvalidParentTarget = isNodeInsideForbiddenSubtree(
        parentNodeId,
        selectedIdSet,
        nodeMap,
      );

      if (isInvalidParentTarget) {
        setNodes(allBefore);
        return;
      }

      const isParentChanged = parentNodeId !== originalParentId;

      if (!isParentChanged) {
        setNodes(allBefore);
        return;
      }

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

      const parentAbs = getParentAbsolutePosition(finalParentId, nodeMap);

      const draftNodes = allBefore.map((node) => {
        if (!selectedIdSet.has(node.id)) {
          return node;
        }

        const nowAbs = getAbsolutePosition(node, nodeMap);

        return {
          ...node,
          parentId: finalParentId ?? undefined,
          position: {
            x: nowAbs.x - parentAbs.x,
            y: nowAbs.y - parentAbs.y,
          },
        };
      });

      const affectedParentIds = computeAffectedParents(
        originalParentId,
        finalParentId,
        allBefore,
      );

      await layoutAndCommit(
        sortParentsBeforeChildren(draftNodes),
        edgesRef.current,
        affectedParentIds.length ? affectedParentIds : undefined,
      );
    },
    [
      chainContext?.chain,
      isLibraryLoading,
      getIntersectingNodes,
      libraryElements,
      notificationService,
      setNodes,
      layoutAndCommit,
      cancelPendingHoverVisuals,
      onChainUpdate,
    ],
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
    waitForNextAutoArrange,
  };
};
