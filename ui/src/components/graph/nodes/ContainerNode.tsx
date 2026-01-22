import { Handle, NodeProps, Position } from "@xyflow/react";
import { ChainGraphNode } from "./ChainGraphNodeTypes.ts";
import { Badge, Button, Tooltip } from "antd";
import React, { useLayoutEffect, useMemo, useRef, useState } from "react";
import { EllipsisLabel } from "./EllipsisLabel";
import styles from "./ContainerNode.module.css";
import { IconName, OverridableIcon } from "../../../icons/IconProvider.tsx";

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
      className={`${styles.container} ${selected ? styles.containerSelected : ""}`}
      data-node-type="container"
    >
      <div
        className={styles.header}
        style={{ paddingRight: actionsWidth ? actionsWidth + 8 : 32 }}
      >
        <div ref={actionsRef} className={styles.actions}>
          <Tooltip title={isCollapsed ? "Expand" : "Collapse"}>
            <Button
              size="small"
              type="text"
              icon={
                isCollapsed ? (
                  <OverridableIcon name="caretRightFilled" />
                ) : (
                  <OverridableIcon name="caretDownFilled" />
                )
              }
              onClick={() => data.onToggleCollapse?.()}
              tabIndex={-1}
            />
          </Tooltip>
        </div>

        <div className={styles.labelWrapper}>
          <OverridableIcon
            name={data.elementType as IconName}
            style={{ fontSize: 16 }}
          />

          <EllipsisLabel
            text={trimmedLabel}
            style={{ flex: 1, minWidth: 0, display: "block" }}
          />

          {data.unitCount! > 0 && (
            <Badge
              count={data.unitCount}
              status="default"
              size="small"
              className={styles.badge}
            />
          )}
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
