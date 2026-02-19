import { Edge, Node, Position, useReactFlow } from "@xyflow/react";
import { useCallback, useEffect, useRef } from "react";
import ELK, { ElkNode, LayoutOptions } from "elkjs/lib/elk.bundled";
import { ElkDirection, useElkDirection } from "./useElkDirection.tsx";

const baseLayoutOptions: LayoutOptions = {
  "elk.algorithm": "layered",
  "elk.hierarchyHandling": "INCLUDE_CHILDREN",
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

type NodeBounds = { minX: number; maxX: number; minY: number; maxY: number };

function getBounds<NodeData extends Record<string, unknown>>(
  node: Node<NodeData>,
): NodeBounds {
  const x = node.position?.x ?? 0;
  const y = node.position?.y ?? 0;
  const w = node.width ?? 150;
  const h = node.height ?? 50;
  return { minX: x, maxX: x + w, minY: y, maxY: y + h };
}

function getComponentsBounds<NodeData extends Record<string, unknown>>(
  nodes: Node<NodeData>[],
): NodeBounds {
  if (nodes.length === 0) {
    return { minX: 0, minY: 0, maxX: 0, maxY: 0 };
  }

  let minX = Infinity;
  let minY = Infinity;
  let maxX = -Infinity;
  let maxY = -Infinity;

  for (const n of nodes) {
    const b = getBounds(n);
    minX = Math.min(minX, b.minX);
    minY = Math.min(minY, b.minY);
    maxX = Math.max(maxX, b.maxX);
    maxY = Math.max(maxY, b.maxY);
  }

  return {
    minX: Number.isFinite(minX) ? minX : 0,
    minY: Number.isFinite(minY) ? minY : 0,
    maxX: Number.isFinite(maxX) ? maxX : 0,
    maxY: Number.isFinite(maxY) ? maxY : 0,
  };
}

function overlaps1D(aMin: number, aMax: number, bMin: number, bMax: number) {
  return aMin < bMax && bMin < aMax;
}

function buildElkGraph<
  T extends ElkNode,
  NodeData extends Record<string, unknown> = never,
  EdgeData extends Record<string, unknown> = never,
>(
  nodes: Node<NodeData>[],
  edges: Edge<EdgeData>[],
  direction: ElkDirection,
): T {
  const layoutOptions = {
    ...baseLayoutOptions,
    "elk.alignment": direction === "RIGHT" ? "LEFT" : "TOP",
  };

  const visibleNodes = nodes.filter((node) => !node.hidden);
  const visibleIds = new Set(visibleNodes.map((node) => node.id));
  const visibleEdges = edges.filter(
    (edge) => visibleIds.has(edge.source) && visibleIds.has(edge.target),
  );

  const containerNodeIds = new Set(
    visibleNodes.filter((n) => n.type === "container").map((n) => n.id),
  );

  const nodeMap = new Map<string, ElkNode>();
  const parentMap = new Map<string, string | undefined>();

  for (const node of visibleNodes) {
    parentMap.set(node.id, node.parentId);
    const hasChildren = visibleNodes.some((n) => n.parentId === node.id);

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
        ...layoutOptions,
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
    layoutOptions: { ...layoutOptions, "elk.direction": direction },
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

  const parentGroups = new Map<string | undefined, Node<NodeData>[]>();
  for (const node of nodes) {
    if (node.hidden) continue;
    const parentId = node.parentId;
    if (onlyInsideContainers && !parentId) continue;
    if (!parentGroups.has(parentId)) parentGroups.set(parentId, []);
    parentGroups.get(parentId)!.push(node);
  }

  const gap: number = Number(
    baseLayoutOptions["elk.spacing.componentComponent"] ?? 20,
  );

  for (const [parentId, children] of parentGroups) {
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

    const compNodesList = comps
      .map((ids) => ids.map((id) => nodeMap.get(id)!).filter(Boolean))
      .filter((arr) => arr.length > 0);

    const compBounds = compNodesList.map((arr) => getComponentsBounds(arr));

    const globalMin = isHorizontal
      ? Math.min(...compBounds.map((b) => b.minX))
      : Math.min(...compBounds.map((b) => b.minY));

    const order = compBounds
      .map((b, idx) => ({ idx, key: isHorizontal ? b.minX : b.minY }))
      .sort((a, b) => a.key - b.key)
      .map((x) => x.idx);

    const placedBounds: NodeBounds[] = [];
    for (const idx of order) {
      const compNodes = compNodesList[idx];
      const box = getComponentsBounds(compNodes);

      const compMin = isHorizontal ? box.minX : box.minY;
      const desiredDelta = globalMin - compMin;

      let limitDelta = desiredDelta;

      for (const prev of placedBounds) {
        const yOverlap = overlaps1D(box.minY, box.maxY, prev.minY, prev.maxY);
        const xOverlap = overlaps1D(box.minX, box.maxX, prev.minX, prev.maxX);

        if (isHorizontal) {
          if (!yOverlap) continue;
          const minAllowed = prev.maxX + gap - box.minX;
          limitDelta = Math.max(limitDelta, minAllowed);
        } else {
          if (!xOverlap) continue;
          const minAllowed = prev.maxY + gap - box.minY;
          limitDelta = Math.max(limitDelta, minAllowed);
        }
      }

      if (limitDelta !== 0) {
        for (const n of compNodes) {
          if (isHorizontal) {
            n.position = { ...n.position, x: (n.position.x || 0) + limitDelta };
          } else {
            n.position = { ...n.position, y: (n.position.y || 0) + limitDelta };
          }
        }
      }

      const newBound = {
        minX: box.minX + (isHorizontal ? limitDelta : 0),
        maxX: box.maxX + (isHorizontal ? limitDelta : 0),
        minY: box.minY + (!isHorizontal ? limitDelta : 0),
        maxY: box.maxY + (!isHorizontal ? limitDelta : 0),
      };

      placedBounds.push(newBound);
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
  const edges = reactFlow
    .getEdges()
    .filter(
      (e) => !(e.data as { decorative?: boolean } | undefined)?.decorative,
    )
    .filter((e) => !String(e.id).startsWith("decorative:"));

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
