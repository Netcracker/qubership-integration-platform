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
  MouseEvent,
  useCallback,
  useEffect,
  useRef,
  useState
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
  collectChildren,
  getContainerIdsForEdges,
  collectSubgraphByParents,
  edgesForSubgraph,
  findUpdatedElement,
  getDataFromElement,
  getFakeNode,
  getIntersectionParent,
  getLibraryElement,
  getNodeFromElement,
  getPossibleGraphIntersection,
  sortParentsBeforeChildren,
  getLeastCommonParent,
  expandWithParent,
  depthOf,
  mergeWithPinnedPositions,
} from "../../misc/chain-graph-utils.ts";
import {
  ChainGraphNode,
  ChainGraphNodeData,
  OnDeleteEvent,
} from "../../components/graph/nodes/ChainGraphNodeTypes.ts";
import { v4 as uuidv4 } from "uuid";
import { ContextMenuData } from "../../components/graph/ContextMenu.tsx";

export const useChainGraph = (
  chainId?: string,
  onChainUpdate?: () => void | Promise<void>,
) => {
  const { screenToFlowPosition } = useReactFlow();
  const { libraryElements, isLibraryLoading } = useLibraryContext();

  const [nodes, setNodes] = useNodesState<Node<ChainGraphNodeData>>([]);
  const [edges, setEdges] = useEdgesState<Edge>([]);
  const [isLoading, setIsLoading] = useState(true);

  const isInitialized = useRef<boolean>(false);
  const nodesRef = useRef(nodes);

  const notificationService = useNotificationService();
  const { arrangeNodes, direction, toggleDirection } = useAutoLayout();
  const { getIntersectingNodes } = useReactFlow();

  const structureChangedRef = useRef<boolean>(false);
  const structureChangedParentIdsRef = useRef<string[] | null>(null);

  const hoverExpandTimerRef = useRef<number | null>(null);
  const lastHoverContainerIdRef = useRef<string | null>(null);
  const [menu, setMenu] = useState<ContextMenuData | null>(null);

  useEffect(() => {
    nodesRef.current = nodes;
  }, [nodes]);

  const clearHoverTimer = useCallback(() => {
    if (hoverExpandTimerRef.current !== null) {
      window.clearTimeout(hoverExpandTimerRef.current);
      hoverExpandTimerRef.current = null;
    }
    lastHoverContainerIdRef.current = null;
  }, []);

  const clearHighlight = useCallback(() => {
    setNodes((curr) => applyHighlight(curr)); // drop .highlight
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

      if (candidateId !== lastHoverContainerIdRef.current) {
        clearHoverTimer();
        lastHoverContainerIdRef.current = candidateId;

        if (possibleGraphIntersect && possibleGraphIntersect.data?.collapsed) {
          hoverExpandTimerRef.current = window.setTimeout(() => {
            if (lastHoverContainerIdRef.current !== candidateId) return;
            const current = nodesRef.current.find((n) => n.id === candidateId);
            if (current?.type === "container" && current.data?.collapsed) {
              current.data.onToggleCollapse?.();
            }
          }, 250);
        }
      }
    },
    [getIntersectingNodes, nodes, clearHoverTimer],
  );

  const structureChanged = useCallback(
    (parentIds?: string[]) => {
      structureChangedRef.current = true;
      if (parentIds && parentIds.length) {
        const expanded = expandWithParent(parentIds, nodesRef.current);
        structureChangedParentIdsRef.current = Array.from(new Set(expanded));
      } else {
        structureChangedParentIdsRef.current = null; // full rebuild
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
  } = useExpandCollapse(nodes, setNodes, edges, setEdges, structureChanged);

  useEffect(() => {
    const autoArrange = async () => {
      if (!structureChangedRef.current) return;
      structureChangedRef.current = false;

      const parentIds = structureChangedParentIdsRef.current;
      let arrangedNodes: ChainGraphNode[] = nodes;

      if (parentIds && parentIds.length) {
        const nodeMap = new Map(nodes.map((node) => [node.id, node]));
        const sorted = [...new Set(parentIds)].sort(
          (left, right) => depthOf(right, nodeMap) - depthOf(left, nodeMap),
        );

        let current = nodes;
        for (const parents of sorted) {
          const subNodes = collectSubgraphByParents([parents], current);
          const subEdges = edgesForSubgraph(edges, subNodes);
          const laidSubset = await arrangeNodes(subNodes, subEdges);

          const pinned = new Set(expandWithParent([parents], current));
          current = mergeWithPinnedPositions(current, laidSubset, pinned);
        }
        arrangedNodes = current;
      } else {
        arrangedNodes = await arrangeNodes(nodes, edges);
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

    const fetchData = async () => {
      setIsLoading(true);
      try {
        if (!chainId) return;

        const elements = (await api.getElements(
          chainId,
        )) as unknown as Element[];

        const newNodes: ChainGraphNode[] = elements
          .map((element: Element) =>
            getNodeFromElement(
              element,
              getLibraryElement(element, libraryElements),
              direction,
            ),
          )
          .filter((n): n is ChainGraphNode => !!n);

        const connections = await api.getConnections(chainId);
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
        isInitialized.current = true;
      }
    };

    void fetchData();
  }, [
    nodes,
    direction,
    arrangeNodes,
    chainId,
    isLibraryLoading,
    libraryElements,
    notificationService,
    structureChanged,
    setEdges,
    setNodes,
    attachToggle,
    reapplyNodesVisibility,
    setNestedUnitCounts,
  ]);

  useEffect(() => () => clearHoverTimer(), [clearHoverTimer]);

  const onConnect = useCallback(
    async (connection: ReactFlowConnection) => {
      if (!chainId) return;
      try {
        const response = await api.createConnection(
          { from: connection.source, to: connection.target },
          chainId,
        );
        const edge: Edge = {
          ...connection,
          id: response.createdDependencies?.[0]?.id ?? "",
        };
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
    [chainId, nodes, notificationService, setEdges, structureChanged, onChainUpdate],
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
        (node) => node.type === "container",
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
          parentElementId: parentNode.id,
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

        const arrangedNew = await arrangeNodes(childNodes.concat(newNode), []);
        const allNodes = nodes.concat(arrangedNew);
        const withToggle = attachToggle(allNodes);
        const withCount = setNestedUnitCounts(withToggle);

        const withDropPosition = withCount.map((node: ChainGraphNode) => {
          if (node.id === newNode.id) node.position = dropPosition;
          return node;
        });

        const ordered = sortParentsBeforeChildren(withDropPosition);
        setNodes(ordered);

        if (parentNode) {
          structureChanged([parentNode.id]);
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
    ],
  );

  const onEdgesChange = useCallback(
    async (changes: EdgeChange[]) => {
      await Promise.all(
        changes.map((change) => {
          if (!chainId) return;
          if (change.type === "remove") return;
          setEdges((eds) => applyEdgeChanges(changes, eds));
        }),
      );
    },
    [chainId, setEdges],
  );

  const onNodesChange = useCallback(
    async (changes: NodeChange<ChainGraphNode>[]) => {
      await Promise.all(
        changes.map((change) => {
          if (!chainId) return;
          if (change.type === "remove") return;
          setNodes((nds) => {
            const next = applyNodeChanges(changes, nds);
            return sortParentsBeforeChildren(next as ChainGraphNode[]);
          });
        }),
      );
    },
    [chainId, setNodes],
  );

  const onDelete = useCallback(
    async (changes: OnDeleteEvent) => {
      if (!chainId) return;

      const nodesToDelete: ChainGraphNode[] = changes.nodes;
      const edgesToDelete: Edge[] = changes.edges;

      const affectedParents = new Set<string>();

      for (const parentContainer of getContainerIdsForEdges(
        edgesToDelete,
        nodes,
      )) {
        affectedParents.add(parentContainer);
      }

      const removingNodeIds = new Set(nodesToDelete.map((node) => node.id));
      const parentMap = new Map<string, string | undefined>();
      nodes.forEach((node) => {
        if (removingNodeIds.has(node.id)) parentMap.set(node.id, node.parentId);
      });

      const rootIdsToDelete = Array.from(removingNodeIds).filter(
        (id) => !removingNodeIds.has(parentMap.get(id)!),
      );
      for (const id of rootIdsToDelete) {
        const node = nodes.find((x) => x.id === id);
        if (node?.parentId) affectedParents.add(node.parentId);
      }

      const deletedEdgeIds: string[] = [];

      const separateEdgesToDelete: Edge[] = edgesToDelete.filter(
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
        ).map((element: { id: string }) => element.id);

        elementsDeleteResponse.removedDependencies?.forEach((connection) =>
          deletedEdgeIds.push(connection.id),
        );

        const allNodes = nodes.filter(
          (node) => !deletedNodeIds.includes(node.id),
        );
        const allEdges = edges.filter(
          (edge) => !deletedEdgeIds.includes(edge.id),
        );

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

  const onNodeDragStart = useCallback(
    (_: React.MouseEvent, draggedNode: ChainGraphNode) => {
      clearHoverTimer();
      highlightDragIntersections(draggedNode);
      expandDragIntersection(draggedNode);
    },
    [clearHoverTimer, expandDragIntersection, highlightDragIntersections],
  );

  const onNodeDrag = useCallback(
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

      clearHoverTimer?.();
      setNodes((curr) => applyHighlight(curr));

      const allBefore = nodesRef.current as ChainGraphNode[];

      const originalNode = allBefore.find((node) => node.id === draggedNode.id);
      if (!originalNode) return;

      const selectedIds = allBefore
        .filter((n) => n.selected)
        .map((n) => n.id) || [originalNode.id];
      if (!selectedIds.length) return;

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
          parentId: newParentNode?.id ?? null,
          elements: selectedIds,
          swimlaneId: null,
        };
        const response = await api.transferElement(request, chainId);
        finalParentId =
          findUpdatedElement(response.updatedElements, draggedNode.id)
            ?.parentElementId ?? undefined;
      } catch (error) {
        notificationService.errorWithDetails(
          "Drag element failed",
          "Failed to drag element",
          error,
        );
        finalParentId = originalParentId;
      }

      const getAbs = (node: ChainGraphNode, allNodes: ChainGraphNode[]) => {
        let x = node.position?.x ?? 0;
        let y = node.position?.y ?? 0;
        let parentId = node.parentId;
        while (parentId) {
          const parent = allNodes.find((node) => node.id === parentId);
          if (!parent) break;
          x += parent.position?.x ?? 0;
          y += parent.position?.y ?? 0;
          parentId = parent.parentId;
        }
        return { x, y };
      };

      const getParentAbs = (
        parentId: string | undefined,
        all: ChainGraphNode[],
      ) => {
        if (!parentId) return { x: 0, y: 0 };
        const parent = all.find((node) => node.id === parentId);
        if (!parent) return { x: 0, y: 0 };
        return getAbs(parent, all);
      };

      setNodes((prev) => {
        const snapshot = nodesRef.current as ChainGraphNode[];
        const parentAbs = getParentAbs(finalParentId, snapshot);

        const next = (prev as ChainGraphNode[]).map((node) => {
          if (!selectedIds.includes(node.id)) return node;

          const nowAbs = getAbs(
            snapshot.find((z) => z.id === node.id)!,
            snapshot,
          );

          const newLocal = {
            x: nowAbs.x - parentAbs.x,
            y: nowAbs.y - parentAbs.y,
          };

          return {
            ...node,
            parentId: finalParentId ?? undefined,
            position: newLocal,
          };
        });

        return sortParentsBeforeChildren(next);
      });

      const affectedParents = new Set<string>();
      if (originalParentId) affectedParents.add(originalParentId);
      if (finalParentId && finalParentId !== originalParentId)
        affectedParents.add(finalParentId);

      const leastCommonParent = getLeastCommonParent(
        originalParentId,
        finalParentId,
        nodesRef.current as ChainGraphNode[],
      );
      if (leastCommonParent) affectedParents.add(leastCommonParent);

      const affectedParentIds = Array.from(affectedParents);
      if (affectedParentIds.length) {
        structureChanged(affectedParentIds);
      } else {
        structureChanged();
      }
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
              parentId: element.parentElementId,
            };
            return updatedNode;
          }
          return prevNode;
        }),
      );
    },
    [setNodes],
  );

  const closeMenu = () => setMenu(null);

  const onContextMenuCall = (
    event: MouseEvent,
    selectedElements: Node<ChainGraphNodeData>[]
  ) => {
    event.preventDefault();

    if (!(selectedElements?.length > 0)) {
      return;
    }

    const items = [];
    // Single element
    if (selectedElements?.length === 1) {
      if (selectedElements[0].data.elementType === 'container') {
        items.push({
          id: uuidv4(),
          text: "Ungroup",
          handler: () => ungroupElements(selectedElements[0]),
        });
      }
    } else if (selectedElements?.length > 1) {
      items.push({
        id: uuidv4(),
        text: "Group",
        handler: () => groupElements(selectedElements),
      });
    }

    setMenu({
      x: event.clientX,
      y: event.clientY,
      items
    });
  };

  const groupElements = async (
    selectedElements: Node<ChainGraphNodeData>[]
  ) => {
    if (chainId == null) {
      return;
    }

    try {
      const container = await api.groupElements(
        chainId,
        selectedElements.map((node) => node.id),
      );

      const containerNode: ChainGraphNode = getNodeFromElement(
        container,
        undefined,
        direction,
        undefined,
      );
      if (!containerNode) return;

      const childNodes: ChainGraphNode[] = [];
      let nodesWithoutChangedElements = nodes;

      if (container?.children?.length) {
        nodes.forEach((prevNode) => {
          // @ts-expect-error no it's not
          for (const childrenElement of container.children) {
            if (prevNode.id === childrenElement.id) {
              const updatedNode: ChainGraphNode = {
                ...prevNode,
                parentId: containerNode.id,
              };
              childNodes.push(updatedNode);
              break;
            }
          }
        })

        const childrenElementIds = container.children.map((node) => node.id);
        nodesWithoutChangedElements = nodes.filter(node => !childrenElementIds.includes(node.id));
      }

      const arrangedNew = await arrangeNodes(childNodes.concat(containerNode), []);
      const allNodes = nodesWithoutChangedElements.concat(arrangedNew);
      const withToggle = attachToggle(allNodes);
      const withCount = setNestedUnitCounts(withToggle);

      const ordered = sortParentsBeforeChildren(withCount);
      setNodes(ordered);

      structureChanged()
    } catch (error) {
      console.error(error);
      notificationService.requestFailed("Failed to group elements", error);
    }
  };

  const ungroupElements = async (
    selectedGroup: Node<ChainGraphNodeData>
  ) => {
    if (chainId == null) {
      return;
    }

    try {
      const elements = await api.ungroupElements(chainId, selectedGroup.id);

      let nodesWithoutContainer = nodes.filter(node => node.id !== selectedGroup.id);

      if (elements?.length) {
        nodesWithoutContainer = nodesWithoutContainer.map((prevNode) => {
          for (const childrenElement of elements) {
            if (prevNode.id === childrenElement.id) {
              const updatedNode: ChainGraphNode = {
                ...prevNode,
                parentId: undefined,
              };
              return updatedNode;
            }
          }
          return prevNode;
        });
      }

      setNodes(nodesWithoutContainer);

      structureChanged();
    } catch (error) {
      console.error(error);
      notificationService.requestFailed("Failed to ungroup elements", error);
    }
  };

  return {
    nodes,
    edges,
    onConnect,
    onDragOver,
    onDrop,
    onDelete,
    onEdgesChange,
    onNodesChange,
    onNodeDragStart,
    onNodeDrag,
    onNodeDragStop,
    direction,
    toggleDirection,
    updateNodeData,
    menu,
    closeMenu,
    onContextMenuCall,
    isLoading: isLoading && isLibraryLoading,
  };
};
