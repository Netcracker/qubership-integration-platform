import {Handle, NodeProps, Position} from "@xyflow/react";
import {ChainGraphNode} from "./ChainGraphNodeTypes.ts";


export function UnitNode({
  data,
  isConnectable,
  selected,
  targetPosition = Position.Left,
  sourcePosition = Position.Right,
}:  NodeProps<ChainGraphNode>) {
  return (
    <div
      style={{
        padding: 10,
        border: selected ? "2px solid #000" : "1px solid #000",
        borderRadius: 4,
        background: "white",
        minWidth: 100,
        textAlign: "center",
      }}
    >
      {data.label ?? "Node"}

      {data.inputEnabled !== false && (
        <Handle
          type="target"
          position={targetPosition}
          isConnectable={isConnectable}
        />
      )}

      {data.outputEnabled !== false && (
        <Handle
          type="source"
          position={sourcePosition}
          isConnectable={isConnectable}
        />
      )}
    </div>
  );
}
