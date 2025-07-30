import { Element, ElementDescriptor } from "../api/apiTypes.ts";
import { Node, Position, XYPosition } from "@xyflow/react";
import { ElkDirection } from "../hooks/graph/useElkDirection.tsx";
import {
  ChainGraphNode,
  ChainGraphNodeData,
} from "../components/graph/nodes/ChainGraphNodeTypes.ts";

export function getDataFromElement(
  element: Element,
  descriptor?: ElementDescriptor,
): ChainGraphNodeData {
  return {
    elementType: element.type,
    label: element.name,
    description: element.description,
    properties: element.properties,
    inputEnabled: descriptor?.inputEnabled ?? true,
    outputEnabled: descriptor?.outputEnabled ?? true,
  };
}

export function getElementDescriptor(
  element: Element,
  elementDescriptors: ElementDescriptor[] | null,
): ElementDescriptor {
  const elementDescriptor = elementDescriptors?.find(
    (descriptor) => descriptor.name === element.type,
  );
  return (
    elementDescriptor ??
    ({
      name: "default",
      title: "Default",
      description: "Element by Default",
    } as ElementDescriptor)
  );
}

export function getNodeFromElement(
  element: Element,
  descriptor?: ElementDescriptor,
  position?: XYPosition,
  direction?: ElkDirection,
): ChainGraphNode {
  const nodeType = descriptor?.container ? "container" : "unit";
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
      ...getDataFromElement(element, descriptor),
      direction,
    },
    position: nodePosition,
    targetPosition: isHorizontal ? Position.Left : Position.Top,
    sourcePosition: isHorizontal ? Position.Right : Position.Bottom,
    ...defaultSize,
    ...(element.parentElementId && {
      parentId: element.parentElementId,
      extent: "parent",
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
