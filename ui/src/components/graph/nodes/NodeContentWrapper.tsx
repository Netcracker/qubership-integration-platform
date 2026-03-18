import { Handle, NodeProps, Position } from "@xyflow/react";
import { ChainGraphNode } from "./ChainGraphNodeTypes.ts";
import React, { HTMLAttributes, PropsWithChildren, ReactNode } from "react";
import styles from "./NodeContentWrapper.module.css";

export type NodeContentWrapperProps = PropsWithChildren<
  NodeProps<ChainGraphNode>
> &
  Pick<HTMLAttributes<HTMLDivElement>, "style">;

export const NodeContentWrapper: React.FC<NodeContentWrapperProps> = ({
  data,
  isConnectable,
  selected,
  targetPosition = Position.Left,
  sourcePosition = Position.Right,
  style,
  children,
}): ReactNode => {
  const isGroupContainer = data.elementType === "container";

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
      className={`${styles.wrapper} ${selected ? styles.selected : ""}`}
      style={{
        ...style,
        transition: "outline-color var(--transition-duration, 0.25s) ease",
      }}
    >
      {children}

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
};
