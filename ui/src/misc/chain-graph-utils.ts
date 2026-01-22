import { Element, ElementColorType, LibraryElement } from "../api/apiTypes.ts";
import { Edge, Node, Position, XYPosition } from "@xyflow/react";
import { ElkDirection } from "../hooks/graph/useElkDirection.tsx";
import {
  ChainGraphNode,
  ChainGraphNodeData,
} from "../components/graph/nodes/ChainGraphNodeTypes.ts";

export function getDataFromElement(
  element: Element,
  libraryElement?: LibraryElement,
): ChainGraphNodeData {
  let node = {
    elementType: element.type,
    label: element.name,
    description: element.description,
    properties: element.properties,
  } as ChainGraphNodeData;

  if (libraryElement) {
    node = {
      ...node,
      inputEnabled: libraryElement?.inputEnabled,
      outputEnabled: libraryElement?.outputEnabled,
      typeTitle: libraryElement?.title,
    };
  }
  return node;
}

export function getLibraryElement(
  element: Element,
  libraryElements: LibraryElement[] | null,
): LibraryElement {
  const libraryElement = libraryElements?.find(
    (descriptor) => descriptor.name === element.type,
  );
  return (
    libraryElement ??
    ({
      name: "default",
      title: "Default",
      description: "Element by Default",
    } as LibraryElement)
  );
}

export function getNodeFromElement(
  element: Element,
  libraryElement?: LibraryElement,
  direction?: ElkDirection,
  position?: XYPosition,
): ChainGraphNode {
  const defaultPosition: XYPosition = { x: 0, y: 0 };
  const nodeType =
    libraryElement?.container || element.type === "container"
      ? "container"
      : "unit";
  const isHorizontal = direction === "RIGHT";
  const isContainer = nodeType === "container";

  const hasChildren =
    Array.isArray(element.children) && element.children.length > 0;

  const defaultSize =
    isContainer && hasChildren
      ? { width: 300, height: 300 }
      : { width: 150, height: 50 };

  const possiblePosition = position ?? defaultPosition;

  return {
    id: element.id,
    type: nodeType,
    data: {
      ...getDataFromElement(element, libraryElement),
      direction,
    },
    position: element.parentElementId ? defaultPosition : possiblePosition,
    draggable: !(
      libraryElement?.parentRestriction !== undefined &&
      libraryElement?.parentRestriction.length > 0
    ),
    targetPosition: isHorizontal ? Position.Left : Position.Top,
    sourcePosition: isHorizontal ? Position.Right : Position.Bottom,
    ...defaultSize,
    ...(element.parentElementId && {
      parentId: element.parentElementId,
    }),
    ...(isContainer
      ? {
          className: "container-node",
          style: {
            borderRadius: 5,
            fontWeight: 500,
          },
        }
      : {
          style: {
            backgroundColor: getElementColor(libraryElement),
            borderRadius: 5,
            fontWeight: 500,
          },
        }),
  };
}

export function getElementColor(
  libraryElement: LibraryElement | undefined,
): string {
  if (!libraryElement) {
    return "#fdf39d";
  }

  switch (libraryElement.colorType) {
    case ElementColorType.SENDER:
      return "#bddcf2";
    case ElementColorType.TRIGGER:
      return "#a5e1d2";
    case ElementColorType.CHAIN_CALL:
      return "#cfc3ef";
    case ElementColorType.COMPOSITE_TRIGGER:
      return "#c9e1a5";
    case ElementColorType.UNSUPPORTED:
      return "#b8b8b8";
    default:
      return "#fdf39d";
  }
}

export function collectChildren(
  parentId: string,
  nodes: Node<ChainGraphNodeData>[],
): ChainGraphNode[] {
  const childrenMap = new Map<string, ChainGraphNode[]>();

  for (const node of nodes) {
    const pid = node.parentId;
    if (pid) {
      if (!childrenMap.has(pid)) {
        childrenMap.set(pid, []);
      }
      childrenMap.get(pid)!.push(node);
    }
  }

  const result: ChainGraphNode[] = [];

  const traverse = (id: string) => {
    const children = childrenMap.get(id) || [];
    for (const child of children) {
      result.push(child);
      traverse(child.id);
    }
  };

  traverse(parentId);
  return result;
}

export function getPossibleGraphIntersection(
  allIntersections: Node[],
  draggedChildren?: Node[],
): Node | undefined {
  return allIntersections
    .filter((intersectingNode) => !draggedChildren?.includes(intersectingNode))
    .filter((intersectingNode) => intersectingNode.type === "container")
    .sort((a, b) => {
      const areaA = (a.width ?? 0) * (a.height ?? 0);
      const areaB = (b.width ?? 0) * (b.height ?? 0);
      return areaA - areaB;
    })[0];
}

export function getIntersectionParent(
  draggedNode: Node,
  parentCandidate: Node,
  libraryElements: LibraryElement[],
): Node | undefined {
  if (parentCandidate === undefined) return undefined;

  let parentNode: Node | undefined = undefined;

  const intersectDescriptor: LibraryElement | undefined = libraryElements?.find(
    (libraryElement) =>
      libraryElement.name === parentCandidate.data.elementType,
  );
  if (intersectDescriptor) {
    parentNode =
      Object.keys(intersectDescriptor.allowedChildren).length === 0 ||
      Object.keys(intersectDescriptor.allowedChildren).includes(
        <string>draggedNode.data.elementType,
      )
        ? parentCandidate
        : parentNode;
  } else if (parentCandidate.data.elementType == "container") {
    parentNode = parentCandidate;
  }
  return parentNode;
}

export function findUpdatedElement(
  updatedElements: Element[] | undefined,
  elementId: string,
): Element | undefined {
  if (!updatedElements) return undefined;

  let updatedElementsPlain: Element[] = [];
  updatedElements.forEach((updatedElement) => {
    updatedElementsPlain.push(updatedElement);
    if (updatedElement.children !== undefined) {
      updatedElementsPlain = updatedElementsPlain.concat(
        updatedElement.children,
      );
    }
  });

  return updatedElementsPlain.find(
    (updatedElement) => updatedElement.id === elementId,
  );
}

export function getFakeNode(flowPosition: XYPosition): ChainGraphNode {
  return {
    id: "fake",
    width: 1,
    height: 1,
    position: flowPosition,
  } as ChainGraphNode;
}

export function applyHighlight(
  nodes: ChainGraphNode[],
  highlightId?: string,
): ChainGraphNode[] {
  return nodes.map((node) => ({
    ...node,
    className: highlightId?.includes(node.id) ? "highlight" : "",
  }));
}

export function computeNestedUnitCounts(
  nodes: ChainGraphNode[],
): Map<string, number> {
  const childrenMap = new Map<string, ChainGraphNode[]>();
  for (const node of nodes) {
    if (node.parentId) {
      (
        childrenMap.get(node.parentId) ??
        childrenMap.set(node.parentId, []).get(node.parentId)!
      ).push(node);
    }
  }

  const childrenCountMap = new Map<string, number>();
  const visiting = new Set<string>();

  const getNestedNodesCount = (id: string): number => {
    if (childrenCountMap.has(id)) return childrenCountMap.get(id)!;
    if (visiting.has(id)) return 0;
    visiting.add(id);

    let counter = 0;
    const children = childrenMap.get(id) ?? [];
    for (const child of children) {
      if (child.type === "container") {
        counter += getNestedNodesCount(child.id);
      } else {
        counter += 1 + getNestedNodesCount(child.id);
      }
    }
    visiting.delete(id);
    childrenCountMap.set(id, counter);
    return counter;
  };

  for (const node of nodes) {
    if (node.type === "container") getNestedNodesCount(node.id);
  }
  return childrenCountMap;
}

export function collectSubgraphByParents(
  parentIds: string[],
  allNodes: ChainGraphNode[],
): ChainGraphNode[] {
  const subGraphMap = new Map<string, ChainGraphNode>();

  for (const parentId of parentIds) {
    const parent = allNodes.find((n) => n.id === parentId);
    if (!parent) continue;

    subGraphMap.set(parent.id, parent);
    const children = collectChildren(parent.id, allNodes);
    for (const ch of children) subGraphMap.set(ch.id, ch);
  }
  return Array.from(subGraphMap.values());
}

export function edgesForSubgraph(
  allEdges: Edge[],
  nodesInSubgraph: ChainGraphNode[],
): Edge[] {
  const ids = new Set(nodesInSubgraph.map((n) => n.id));
  return allEdges.filter((e) => ids.has(e.source) && ids.has(e.target));
}

export function sortParentsBeforeChildren<
  T extends { id: string; parentId?: string },
>(nodes: T[]): T[] {
  const nodesMap = new Map(nodes.map((node) => [node.id, node]));
  const depthCache = new Map<string, number>();
  const getDepth = (n: T): number => {
    let depth = 0;
    let parentId = n.parentId ? nodesMap.get(n.parentId) : undefined;
    while (parentId) {
      depth++;
      parentId = parentId.parentId
        ? nodesMap.get(parentId.parentId)
        : undefined;
      if (depth > 1000) break;
    }
    return depth;
  };

  const index = new Map(nodes.map((node, i) => [node.id, i]));

  return [...nodes].sort((left, right) => {
    const leftDepth = depthCache.has(left.id)
      ? depthCache.get(left.id)!
      : getDepth(left);
    const rightDepth = depthCache.has(right.id)
      ? depthCache.get(right.id)!
      : getDepth(right);
    if (!depthCache.has(left.id)) depthCache.set(left.id, leftDepth);
    if (!depthCache.has(right.id)) depthCache.set(right.id, rightDepth);

    if (leftDepth !== rightDepth) return leftDepth - rightDepth;
    return index.get(left.id)! - index.get(right.id)!;
  });
}

export function getContainerIdsForEdges(
  edges: Edge[],
  allNodes: ChainGraphNode[],
): string[] {
  const nodeMap = new Map(allNodes.map((node) => [node.id, node]));
  const resultSet = new Set<string>();
  for (const edge of edges) {
    const sourceParent = nodeMap.get(edge.source)?.parentId;
    const targetParent = nodeMap.get(edge.target)?.parentId;
    if (sourceParent) resultSet.add(sourceParent);
    if (targetParent) resultSet.add(targetParent);
  }
  return Array.from(resultSet);
}

export function getParentChain(
  id: string | undefined,
  allNodes: ChainGraphNode[],
): string[] {
  if (!id) return [];
  const nodeMap = new Map(allNodes.map((node) => [node.id, node]));
  const chain: string[] = [];
  const seen = new Set<string>();

  let cur: string | undefined = id;
  while (cur && !seen.has(cur)) {
    chain.push(cur);
    seen.add(cur);
    cur = nodeMap.get(cur)?.parentId;
  }
  return chain;
}

export function getLeastCommonParent(
  left: string | undefined,
  right: string | undefined,
  allNodes: ChainGraphNode[],
): string | undefined {
  if (!left || !right) return undefined;
  if (left === right) return left;

  const nodeMap = new Map(allNodes.map((node) => [node.id, node]));
  const up = new Set(getParentChain(left, allNodes));

  let cur: string | undefined = right;
  const seen = new Set<string>();
  while (cur && !seen.has(cur)) {
    if (up.has(cur)) return cur;
    seen.add(cur);
    cur = nodeMap.get(cur)?.parentId;
  }
  return undefined;
}

export function expandWithParent(
  parentIds: string[],
  allNodes: ChainGraphNode[],
): string[] {
  const nodeMap = new Map(allNodes.map((node) => [node.id, node]));
  const out = new Set<string>(parentIds);
  const stack = [...parentIds];
  while (stack.length) {
    const id = stack.pop()!;
    const node = nodeMap.get(id);
    if (node?.parentId && !out.has(node.parentId)) {
      out.add(node.parentId);
      stack.push(node.parentId);
    }
  }
  return Array.from(out.values());
}

export function mergeWithPinnedPositions(
  base: ChainGraphNode[],
  laidSubset: ChainGraphNode[],
  pinnedIds: Set<string>,
): ChainGraphNode[] {
  const baseMap = new Map(base.map((node) => [node.id, node]));
  const laidMap = new Map(laidSubset.map((node) => [node.id, node]));
  return base.map((node) => {
    const laid = laidMap.get(node.id);
    if (!laid) return node;
    const merged: ChainGraphNode = { ...node, ...laid };
    if (pinnedIds.has(node.id)) {
      merged.position = baseMap.get(node.id)?.position ?? merged.position;
    }
    return merged;
  });
}

export function depthOf(id: string, byId: Map<string, ChainGraphNode>): number {
  let depth = 0;
  let parent = byId.get(id)?.parentId;
  while (parent) {
    depth++;
    parent = byId.get(parent)?.parentId;
    if (depth > 1000) break;
  }
  return depth;
}
