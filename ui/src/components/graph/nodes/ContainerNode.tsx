import { Handle, NodeProps, Position } from "@xyflow/react";
import {ChainGraphNode} from "./ChainGraphNodeTypes.ts";

export function ContainerNode({
  data,
  isConnectable,
  selected,
  targetPosition = Position.Left,
  sourcePosition = Position.Right,
}: NodeProps<ChainGraphNode>) {
  return (
    <div
      style={{
        background: "#fff",
        border: selected ? "2px solid #000" : "1px solid #000",
        borderRadius: 5,
        width: "100%",
        height: "100%",
        display: "flex",
        flexDirection: "column",
        overflow: "hidden",
      }}
    >
      <div
        style={{
          padding: "4px 8px",
          background: "#f5f5f5",
          borderBottom: "1px solid #ddd",
          fontWeight: "bold",
          fontSize: "12px",
          flexShrink: 0,
          whiteSpace: "nowrap",
          textOverflow: "ellipsis",
        }}
      >
        {data.label ?? "Container"}
      </div>
      {data.inputEnabled && (
        <Handle
          type="target"
          position={targetPosition}
          isConnectable={isConnectable}
        />
      )}

      {data.outputEnabled && (
        <Handle
          type="source"
          position={sourcePosition}
          isConnectable={isConnectable}
        />
      )}
    </div>
  );
}
