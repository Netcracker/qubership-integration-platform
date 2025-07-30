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
  XYPosition,
} from "@xyflow/react";
import React, { useCallback, useEffect, useRef, useState } from "react";
import { api } from "../../api/api.ts";
import {
  Connection,
  CreateElementRequest,
  Element,
} from "../../api/apiTypes.ts";
import { useAutoLayout } from "./useAutoLayout.tsx";
import { useNotificationService } from "../useNotificationService.tsx";
import { useLibraryContext } from "../../components/LibraryContext.tsx";
import {
  collectChildren,
  getDataFromElement,
  getElementDescriptor,
  getNodeFromElement,
} from "../../misc/chain-graph-utils.ts";
import {
  ChainGraphNode,
  ChainGraphNodeData,
  OnDeleteEvent,
} from "../../components/graph/nodes/ChainGraphNodeTypes.ts";

export const useChainGraph = (chainId?: string) => {
  const { screenToFlowPosition } = useReactFlow();

  const { elementDescriptors, isLibraryLoading } = useLibraryContext();
  const [nodes, setNodes] = useNodesState<Node<ChainGraphNodeData>>([]);
  const [edges, setEdges] = useEdgesState<Edge>([]);
  const [isLoading, setIsLoading] = useState(true);
  const isInitialized = useRef<boolean>(false);
  const notificationService = useNotificationService();
  const { arrangeNodes, direction, toggleDirection } = useAutoLayout();
  const { getIntersectingNodes } = useReactFlow();

  const structureChangedRef = useRef<boolean>(false);

  const structureChanged = useCallback(() => {
    structureChangedRef.current = true;
  }, []);

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

        const elements = await api.getElements(chainId);

        const newNodes: ChainGraphNode[] = elements
          .map((element: Element) => {
            return getNodeFromElement(
              element,
              getElementDescriptor(element, elementDescriptors),
              {
                x: 0,
                y: 0,
              },
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
    direction,
    arrangeNodes,
    chainId,
    isLibraryLoading,
    elementDescriptors,
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
      } catch (error) {
        notificationService.requestFailed("Failed to create connection", error);
      }
    },
    [chainId, notificationService, setEdges],
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

      const ghostNode = {
        id: "fake",
        width: 1,
        height: 1,
        position: dropPosition,
      };

      const intersecting = getIntersectingNodes(ghostNode).filter(
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
            getElementDescriptor(createdElement, elementDescriptors),
            dropPosition,
            direction,
          );
          if (!newNode) {
            return;
          }
          const childNodes: ChainGraphNode[] = createdElement?.children
            ? createdElement?.children?.map((child: Element, index: number) => {
                return getNodeFromElement(
                  child,
                  getElementDescriptor(child, elementDescriptors),
                  {
                    x: dropPosition.x + 30 * (index + 1),
                    y: dropPosition.y + 30 * (index + 1),
                  },
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
      nodes,
      elementDescriptors,
      chainId,
      direction,
      notificationService,
      getIntersectingNodes,
      screenToFlowPosition,
      setNodes,
      structureChanged,
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
          structureChanged();
        }),
      );
    },
    [chainId, setEdges, structureChanged],
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
          const edgesDeleteResponse = await api.deleteConnection(
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

  const onNodeDragStop = useCallback(
    (_: React.MouseEvent, draggedNode: ChainGraphNode) => {
      setNodes((prevNodes) => {
        const originalNode = prevNodes.find((n) => n.id === draggedNode.id);
        if (!originalNode) return prevNodes;

        const delta: XYPosition = {
          x: draggedNode.position.x - originalNode.position.x,
          y: draggedNode.position.y - originalNode.position.y,
        };

        const children = collectChildren(draggedNode.id, prevNodes);

        return prevNodes.map((node) => {
          if (node.id === draggedNode.id) {
            return { ...node, position: draggedNode.position };
          }

          const isChild = children.some((desc) => desc.id === node.id);
          if (isChild) {
            return {
              ...node,
              position: {
                x: node.position.x + delta.x,
                y: node.position.y + delta.y,
              },
            };
          }

          return node;
        });
      });
    },
    [setNodes],
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
    onDrop,
    onDelete,
    onEdgesChange,
    onNodesChange,
    onNodeDragStop,
    direction,
    toggleDirection,
    updateNodeData,
    isLoading: isLoading && isLibraryLoading,
  };
};
