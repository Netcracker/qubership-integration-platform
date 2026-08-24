import { Edge, XYPosition } from "@xyflow/react";
import { ElkDirection } from "../hooks/graph/useElkDirection.tsx";
import { ChainGraphNode } from "../components/graph/nodes/ChainGraphNodeTypes.ts";

export const SWIMLANE_GAP = 40;
export const SWIMLANE_PADDING = 20;
export const DEFAULT_NODE_WIDTH = 150;
export const DEFAULT_NODE_HEIGHT = 50;
const MAX_CROSS_SWIMLANE_EDGES = 10;

export function isHorizontalDirection(direction?: ElkDirection): boolean {
  return direction === "RIGHT";
}

export function isDefaultSwimlane(
  node: ChainGraphNode,
  defaultSwimlaneId: string,
): boolean {
  return node.type === "swimlane" && node.id === defaultSwimlaneId;
}

function getSwimlanes(nodes: ChainGraphNode[]): ChainGraphNode[] {
  return nodes.filter((node) => node.type === "swimlane");
}

function getSwimlaneIds(nodes: ChainGraphNode[]): Set<string> {
  return new Set(getSwimlanes(nodes).map((n) => n.id));
}

function getPos(node: ChainGraphNode, direction?: ElkDirection): number {
  return isHorizontalDirection(direction)
    ? (node.position?.x ?? 0)
    : (node.position?.y ?? 0);
}

function getSize(node: ChainGraphNode, direction?: ElkDirection): number {
  return isHorizontalDirection(direction)
    ? (node.width ?? DEFAULT_NODE_WIDTH)
    : (node.height ?? DEFAULT_NODE_HEIGHT);
}

function applyShift(
  node: ChainGraphNode,
  direction: ElkDirection,
  shift: number,
): ChainGraphNode {
  if (shift === 0) return node;
  return {
    ...node,
    position: {
      x: isHorizontalDirection(direction)
        ? (node.position?.x ?? 0) + shift
        : (node.position?.x ?? 0),
      y: isHorizontalDirection(direction)
        ? (node.position?.y ?? 0)
        : (node.position?.y ?? 0) + shift,
    },
  };
}

function equalizeSwimlaneSizes(
  nodes: ChainGraphNode[],
  direction?: ElkDirection,
): ChainGraphNode[] {
  const horizontal = isHorizontalDirection(direction);
  const swimlanes = getSwimlanes(nodes);
  if (swimlanes.length < 2) return nodes;

  const maxSize = Math.max(
    ...swimlanes.map((s) =>
      horizontal
        ? (s.width ?? DEFAULT_NODE_WIDTH)
        : (s.height ?? DEFAULT_NODE_HEIGHT),
    ),
  );

  return nodes.map((node) => {
    if (node.type !== "swimlane") return node;
    return horizontal
      ? { ...node, width: maxSize }
      : { ...node, height: maxSize };
  });
}

export function ensureDefaultSwimlaneAtTop(
  nodes: ChainGraphNode[],
  direction: ElkDirection,
  defaultSwimlaneId: string,
): ChainGraphNode[] {
  const swimlanes = getSwimlanes(nodes);
  const defaultSwimlane = swimlanes.find((node) =>
    isDefaultSwimlane(node, defaultSwimlaneId),
  );

  if (!defaultSwimlane) return nodes;

  const otherSwimlanes = swimlanes.filter((s) => s.id !== defaultSwimlane.id);
  const horizontal = isHorizontalDirection(direction);

  const positions = new Map<string, XYPosition>();
  positions.set(defaultSwimlane.id, { x: 0, y: 0 });

  let cumulative = horizontal
    ? (defaultSwimlane.height ?? DEFAULT_NODE_HEIGHT) + SWIMLANE_GAP
    : (defaultSwimlane.width ?? DEFAULT_NODE_WIDTH) + SWIMLANE_GAP;

  for (const swimlane of otherSwimlanes) {
    const pos = horizontal ? { x: 0, y: cumulative } : { x: cumulative, y: 0 };
    positions.set(swimlane.id, pos);
    cumulative += horizontal
      ? (swimlane.height ?? DEFAULT_NODE_HEIGHT) + SWIMLANE_GAP
      : (swimlane.width ?? DEFAULT_NODE_WIDTH) + SWIMLANE_GAP;
  }

  return nodes.map((node) => {
    const pos = positions.get(node.id);
    if (!pos) return node;
    return { ...node, position: pos };
  });
}

function getCrossSwimlaneEdges(
  edges: Edge[],
  nodeMap: Map<string, ChainGraphNode>,
  swimlaneIds: Set<string>,
): Edge[] {
  return edges.filter((edge) => {
    if (edge.hidden) return false;
    const source = nodeMap.get(edge.source);
    const target = nodeMap.get(edge.target);
    if (!source || !target) return false;
    if (!source.parentId || !target.parentId) return false;
    if (source.parentId === target.parentId) return false;
    return swimlaneIds.has(source.parentId) && swimlaneIds.has(target.parentId);
  });
}

function computeShiftForEdge(
  source: ChainGraphNode,
  target: ChainGraphNode,
  direction: ElkDirection,
): number | undefined {
  const sourceEdge = getPos(source, direction) + getSize(source, direction);
  const targetPos = getPos(target, direction);
  const required = sourceEdge + SWIMLANE_GAP;

  if (targetPos >= required) return undefined;
  return required - targetPos;
}

function computeRequiredShifts(
  nodes: ChainGraphNode[],
  edges: Edge[],
  direction: ElkDirection,
  swimlaneIds: Set<string>,
): Map<string, number> {
  const nodeMap = new Map(nodes.map((n) => [n.id, n]));
  const shifts = new Map<string, number>();
  const relevantEdges = getCrossSwimlaneEdges(edges, nodeMap, swimlaneIds);

  for (const edge of relevantEdges) {
    const source = nodeMap.get(edge.source)!;
    const target = nodeMap.get(edge.target)!;
    const shift = computeShiftForEdge(source, target, direction);
    if (shift === undefined) continue;

    const current = shifts.get(edge.target) ?? 0;
    if (shift > current) shifts.set(edge.target, shift);
  }

  return shifts;
}

function applyShiftsWithGaps(
  nodes: ChainGraphNode[],
  nodeShifts: Map<string, number>,
  direction: ElkDirection,
  swimlaneIds: Set<string>,
): ChainGraphNode[] {
  const updates = new Map<string, number>();

  for (const swimlaneId of swimlaneIds) {
    const children = nodes
      .filter((n) => n.parentId === swimlaneId)
      .sort((a, b) => getPos(a, direction) - getPos(b, direction));

    if (children.length === 0) continue;

    let prevEdge = -Infinity;

    for (const child of children) {
      const originalPos = getPos(child, direction);
      const individualShift = nodeShifts.get(child.id) ?? 0;
      const childSize = getSize(child, direction);

      let newPos = originalPos + individualShift;
      const minPos = prevEdge + SWIMLANE_GAP;

      if (newPos < minPos) {
        newPos = minPos;
      }

      if (newPos !== originalPos) {
        updates.set(child.id, newPos - originalPos);
      }

      prevEdge = newPos + childSize;
    }
  }

  if (updates.size === 0) return nodes;

  return nodes.map((node) => {
    const shift = updates.get(node.id);
    return shift !== undefined ? applyShift(node, direction, shift) : node;
  });
}

function resolveCrossSwimlaneEdges(
  nodes: ChainGraphNode[],
  edges: Edge[],
  direction: ElkDirection,
): ChainGraphNode[] {
  const swimlaneIds = getSwimlaneIds(nodes);
  if (swimlaneIds.size < 2) return nodes;

  let result = nodes;

  for (let iter = 0; iter < MAX_CROSS_SWIMLANE_EDGES; iter++) {
    const nodeShifts = computeRequiredShifts(
      result,
      edges,
      direction,
      swimlaneIds,
    );
    if (nodeShifts.size === 0) break;
    result = applyShiftsWithGaps(result, nodeShifts, direction, swimlaneIds);
  }

  return result;
}

function expandSwimlanesToFitChildren(
  nodes: ChainGraphNode[],
  direction?: ElkDirection,
): ChainGraphNode[] {
  const horizontal = isHorizontalDirection(direction);
  const swimlaneIds = getSwimlaneIds(nodes);
  if (swimlaneIds.size === 0) return nodes;

  const nodeMap = new Map(nodes.map((n) => [n.id, n]));
  const newSizes = new Map<string, number>();

  for (const swimlaneId of swimlaneIds) {
    const swimlane = nodeMap.get(swimlaneId);
    if (!swimlane) continue;

    const children = nodes.filter(
      (n) => n.parentId === swimlaneId && !n.hidden,
    );
    if (children.length === 0) continue;

    const maxExtent = Math.max(
      ...children.map((n) => getPos(n, direction) + getSize(n, direction)),
    );
    const required = maxExtent + SWIMLANE_PADDING;
    const current = horizontal
      ? (swimlane.width ?? DEFAULT_NODE_WIDTH)
      : (swimlane.height ?? DEFAULT_NODE_HEIGHT);

    if (required > current) {
      newSizes.set(swimlaneId, required);
    }
  }

  if (newSizes.size === 0) return nodes;

  return nodes.map((node) => {
    const s = newSizes.get(node.id);
    if (s === undefined) return node;
    return horizontal ? { ...node, width: s } : { ...node, height: s };
  });
}

export function arrangeSwimlaneChildren(
  nodes: ChainGraphNode[],
  edges: Edge[],
  direction: ElkDirection,
  defaultSwimlaneId: string | undefined,
): ChainGraphNode[] {
  if (!defaultSwimlaneId) {
    return nodes;
  }
  const resolved = resolveCrossSwimlaneEdges(nodes, edges, direction);
  const fitted = expandSwimlanesToFitChildren(resolved, direction);
  const equalized = equalizeSwimlaneSizes(fitted, direction);
  return ensureDefaultSwimlaneAtTop(equalized, direction, defaultSwimlaneId);
}
