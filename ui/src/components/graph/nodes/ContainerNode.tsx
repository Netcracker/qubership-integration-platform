import { Handle, NodeProps, Position } from "@xyflow/react";
import { ChainGraphNode } from "./ChainGraphNodeTypes.ts";
import { Badge, Button, Tooltip } from "antd";
import { CaretRightFilled, CaretDownFilled } from "@ant-design/icons";
import React, { useLayoutEffect, useMemo, useRef, useState } from "react";
import { EllipsisLabel } from "./EllipsisLabel";

function useElementWidth(ref: React.RefObject<HTMLElement>) {
  const [width, setWidth] = useState(0);
  useLayoutEffect(() => {
    const element = ref.current;
    if (!element) return;
    const resizeObserver = new ResizeObserver((entries) => {
      const contentRect = entries[0]?.contentRect;
      if (contentRect) setWidth(contentRect.width);
    });
    resizeObserver.observe(element);
    return () => resizeObserver.disconnect();
  }, [ref]);
  return width;
}

export function ContainerNode({
  data,
  isConnectable,
  selected,
  targetPosition = Position.Left,
  sourcePosition = Position.Right,
}: NodeProps<ChainGraphNode>) {
  const isCollapsed = !!data.collapsed;
  const trimmedLabel = useMemo(
    () => (data.label?.split("\n")[0] ?? "Container").trim(),
    [data.label],
  );

  const actionsRef = useRef<HTMLDivElement>(null);
  const actionsWidth = useElementWidth(actionsRef);

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
          position: "relative",
          padding: "4px 10px 8px 10px",
          paddingRight: actionsWidth ? actionsWidth + 8 : 32,
          background: "#f5f5f5",
          borderBottom: "1px solid #ddd",
          fontWeight: "bold",
          fontSize: 12,
          lineHeight: 1.2,
          flexShrink: 0,
        }}
      >
        <div
          ref={actionsRef}
          style={{
            position: "absolute",
            top: 2,
            right: 2,
            zIndex: 1,
            display: "flex",
            alignItems: "center",
            gap: 4,
          }}
        >
          <Tooltip title={isCollapsed ? "Expand" : "Collapse"}>
            <Button
              size="small"
              type="text"
              icon={isCollapsed ? <CaretRightFilled /> : <CaretDownFilled />}
              onClick={() => data.onToggleCollapse?.()}
              tabIndex={-1}
            />
          </Tooltip>
        </div>

        <div
          style={{
            display: "flex",
            alignItems: "center",
            gap: 6,
            minWidth: 0,
          }}
        >
          {data.unitCount! > 0 && (
            <Badge
              count={data.unitCount}
              status="default"
              size="small"
              color="#000000"
              style={{ display: "inline-flex" }}
            />
          )}

          <EllipsisLabel
            text={trimmedLabel}
            style={{ flex: 1, minWidth: 0, display: "block" }}
          />
        </div>
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
