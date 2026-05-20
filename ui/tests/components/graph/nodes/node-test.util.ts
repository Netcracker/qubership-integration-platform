import { ChainGraphNode } from "../../../../src/components/graph/nodes/ChainGraphNodeTypes.ts";
import { NodeProps, Position } from "@xyflow/react";

export function makeProps(
  overrides: Omit<Partial<NodeProps<ChainGraphNode>>, "data"> & {
    data?: Partial<ChainGraphNode["data"]>;
  } = {},
): NodeProps<ChainGraphNode> {
  const { data: dataOverrides, ...rest } = overrides;
  return {
    id: "test-container",
    type: "container",
    data: {
      elementType: "try-catch",
      label: "Container Label",
      description: "",
      properties: {},
      inputEnabled: true,
      outputEnabled: true,
      collapsed: false,
      unitCount: 0,
      onToggleCollapse: jest.fn(),
      ...dataOverrides,
    },
    selected: false,
    isConnectable: true,
    zIndex: 0,
    positionAbsoluteX: 0,
    positionAbsoluteY: 0,
    targetPosition: Position.Left,
    sourcePosition: Position.Right,
    dragging: false,
    dragHandle: undefined,
    parentId: undefined,
    ...rest,
  } as NodeProps<ChainGraphNode>;
}
