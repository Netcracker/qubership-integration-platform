import { Handle, NodeProps, Position } from "@xyflow/react";
import { ChainGraphNode } from "./ChainGraphNodeTypes.ts";
import { Badge, Button, Tooltip } from "antd";
import React, { useMemo } from "react";
import { EllipsisLabel } from "./EllipsisLabel";
import styles from "./ContainerNode.module.css";
import { IconName, OverridableIcon } from "../../../icons/IconProvider.tsx";

export function ContainerNode({
  data,
  isConnectable,
  selected,
  targetPosition = Position.Left,
  sourcePosition = Position.Right,
}: NodeProps<ChainGraphNode>) {
  const isCollapsed = !!data.collapsed;
  const isGroupContainer = data.elementType === "container";

  const trimmedLabel = useMemo(
    () => (data.label?.split("\n")[0] ?? "Container").trim(),
    [data.label],
  );

  const shouldRenderTargetHandle = !!data.inputEnabled || isGroupContainer;
  const shouldRenderSourceHandle = !!data.outputEnabled || isGroupContainer;

  const hideTargetHandle = isGroupContainer && !data.inputEnabled;
  const hideSourceHandle = isGroupContainer && !data.outputEnabled;

  const hiddenHandleStyle: React.CSSProperties = {
    opacity: 0,
    pointerEvents: "none",
  };

  return (
    <div
      className={`${styles.container} ${selected ? styles.containerSelected : ""}`}
      data-node-type="container"
    >
      <div
        className={styles.header}
        style={{ paddingRight: 30, paddingLeft: 3 }}
      >
        <div className={styles.actions}>
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

      {shouldRenderTargetHandle && (
        <Handle
          type="target"
          position={targetPosition}
          isConnectable={isConnectable && !!data.inputEnabled}
          style={hideTargetHandle ? hiddenHandleStyle : undefined}
        />
      )}

      {shouldRenderSourceHandle && (
        <Handle
          type="source"
          position={sourcePosition}
          isConnectable={isConnectable && !!data.outputEnabled}
          style={hideSourceHandle ? hiddenHandleStyle : undefined}
        />
      )}
    </div>
  );
}
