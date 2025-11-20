import { Handle, NodeProps, Position } from "@xyflow/react";
import { ChainGraphNode } from "./ChainGraphNodeTypes.ts";
import { useMemo } from "react";
import { EllipsisLabel } from "./EllipsisLabel";
import { IconName, OverridableIcon } from "../../../icons/IconProvider.tsx";

export function UnitNode({
  data,
  isConnectable,
  selected,
  targetPosition = Position.Left,
  sourcePosition = Position.Right,
}: NodeProps<ChainGraphNode>) {
  const trimmedLabel = useMemo(
    () => (data.label?.split("\n")[0] ?? "Node").trim(),
    [data.label],
  );

  return (
    <div
      style={{
        border: selected ? "2px solid #000" : undefined,
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
          width: "100%",
          height: "100%",
          flexDirection: "row",
          display: "flex",
          alignItems: "center",
          gap: 6,
          placeItems: "center",
          userSelect: "none",
          textAlign: "center",
          lineHeight: 1.2,
          padding: "6px 8px",
        }}
      >
        <OverridableIcon name={data.elementType as IconName} style={{ fontSize: 16 }} />
        <EllipsisLabel
          text={trimmedLabel}
          style={{
            maxWidth: "100%",
            minWidth: 0,
            fontSize: 12,
          }}
        />
      </div>

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
