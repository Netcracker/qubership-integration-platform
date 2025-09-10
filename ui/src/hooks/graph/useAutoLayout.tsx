import { Edge, Node, Position, useReactFlow } from "@xyflow/react";
import { useCallback, useEffect, useRef } from "react";
import ELK, { ElkNode, LayoutOptions } from "elkjs/lib/elk.bundled";
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
  const visibleNodes = nodes.filter((node) => !node.hidden);
  const visibleIds = new Set(visibleNodes.map((node) => node.id));
  const visibleEdges = edges.filter(
    (edge) => visibleIds.has(edge.source) && visibleIds.has(edge.target),
  );

  const containerNodeIds = new Set(
    visibleNodes.filter((n) => n.type === "container").map((n) => n.id),
  );

  const baseLayoutOptions: LayoutOptions = {
    "elk.algorithm": "layered",
    "elk.hierarchyHandling": "INCLUDE_CHILDREN",
    "elk.alignment": direction === "RIGHT" ? "LEFT" : "TOP",
    "elk.layered.nodePlacement.strategy": "BRANDES_KOEPF",
    "elk.layered.nodePlacement.bk.fixedAlignment": "LEFTUP",
    "elk.layered.compaction.postCompaction.enabled": "true",
    "elk.layering.strategy": "LONGEST_PATH",
    "elk.layered.considerModelOrder": "NODES_AND_EDGES",
    "elk.partitioning.activate": "true",
    "elk.spacing.nodeNode": "40",
    "elk.spacing.nodeNodeBetweenLayers": "40",
    "elk.spacing.componentComponent": "20",
    "elk.padding": "[top=45,left=20,right=20,bottom=20]",
  };

  const nodeMap = new Map<string, ElkNode>();
  const parentMap = new Map<string, string | undefined>();

  for (const node of visibleNodes) {
    parentMap.set(node.id, node.parentId);
    const hasChildren = visibleNodes.some((node) => node.parentId === node.id);

    nodeMap.set(node.id, {
      id: node.id,
      ...(hasChildren
        ? { width: node.width ?? 150, height: node.height ?? 50 }
        : { width: 150, height: 50 }),
    });
  }

  for (const node of visibleNodes) {
    if (node.parentId && nodeMap.has(node.parentId)) {
      const parent = nodeMap.get(node.parentId)!;
      const child = nodeMap.get(node.id)!;
      (parent.children ??= []).push(child);
    }
  }

  for (const elkNode of nodeMap.values()) {
    if (elkNode.children?.length || containerNodeIds.has(elkNode.id)) {
      elkNode.layoutOptions = {
        ...baseLayoutOptions,
        "elk.direction": direction,
        "elk.alignment": direction === "RIGHT" ? "LEFT" : "TOP",
        "elk.layered.nodePlacement.bk.fixedAlignment": "LEFTUP",
      };
    }
  }

  const getNestedParents = (id: string): string[] => {
    const result: string[] = [];
    let current: string | undefined = id;
    const seen = new Set<string>();
    while (current && !seen.has(current)) {
      result.push(current);
      seen.add(current);
      current = parentMap.get(current);
    }
    return result;
  };

  const getClosestParent = (a: string, b: string): string | undefined => {
    const allParents = new Set(getNestedParents(a));
    let current: string | undefined = b;
    const seen = new Set<string>();
    while (current && !seen.has(current)) {
      if (allParents.has(current)) return current;
      seen.add(current);
      current = parentMap.get(current);
    }
    return undefined;
  };

  const topLevelNodes = visibleNodes
    .filter((n) => !n.parentId)
    .map((n) => nodeMap.get(n.id)!)
    .filter(Boolean);

  const root: ElkNode = {
    id: "root",
    layoutOptions: { ...baseLayoutOptions, "elk.direction": direction },
    children: topLevelNodes,
    edges: [],
  };

  const getEdgeLocation = (id?: string) => (id ? nodeMap.get(id) : root);

  for (const edge of visibleEdges) {
    const edgeLocationId = getClosestParent(edge.source, edge.target);
    const edgeLocation = getEdgeLocation(edgeLocationId);
    (edgeLocation!.edges ??= []).push({
      id: edge.id,
      sources: [edge.source],
      targets: [edge.target],
    });
  }

  return root as unknown as T;
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
    position: { x: elkNode.x || 0, y: elkNode.y || 0 },
    width: elkNode.width,
    height: elkNode.height,
    parentId: parentId ?? node.parentId,
    data: { ...node.data, direction },
    targetPosition,
    sourcePosition,
  };
}

function leftAlignDisconnectedSiblings<
  NodeData extends Record<string, unknown>,
  EdgeData extends Record<string, unknown>,
>(
  nodes: Node<NodeData>[],
  edges: Edge<EdgeData>[],
  direction: ElkDirection,
  onlyInsideContainers = true,
) {
  const isHorizontal = direction === "RIGHT";
  const nodeMap = new Map(nodes.map((node) => [node.id, node]));

  const parentMap = new Map<string | undefined, Node<NodeData>[]>();
  for (const node of nodes) {
    if (node.hidden) continue;
    const parentId = node.parentId;
    if (onlyInsideContainers && !parentId) continue;
    if (!parentMap.has(parentId)) parentMap.set(parentId, []);
    parentMap.get(parentId)!.push(node);
  }

  for (const [parentId, children] of parentMap) {
    if (!children || children.length < 2) continue;

    const childIds = new Set(children.map((child) => child.id));
    const adj = new Map<string, Set<string>>();
    for (const id of childIds) adj.set(id, new Set<string>());

    for (const edge of edges) {
      const source = nodeMap.get(edge.source);
      const target = nodeMap.get(edge.target);
      if (!source || !target) continue;
      if (source.parentId !== parentId || target.parentId !== parentId)
        continue;
      if (!childIds.has(source.id) || !childIds.has(target.id)) continue;
      adj.get(source.id)!.add(target.id);
      adj.get(target.id)!.add(source.id);
    }

    const comps: string[][] = [];
    const seen = new Set<string>();
    for (const id of childIds) {
      if (seen.has(id)) continue;
      const stack = [id];
      const comp: string[] = [];
      seen.add(id);
      while (stack.length) {
        const v = stack.pop()!;
        comp.push(v);
        for (const u of adj.get(v) ?? []) {
          if (!seen.has(u)) {
            seen.add(u);
            stack.push(u);
          }
        }
      }
      comps.push(comp);
    }

    if (comps.length <= 1) continue;

    const globalMin = Math.min(
      ...children.map(
        (child) => (isHorizontal ? child.position.x : child.position.y) || 0,
      ),
    );

    for (const comp of comps) {
      const compNodes = comp.map((id) => nodeMap.get(id)!).filter(Boolean);
      const compMin = Math.min(
        ...compNodes.map(
          (comp) => (isHorizontal ? comp.position.x : comp.position.y) || 0,
        ),
      );
      const delta = globalMin - compMin;
      if (delta === 0) continue;

      for (const node of compNodes) {
        if (isHorizontal) {
          node.position = {
            ...node.position,
            x: (node.position.x || 0) + delta,
          };
        } else {
          node.position = {
            ...node.position,
            y: (node.position.y || 0) + delta,
          };
        }
      }
    }
  }
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

  const updated: Node<NodeData>[] = [];
  const touched = new Set<string>();

  function traverse(elkNode: ElkNode, parentId?: string) {
    const orig = nodeMap.get(elkNode.id);
    if (orig) {
      const next = buildNode(elkNode, orig, direction, parentId);
      updated.push(next);
      touched.add(next.id);
    }
    elkNode.children?.forEach((child) => traverse(child, elkNode.id));
  }

  layout.children?.forEach((n) => traverse(n));

  const untouched = nodes.filter((n) => !touched.has(n.id));
  const laid = [...updated, ...untouched];

  leftAlignDisconnectedSiblings(laid, edges, direction, true);

  return laid;
}

function autoLayout(
  reactFlow: ReturnType<typeof useReactFlow>,
  direction: ElkDirection,
): void {
  const nodes = reactFlow.getNodes();
  const edges = reactFlow.getEdges();

  void arrangeNodes(nodes, edges, direction).then((newNodes) => {
    const nodeMap = new Map(newNodes.map((node) => [node.id, node]));
    for (const nn of newNodes) {
      reactFlow.updateNode(nn.id, () => nodeMap.get(nn.id)!);
    }
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
      function sortNodesTopologically(ns: Node<NodeData>[]): Node<NodeData>[] {
        const idToNode = new Map(ns.map((node) => [node.id, node]));
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

        ns.forEach(visit);
        return sorted;
      }

      const sortedNodes: Node<NodeData>[] = sortNodesTopologically(nodes);
      return arrangeNodes(sortedNodes, edges, direction);
    },
    [direction],
  );

  useEffect(() => {
    autoLayout(reactFlow, direction);
  }, [direction, reactFlow, elk]);

  return {
    arrangeNodes: arrangeNodesUsingDirection,
    direction,
    toggleDirection,
  };
};
