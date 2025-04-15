import {
  addEdge,
  applyEdgeChanges,
  applyNodeChanges,
  Edge,
  EdgeChange,
  Node,
  NodeChange,
  Position,
  useEdgesState,
  useNodesState,
  useReactFlow,
} from "@xyflow/react";
import { useCallback, useEffect } from "react";
import { api } from "../../api/api.ts";
import { Connection, Element } from "../../api/apiTypes.ts";
import { notification } from "antd";
import { useAutoLayout } from "./useAutoLayout.tsx";

export const useChainGraph = (chainId?: string) => {
  const { screenToFlowPosition } = useReactFlow();

  const [nodes, setNodes] = useNodesState<Node>([]);
  const [edges, setEdges] = useEdgesState<Edge>([]);

  const { autoLayout, elkDirectionControl } = useAutoLayout(
    nodes,
    edges,
    setNodes,
    setEdges,
  );

  useEffect(() => {
    const fetchData = async () => {
      try {
        if (!chainId) return;

        const elements = await api.getElements(chainId);

        const newNodes: Node[] = elements.map((element: Element) => ({
          id: element.id,
          type: element.type,
          position: { x: 0, y: 0 },
          data: { label: element.name },
        }));

        setNodes(newNodes);

        const connections = await api.getConnections(chainId);

        const newEdges: Edge[] = connections.map((connection: Connection) => ({
          id: connection.id, //TODO better to use addEdge!
          source: connection.from,
          target: connection.to,
        }));

        setEdges(newEdges);

        autoLayout(newNodes, newEdges);
      } catch (error) {
        notification.error({
          message: "Request failed",
          description: "Failed to load elements or connections",
        });
      }
    };
    fetchData();
  }, []);

  const onConnect = useCallback(
    async (params: any) => {
      if (!chainId) return;
      try {
        let response = await api.createConnection(
          {
            from: params.source,
            to: params.target,
          },
          chainId,
        );

        console.log(response.data.createdDependencies?.[0]?.id);
        params.id = response.data.createdDependencies?.[0]?.id;

        console.log(params);
        setEdges((eds) => addEdge(params, eds));
      } catch (error) {
        notification.error({
          message: "Request failed",
          description: "Failed to create connection",
        });
      }
    },
    [setEdges],
  );

  const onDrop = useCallback(
    async (event: React.DragEvent) => {
      event.preventDefault();
      const isHorizontal = elkDirectionControl.elkDirection === "RIGHT";
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

        const createdElement = response.data.createdElements[0];

        const newNode: Node = {
          id: createdElement.id,
          type: createdElement.type,
          position,
          data: {
            label: createdElement.name,
            direction: elkDirectionControl.elkDirection,
          },
          targetPosition: isHorizontal ? Position.Left : Position.Top,
          sourcePosition: isHorizontal ? Position.Right : Position.Bottom,
        };

        setNodes((nodes) => nodes.concat(newNode));
      } catch (error) {
        notification.error({
          message: "Request failed",
          description: "Failed to create element",
        });
      }
    },
    [screenToFlowPosition],
  );

  const onEdgesChange = useCallback((changes: EdgeChange[]) => {
    changes.forEach(async (change) => {
      if (!chainId) return;
      if (change.type === "remove") {
        await api.deleteConnection(change.id, chainId);
      }
      setEdges((eds) => applyEdgeChanges(changes, eds));
    });
  }, []);

  const onNodesChange = useCallback((changes: NodeChange[]) => {
    changes.forEach(async (change) => {
      if (!chainId) return;
      if (change.type === "remove") {
        await api.deleteElement(change.id, chainId);
      }
      setNodes((nds) => applyNodeChanges(changes, nds));
    });
  }, []);

  return {
    nodes,
    edges,
    onConnect,
    onDrop,
    onEdgesChange,
    onNodesChange,
    elkDirectionControl,
  };
};
