import {
  addEdge,
  applyEdgeChanges,
  applyNodeChanges,
  Edge,
  EdgeChange,
  Node,
  NodeChange,
  Position,
  Connection as ReactFlowConnection,
  useEdgesState,
  useNodesState,
  useReactFlow,
} from "@xyflow/react";
import React, { useCallback, useEffect, useState } from "react";
import { api } from "../../api/api.ts";
import { Connection, Element } from "../../api/apiTypes.ts";
import { useAutoLayout } from "./useAutoLayout.tsx";
import { useNotificationService } from "../useNotificationService.tsx";
import { ElkDirection } from "./useElkDirection.tsx";

export type ChainGraphNodeData = {
  label: string;
  description: string;
  properties: Element["properties"];
  direction?: ElkDirection;
};

function getDataFromElement(element: Element): ChainGraphNodeData {
  return {
    label: element.name,
    description: element.description,
    properties: element.properties,
  };
}

export const useChainGraph = (chainId?: string) => {
  const { screenToFlowPosition } = useReactFlow();

  const [nodes, setNodes] = useNodesState<Node<ChainGraphNodeData>>([]);
  const [edges, setEdges] = useEdgesState<Edge>([]);
  const [isLoading, setIsLoading] = useState(true);
  const notificationService = useNotificationService();
  const { arrangeNodes, direction, toggleDirection } = useAutoLayout();

  useEffect(() => {
    const fetchData = async () => {
      setIsLoading(true);
      try {
        if (!chainId) return;

        const elements = await api.getElements(chainId);

        const newNodes: Node<ChainGraphNodeData>[] = elements.map(
          (element: Element) => ({
            id: element.id,
            type: element.type,
            position: { x: 0, y: 0 },
            data: {
              ...getDataFromElement(element),
            },
          }),
        );

        const connections = await api.getConnections(chainId);

        const newEdges: Edge[] = connections.map((connection: Connection) => ({
          id: connection.id, //TODO better to use addEdge!
          source: connection.from,
          target: connection.to,
        }));

        setNodes(await arrangeNodes(newNodes, newEdges));
        setEdges(newEdges);
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
  }, [arrangeNodes, chainId, notificationService, setEdges, setNodes]);

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

        console.log(response.createdDependencies?.[0]?.id);
        const edge: Edge = {
          ...connection,
          id: response.createdDependencies?.[0]?.id ?? "",
        };
        console.log(edge);
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
      const isHorizontal = direction === "RIGHT";
      if (!chainId) return;
      const name = event.dataTransfer.getData("application/reactflow");

      if (!name) return;

      const position = screenToFlowPosition({
        x: event.clientX,
        y: event.clientY,
      });

      try {
        const response = await api.createElement(
          {
            type: name,
          },
          chainId,
        );

        const createdElement = response.createdElements?.[0];
        if (createdElement) {
          const newNode: Node<ChainGraphNodeData> = {
            id: createdElement.id,
            type: createdElement.type,
            position,
            data: {
              ...getDataFromElement(createdElement),
              direction,
            },
            targetPosition: isHorizontal ? Position.Left : Position.Top,
            sourcePosition: isHorizontal ? Position.Right : Position.Bottom,
          };
          setNodes((nodes) => nodes.concat(newNode));
        }
      } catch (error) {
        notificationService.requestFailed("Failed to create element", error);
      }
    },
    [chainId, direction, notificationService, screenToFlowPosition, setNodes],
  );

  const onEdgesChange = useCallback(
    async (changes: EdgeChange[]) => {
      await Promise.all(
        changes.map(async (change) => {
          if (!chainId) return;
          if (change.type === "remove") {
            await api.deleteConnection(change.id, chainId);
          }
          setEdges((eds) => applyEdgeChanges(changes, eds));
        }),
      );
    },
    [chainId, setEdges],
  );

  const onNodesChange = useCallback(
    async (changes: NodeChange<Node<ChainGraphNodeData>>[]) => {
      await Promise.all(
        changes.map(async (change) => {
          if (!chainId) return;
          if (change.type === "remove") {
            await api.deleteElement(change.id, chainId);
          }
          setNodes((nds) => applyNodeChanges(changes, nds));
        }),
      );
    },
    [chainId, setNodes],
  );

  const updateNodeData = useCallback(
    (element: Element, node: Node<ChainGraphNodeData>) => {
      node.data = {
        ...node.data,
        ...getDataFromElement(element),
      };
      setNodes((nds) =>
        nds.map((nd) => {
          if (nd.id === node.id) {
            return {
              ...node,
              data: {
                ...node.data,
              },
            };
          }
          return nd;
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
    onEdgesChange,
    onNodesChange,
    direction,
    toggleDirection,
    updateNodeData,
    isLoading,
  };
};
