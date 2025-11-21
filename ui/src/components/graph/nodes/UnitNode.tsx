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
        border: "2px solid transparent",
        borderColor: selected ? "#000" : "transparent",
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
          gap: 6,
          userSelect: "none",
          textAlign: "center",
          lineHeight: 1.2,
          paddingTop: "8px",
          paddingBottom: "6px",
          paddingLeft: "8px",
        }}
      >
        <OverridableIcon
          name={data.elementType as IconName}
          style={{ fontSize: 16 }}
        />
        <div
          style={{
            width: "100%",
          }}
        >
          <EllipsisLabel
            text={trimmedLabel}
            style={{
              minWidth: 0,
              fontSize: 12,
              textAlign: "left",
            }}
          />
          <div
            style={{
              textAlign: "right",
              marginTop: 2,
            }}
          >
            <span
              style={{
                background: "rgba(0, 0, 0, 0.15)",
                padding: "2px 5px",
                borderRadius: 3,
                fontSize: 8,
              }}
            >
              {data.typeTitle}
            </span>
          </div>
        </div>
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
