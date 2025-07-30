import { Edge, Node, Position, useReactFlow } from "@xyflow/react";
import { useCallback, useEffect, useRef } from "react";
import ELK, {
  ELK as IELK,
  ElkNode,
  LayoutOptions,
} from "elkjs/lib/elk.bundled";
import { ElkDirection, useElkDirection } from "./useElkDirection.tsx";

const layoutOptions: LayoutOptions = {
  "elk.algorithm": "layered",
  "elk.direction": "DOWN",
  "elk.layering.strategy": "LONGEST_PATH",
  "elk.spacing.nodeNode": "40",
  "elk.spacing.nodeNodeBetweenLayers": "40",
  "elk.padding": "[top=45,left=20,right=20,bottom=20]",
};

function buildElkGraph<
  T extends ElkNode,
  NodeData extends Record<string, unknown> = never,
  EdgeData extends Record<string, unknown> = never,
>(
  nodes: Node<NodeData>[],
  edges: Edge<EdgeData>[],
  direction: ElkDirection,
): T {
  const nodeMap = new Map<string, ElkNode>();

  for (const node of nodes) {
    const hasChildren = nodes.some((n) => n.parentId === node.id);
    nodeMap.set(node.id, {
      id: node.id,
      ...(hasChildren
        ? {
            width: node.width ?? 150,
            height: node.height ?? 50,
          }
        : { width: 150, height: 50 }),
    });
  }

  for (const node of nodes) {
    if (node.parentId) {
      const parent = nodeMap.get(node.parentId);
      const self = nodeMap.get(node.id);
      if (parent && self) {
        parent.children = parent.children || [];
        parent.children.push(self);
      }
    }
  }

  for (const elkNode of nodeMap.values()) {
    if (elkNode.children?.length) {
      const childrenIds = new Set(elkNode.children.map((c) => c.id));

      const hasInnerEdges = edges.some(
        (edge) => childrenIds.has(edge.source) && childrenIds.has(edge.target),
      );

      elkNode.layoutOptions = {
        ...layoutOptions,
        "elk.layering.strategy": hasInnerEdges
          ? "LONGEST_PATH"
          : "NETWORK_SIMPLEX",
        "elk.direction": direction,
      };
    }
  }

  const topLevelNodes = nodes
    .filter((n) => !n.parentId)
    .map((n) => nodeMap.get(n.id)!)
    .filter(Boolean);

  return {
    id: "root",
    layoutOptions: {
      ...layoutOptions,
      "elk.direction": direction,
    },
    children: topLevelNodes,
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
  parentId?: string,
): Node<NodeData> {
  const isHorizontal = direction === "RIGHT";
  const targetPosition = isHorizontal ? Position.Left : Position.Top;
  const sourcePosition = isHorizontal ? Position.Right : Position.Bottom;

  return {
    ...node,
    position: {
      x: elkNode.x || 0,
      y: elkNode.y || 0,
    },
    width: elkNode.width,
    height: elkNode.height,
    parentId,
    extent: parentId ? "parent" : undefined,
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

  const result: Node<NodeData>[] = [];

  function traverse(elkNode: ElkNode, parentId?: string) {
    const node = nodeMap.get(elkNode.id);
    if (node) {
      result.push(buildNode(elkNode, node, direction, parentId));
    }

    elkNode.children?.forEach((child) => {
      traverse(child, elkNode.id);
    });
  }

  layout.children?.forEach((n) => traverse(n));

  return result;
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
    layout.children?.forEach((node) => {
      reactFlow.updateNode(node.id, (n) => buildNode(node, n, direction));
    });
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
      function sortNodesTopologically(
        nodes: Node<NodeData>[],
      ): Node<NodeData>[] {
        const idToNode = new Map(nodes.map((node) => [node.id, node]));
        const visited = new Set<string>();
        const sorted: Node<NodeData>[] = [];

        function visit(node: Node<NodeData>) {
          if (visited.has(node.id)) return;
          if (node.parentId) {
            const parent = idToNode.get(node.parentId);
            if (parent) visit(parent);
          }
          visited.add(node.id);
          sorted.push(node);
        }

        nodes.forEach(visit);
        return sorted;
      }

      const sortedNodes: Node<NodeData>[] = sortNodesTopologically(nodes);
      return arrangeNodes(sortedNodes, edges, direction);
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
