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
import { useNotificationService } from "../useNotificationService.tsx";
import { useLibraryContext } from "../../components/LibraryContext.tsx";
import {
  applyHighlight,
  collectChildren,
  findUpdatedElement,
  getDataFromElement,
  getFakeNode,
  getIntersectionParent,
  getLibraryElement,
  getNodeFromElement,
  getPossibleGraphIntersection,
} from "../../misc/chain-graph-utils.ts";
import {
  ChainGraphNode,
  ChainGraphNodeData,
  OnDeleteEvent,
} from "../../components/graph/nodes/ChainGraphNodeTypes.ts";

export const useChainGraph = (chainId?: string) => {
  const { screenToFlowPosition } = useReactFlow();

  const { libraryElements, isLibraryLoading } = useLibraryContext();
  const [nodes, setNodes] = useNodesState<Node<ChainGraphNodeData>>([]);
  const [edges, setEdges] = useEdgesState<Edge>([]);
  const [isLoading, setIsLoading] = useState(true);
  const isInitialized = useRef<boolean>(false);
  const notificationService = useNotificationService();
  const { arrangeNodes, direction, toggleDirection } = useAutoLayout();
  const { getIntersectingNodes } = useReactFlow();

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

  const clearHighlight = useCallback(() => {
    setNodes((curr) => applyHighlight(curr));
  }, [setNodes]);

  const structureChangedRef = useRef<boolean>(false);

  const structureChanged = useCallback(() => {
    structureChangedRef.current = true;
    clearHighlight();
  }, [clearHighlight]);

  useEffect(() => {
    const autoArrange = async () => {
      if (structureChangedRef.current) {
        structureChangedRef.current = false;
        setNodes(await arrangeNodes(nodes, edges));
      }
    };
    void autoArrange();
  }, [nodes, edges, arrangeNodes, setNodes]);

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
          .map((element: Element) => {
            return getNodeFromElement(
              element,
              getLibraryElement(element, libraryElements),
              direction,
            );
          })
          .filter((newNode) => newNode !== undefined);

        const connections = await api.getConnections(chainId);

        const newEdges: Edge[] = connections.map((connection: Connection) => ({
          id: connection.id, //TODO better to use addEdge!
          source: connection.from,
          target: connection.to,
        }));

        setNodes(await arrangeNodes(newNodes, newEdges));
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
  ]);

  const onConnect = useCallback(
    async (connection: ReactFlowConnection) => {
      if (!chainId) return;
      try {
        const response = await api.createConnection(
          {
            from: connection.source,
            to: connection.target,
          },
          chainId,
        );
        const edge: Edge = {
          ...connection,
          id: response.createdDependencies?.[0]?.id ?? "",
        };
        setEdges((eds) => addEdge(edge, eds));
        const isUnderParent: boolean = nodes.some(
          (node: ChainGraphNode) =>
            (node.id === connection.source || node.id === connection.target) &&
            node.parentId !== undefined,
        );
        if (isUnderParent) {
          structureChanged();
        }
      } catch (error) {
        notificationService.requestFailed("Failed to create connection", error);
      }
    },
    [chainId, nodes, notificationService, setEdges, structureChanged],
  );

  const onDragOver = useCallback(
    (event: DragEvent<HTMLDivElement>) => {
      event.preventDefault();
      event.dataTransfer.dropEffect = "move";

      const currentDragPosition = screenToFlowPosition({
        x: event.clientX,
        y: event.clientY,
      });

      const fakeDragNode = getFakeNode(currentDragPosition);
      const dragIntersection = getPossibleGraphIntersection(
        getIntersectingNodes(fakeDragNode),
      )?.id;

      setNodes((nodes) => applyHighlight(nodes, dragIntersection));
    },
    [getIntersectingNodes, screenToFlowPosition, setNodes],
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

      let createElementRequest: CreateElementRequest = {
        type: name,
      };
      if (parentNode) {
        createElementRequest = {
          ...createElementRequest,
          parentElementId: parentNode.id,
        };
      }

      try {
        const response = await api.createElement(createElementRequest, chainId);

        const createdElement = response.createdElements?.[0];
        if (createdElement) {
          const newNode: ChainGraphNode = getNodeFromElement(
            createdElement,
            getLibraryElement(createdElement, libraryElements),
            direction,
          );
          if (!newNode) {
            return;
          }
          const childNodes: ChainGraphNode[] = createdElement?.children
            ? createdElement?.children?.map((child: Element) => {
                return getNodeFromElement(
                  child,
                  getLibraryElement(child, libraryElements),
                  direction,
                );
              })
            : [];
          const allNodes = nodes.concat(newNode, childNodes);
          setNodes(allNodes);
          if (parentNode || newNode.type === "container") {
            structureChanged();
          }
        }
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
      setNodes,
      structureChanged,
      notificationService,
    ],
  );

  const onEdgesChange = useCallback(
    async (changes: EdgeChange[]) => {
      await Promise.all(
        changes.map((change) => {
          if (!chainId) return;
          if (change.type === "remove") {
            return;
          }
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
          setNodes((nds) => applyNodeChanges(changes, nds));
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

      const deletedEdgeIds: string[] = [];
      const separateEdgesToDelete: Edge[] = edgesToDelete.filter(
        (edge) =>
          !nodesToDelete.find(
            (node) => node.id === edge.source || node.id === edge.target,
          ),
      );
      if (separateEdgesToDelete.length > 0) {
        try {
          const edgesDeleteResponse = await api.deleteConnections(
            separateEdgesToDelete.map((edge) => edge.id),
            chainId,
          );
          edgesDeleteResponse.removedDependencies?.map((connection) =>
            deletedEdgeIds.push(connection.id),
          );
        } catch (error) {
          notificationService.requestFailed(
            "Failed to delete connections",
            error,
          );
        }
      }

      const removingNodeIds = new Set(nodesToDelete.map((c) => c.id));
      const parentMap = new Map<string, string | undefined>();

      nodes.forEach((node) => {
        if (removingNodeIds.has(node.id)) {
          parentMap.set(node.id, node.parentId);
        }
      });

      const rootIdsToDelete = Array.from(removingNodeIds).filter(
        (id) => !removingNodeIds.has(parentMap.get(id)!),
      );

      try {
        const elementsDeleteResponse = await api.deleteElements(
          rootIdsToDelete,
          chainId,
        );

        const deletedNodeIds = (
          elementsDeleteResponse.removedElements || []
        ).map((el: { id: string }) => el.id);

        elementsDeleteResponse.removedDependencies?.map((connection) =>
          deletedEdgeIds.push(connection.id),
        );

        const allNodes = nodes.filter(
          (node) => !deletedNodeIds.includes(node.id),
        );
        const allEdges = edges.filter(
          (edge) => !deletedEdgeIds.includes(edge.id),
        );
        setNodes(allNodes);
        setEdges(allEdges);
        structureChanged();
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
    ],
  );

  const onNodeDragStart = useCallback(
    (_: React.MouseEvent, draggedNode: ChainGraphNode) => {
      highlightDragIntersections(draggedNode);
    },
    [highlightDragIntersections],
  );

  const onNodeDrag = useCallback(
    (_: React.MouseEvent, draggedNode: ChainGraphNode) => {
      highlightDragIntersections(draggedNode);
    },
    [highlightDragIntersections],
  );

  const onNodeDragStop = useCallback(
    async (_: React.MouseEvent, draggedNode: ChainGraphNode) => {
      if (!chainId) return;
      if (isLibraryLoading) return;
      const originalNode = nodes.find((n) => n.id === draggedNode.id);
      if (!originalNode) return;
      const allSelectedIds: string[] | undefined = nodes
        .filter((node) => node.selected)
        .map((node) => node.id);
      if (allSelectedIds === undefined) return;

      const originalParentId: string | undefined = originalNode.parentId;

      let newParentNode: Node | undefined = undefined;

      const possibleGraphIntersect: Node | undefined =
        getPossibleGraphIntersection(
          getIntersectingNodes(draggedNode) as ChainGraphNode[],
          collectChildren(draggedNode.id, nodes),
        ) ?? undefined;

      if (possibleGraphIntersect !== undefined) {
        newParentNode = getIntersectionParent(
          draggedNode,
          possibleGraphIntersect,
          libraryElements ?? [],
        );
      }

      const parentNodeId = newParentNode?.id ?? undefined;

      const isParentChanged: boolean = parentNodeId !== originalParentId;
      if (!isParentChanged && newParentNode === undefined) return;
      let parentId = originalParentId;

      if (isParentChanged) {
        try {
          const request: TransferElementRequest = {
            parentId: newParentNode?.id ?? null,
            elements: allSelectedIds,
            swimlaneId: null,
          };

          const response = await api.transferElement(request, chainId);
          parentId =
            findUpdatedElement(response.updatedElements, draggedNode.id)
              ?.parentElementId ?? undefined;
        } catch (error) {
          notificationService.errorWithDetails(
            "Drag element failed",
            "Failed to drag element",
            error,
          );
          parentId = originalParentId;
        }
      }

      setNodes((prevNodes) =>
        prevNodes.map((prevNode) => {
          if (allSelectedIds.includes(prevNode.id)) {
            return {
              ...prevNode,
              parentId: parentId ?? undefined,
              data: {
                ...prevNode.data,
              },
            };
          }
          return prevNode;
        }),
      );
      structureChanged();
    },
    [
      chainId,
      getIntersectingNodes,
      isLibraryLoading,
      libraryElements,
      nodes,
      notificationService,
      setNodes,
      structureChanged,
    ],
  );

  const updateNodeData = useCallback(
    (element: Element, node: ChainGraphNode) => {
      setNodes((prevNodes) =>
        prevNodes.map((prevNode) => {
          if (prevNode.id === node.id) {
            const updatedNode: ChainGraphNode = {
              ...prevNode,
              data: getDataFromElement(element),
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
    isLoading: isLoading && isLibraryLoading,
  };
};
