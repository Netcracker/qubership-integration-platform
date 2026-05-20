import { Handle, NodeProps, Position } from "@xyflow/react";
import { ChainGraphNode } from "./ChainGraphNodeTypes.ts";
import React, {
  HTMLAttributes,
  PropsWithChildren,
  ReactNode,
  useContext,
  useMemo,
} from "react";
import styles from "./NodeContentWrapper.module.css";
import {
  ChainGraphChangeContext,
  NodeState,
} from "../../chains/diff/ChainGraphChangeProvider.tsx";

export type NodeContentWrapperProps = PropsWithChildren<
  NodeProps<ChainGraphNode>
> &
  Pick<HTMLAttributes<HTMLDivElement>, "style">;

export const NodeContentWrapper: React.FC<NodeContentWrapperProps> = ({
  id,
  data,
  isConnectable,
  selected,
  targetPosition = Position.Left,
  sourcePosition = Position.Right,
  style,
  children,
}): ReactNode => {
  const changeContext = useContext(ChainGraphChangeContext);

  const isGroupContainer = data.elementType === "container";

  const shouldRenderTargetHandle = !!data.inputEnabled || isGroupContainer;
  const shouldRenderSourceHandle = !!data.outputEnabled || isGroupContainer;

  const hideTargetHandle = isGroupContainer && !data.inputEnabled;
  const hideSourceHandle = isGroupContainer && !data.outputEnabled;

  const hiddenHandleStyle: React.CSSProperties = {
    opacity: 0,
    pointerEvents: "none",
  };

  const backgroundColor = useMemo(() => {
    const nodeState = changeContext?.nodeState?.get(id);
    switch (nodeState) {
      case NodeState.NOT_CHANGED:
        return "var(--node-not-changed-color, #727272)";
      case NodeState.CHANGED:
        return "var(--node-changed-color, #ffcc02)";
      case NodeState.REMOVED:
        return "var(--node-removed-color, #f48771)";
      case NodeState.CREATED:
        return "var(--node-created-color, #4ec9b0)";
      default:
        return style?.["backgroundColor"];
    }
  }, [id, changeContext, style]);

  return (
    <div
      className={`${styles.wrapper} ${selected ? styles.selected : ""}`}
      style={{
        ...style,
        transition: "outline-color var(--transition-duration, 0.25s) ease",
        backgroundColor: backgroundColor,
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
