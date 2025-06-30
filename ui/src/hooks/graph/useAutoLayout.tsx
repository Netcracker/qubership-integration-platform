import { Edge, Node, Position, useReactFlow } from "@xyflow/react";
import { useCallback, useEffect, useRef } from "react";
import ELK, { ELK as IELK, ElkNode } from "elkjs/lib/elk.bundled";
import { ElkDirection, useElkDirection } from "./useElkDirection.tsx";

function buildElkGraph<
  T extends ElkNode,
  NodeData extends Record<string, unknown> = never,
  EdgeData extends Record<string, unknown> = never,
>(
  nodes: Node<NodeData>[],
  edges: Edge<EdgeData>[],
  direction: ElkDirection,
): T {
  return {
    id: "root",
    layoutOptions: {
      "elk.algorithm": "layered",
      "elk.direction": direction,
    },
    children: nodes.map((node) => ({
      id: node.id,
      width: 150,
      height: 50,
    })),
    edges: edges.map((edge) => ({
      id: edge.id,
      sources: [edge.source],
      targets: [edge.target],
    })),
  } as unknown as T;
}

function buildNode<NodeData extends Record<string, unknown> = never>(
  elkNode: ElkNode,
  node: Node<NodeData>,
  direction: ElkDirection,
): Node<NodeData> {
  const isHorizontal = direction === "RIGHT";
  const targetPosition = isHorizontal ? Position.Left : Position.Top;
  const sourcePosition = isHorizontal ? Position.Right : Position.Bottom;
  return {
    ...node,
    position: {
      x: elkNode?.x || 0,
      y: elkNode?.y || 0,
    },
    data: {
      ...node.data,
      direction,
    },
    targetPosition,
    sourcePosition,
  };
}

export async function arrangeNodes<
  NodeData extends Record<string, unknown> = never,
  EdgeData extends Record<string, unknown> = never,
>(
  nodes: Node<NodeData>[],
  edges: Edge<EdgeData>[],
  direction: ElkDirection,
): Promise<Node<NodeData>[]> {
  const elk = new ELK();
  const elkGraph = buildElkGraph(nodes, edges, direction);
  const nodeMap = new Map<string, Node<NodeData>>(nodes.map((n) => [n.id, n]));
  const layout = await elk.layout(elkGraph);
  return (
    layout.children?.map((node) =>
      buildNode(node, nodeMap.get(node.id)!, direction),
    ) ?? []
  );
}

function autoLayout(
  elk: IELK,
  reactFlow: ReturnType<typeof useReactFlow>,
  direction: ElkDirection,
): void {
  const elkGraph = buildElkGraph(
    reactFlow.getNodes(),
    reactFlow.getEdges(),
    direction,
  );
  void elk.layout(elkGraph).then((layout) => {
    layout.children?.forEach((node) =>
      reactFlow.updateNode(node.id, (n) => buildNode(node, n, direction)),
    );
    requestAnimationFrame(() => void reactFlow.fitView());
  });
}

export const useAutoLayout = () => {
  const reactFlow = useReactFlow();
  const { direction, toggleDirection } = useElkDirection();

  const elk = useRef(new ELK()).current;

  const arrangeNodesUsingDirection = useCallback(
    async <
      NodeData extends Record<string, unknown> = never,
      EdgeData extends Record<string, unknown> = never,
    >(
      nodes: Node<NodeData>[],
      edges: Edge<EdgeData>[],
    ): Promise<Node<NodeData>[]> => {
      return arrangeNodes(nodes, edges, direction);
    },
    [direction],
  );

  useEffect(() => {
    autoLayout(elk, reactFlow, direction);
  }, [direction, elk, reactFlow]);

  return {
    arrangeNodes: arrangeNodesUsingDirection,
    direction,
    toggleDirection,
  };
};
