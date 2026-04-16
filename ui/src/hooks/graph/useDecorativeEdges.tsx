import { Edge, useEdgesState } from "@xyflow/react";
import { useEffect, useRef } from "react";
import { ChainGraphNode } from "../../components/graph/nodes/ChainGraphNodeTypes.ts";

export const DECORATIVE_PREFIX = "decorative:";

export function isDecorativeEdgeId(id: string) {
  return id.startsWith(DECORATIVE_PREFIX);
}

export function originalEdgeIdFromDecorative(id: string) {
  return isDecorativeEdgeId(id) ? id.slice(DECORATIVE_PREFIX.length) : id;
}

export type DecorativeEdgeData = {
  decorative: true;
  originalEdgeId: string;
  originalSource: string;
  originalTarget: string;
};

function buildDecorativeEdges(
  nodes: ChainGraphNode[],
  edges: Edge[],
): Edge[] {
  const nodeMap = new Map(nodes.map((n) => [n.id, n]));

  const representative = (nodeId: string): string | undefined => {
    const n = nodeMap.get(nodeId);
    if (!n) return undefined;
    if (!n.hidden) return n.id;

    let cur = n.parentId;
    const seen = new Set<string>();
    while (cur && !seen.has(cur)) {
      seen.add(cur);
      const parent = nodeMap.get(cur);
      if (!parent) return undefined;
      if (!parent.hidden) return parent.id;
      cur = parent.parentId;
    }
    return undefined;
  };

  const out: Edge[] = [];

  for (const e of edges) {
    const s = nodeMap.get(e.source);
    const t = nodeMap.get(e.target);
    if (!s || !t) continue;
    if (!s.hidden && !t.hidden) continue;

    const repS = representative(e.source);
    const repT = representative(e.target);
    if (!repS || !repT) continue;
    if (repS === repT) continue;

    const data: DecorativeEdgeData = {
      decorative: true,
      originalEdgeId: e.id,
      originalSource: e.source,
      originalTarget: e.target,
    };

    out.push({
      id: `${DECORATIVE_PREFIX}${e.id}`,
      source: repS,
      target: repT,
      data,
    });
  }

  return out;
}

export const useDecorativeEdges = (
  nodes: ChainGraphNode[],
  edges: Edge[],
) => {
  const [decorativeEdges, setDecorativeEdges] = useEdgesState<Edge>([]);
  const decorativeEdgesRef = useRef<Edge[]>(decorativeEdges);

  useEffect(() => {
    decorativeEdgesRef.current = decorativeEdges;
  }, [decorativeEdges]);

  useEffect(() => {
    if (!nodes.some((n) => n.hidden)) {
      if (decorativeEdgesRef.current.length > 0) setDecorativeEdges([]);
      return;
    }

    const prevSelected = new Map(
      decorativeEdgesRef.current.map((e) => [e.id, !!e.selected]),
    );

    const next = buildDecorativeEdges(nodes, edges).map((e) => ({
      ...e,
      selected: prevSelected.get(e.id) ?? false,
    }));

    if (next.length === 0 && decorativeEdgesRef.current.length === 0) return;
    setDecorativeEdges(next);
  }, [nodes, edges, setDecorativeEdges]);

  return { decorativeEdges, setDecorativeEdges };
};
