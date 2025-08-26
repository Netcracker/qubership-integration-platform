import { Element, LibraryElement } from "../api/apiTypes.ts";
import { Node, Position, XYPosition } from "@xyflow/react";
import { ElkDirection } from "../hooks/graph/useElkDirection.tsx";
import {
  ChainGraphNode,
  ChainGraphNodeData,
} from "../components/graph/nodes/ChainGraphNodeTypes.ts";

export function getDataFromElement(
  element: Element,
  libraryElement?: LibraryElement,
): ChainGraphNodeData {
  return {
    elementType: element.type,
    label: element.name,
    description: element.description,
    properties: element.properties,
    inputEnabled: libraryElement?.inputEnabled ?? true,
    outputEnabled: libraryElement?.outputEnabled ?? true,
  };
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
  position?: XYPosition,
  direction?: ElkDirection,
): ChainGraphNode {
  const nodeType = libraryElement?.container ? "container" : "unit";
  const nodePosition = position ?? { x: 0, y: 0 };
  const isHorizontal = direction === "RIGHT";
  const isContainer = nodeType === "container";

  const hasChildren =
    Array.isArray(element.children) && element.children.length > 0;

  const defaultSize =
    isContainer && hasChildren
      ? { width: 300, height: 300 }
      : { width: 150, height: 50 };

  return {
    id: element.id,
    type: nodeType,
    data: {
      ...getDataFromElement(element, libraryElement),
      direction,
    },
    position: nodePosition,
    draggable: libraryElement?.inputEnabled && libraryElement?.outputEnabled,
    targetPosition: isHorizontal ? Position.Left : Position.Top,
    sourcePosition: isHorizontal ? Position.Right : Position.Bottom,
    ...defaultSize,
    ...(element.parentElementId && {
      parentId: element.parentElementId,
    }),
  };
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

export function applyHighlight(nodes: ChainGraphNode[], highlightId?: string): ChainGraphNode[] {
  return nodes.map((node) => ({
    ...node,
    className: highlightId?.includes(node.id)  ? "highlight" : "",
  }));
}
