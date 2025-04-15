import { Edge, Node, Position, useReactFlow } from "@xyflow/react";
import { useEffect, useRef } from "react";
import ELK from "elkjs/lib/elk.bundled";
import { useElkDirection } from "./useElkDirection.tsx";

type SetNodesFunction = React.Dispatch<React.SetStateAction<Node[]>>;
type SetEdgesFunction = React.Dispatch<React.SetStateAction<Edge[]>>;

export const useAutoLayout = (
  nodes: Node[],
  edges: Edge[],
  setNodes: SetNodesFunction,
  setEdges: SetEdgesFunction,
) => {
  const { fitView } = useReactFlow();
  const elkDirectionControl = useElkDirection();

  const elk = useRef(new ELK()).current;

  useEffect(() => {
    autoLayout(nodes, edges);
  }, [elkDirectionControl.elkDirection]);

  const autoLayout = (nodes: Node[], edges: Edge[]) => {
    const isHorizontal = elkDirectionControl.elkDirection === "RIGHT";
    const elkGraph = {
      id: "root",
      layoutOptions: {
        "elk.algorithm": "layered",
        "elk.direction": elkDirectionControl.elkDirection,
      },
      children: nodes.map((node) => ({
        id: node.id,
        width: 150,
        height: 50,
        type: node.type,
      })),
      edges: edges.map((edge) => ({
        id: edge.id,
        type: edge.type,
        sources: [edge.source],
        targets: [edge.target],
      })),
    };

    elk.layout(elkGraph).then((layout) => {
      const layoutedNodes = nodes.map((node) => {
        const layoutNode = layout.children?.find((n) => n.id === node.id);
        return {
          ...node,
          position: {
            x: layoutNode?.x || 0,
            y: layoutNode?.y || 0,
          },
          data: {
            ...node.data,
            direction: elkDirectionControl.elkDirection,
          },
          targetPosition: isHorizontal ? Position.Left : Position.Top,
          sourcePosition: isHorizontal ? Position.Right : Position.Bottom,
        };
      });

      const layoutedEdges = edges.map((edge) => ({
        ...edge,
      }));

      setNodes(layoutedNodes);
      setEdges(layoutedEdges);

      requestAnimationFrame(() => fitView());
    });
  };

  return { autoLayout, elkDirectionControl };
};
