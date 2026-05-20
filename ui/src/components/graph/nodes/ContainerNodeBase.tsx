import {
  NodeContentWrapper,
  NodeContentWrapperProps,
} from "./NodeContentWrapper.tsx";
import React, { CSSProperties, ReactNode, useContext } from "react";
import { ElkDirection } from "../../../hooks/graph/useElkDirection.tsx";
import { ThemeContext } from "../../../theme/context.tsx";

export type ContainerNodeBaseProps = NodeContentWrapperProps & {
  header: ReactNode;
  color: string;
  direction?: ElkDirection;
  contentStyle?: CSSProperties;
};
export const ContainerNodeBase: React.FC<ContainerNodeBaseProps> = ({
  header,
  color,
  direction = "DOWN",
  contentStyle,
  ...rest
}): ReactNode => {
  const theme = useContext(ThemeContext);
  return (
    <NodeContentWrapper
      {...rest}
      style={{
        outlineColor: color,
        display: "flex",
        flexDirection: direction === "DOWN" ? "column" : "row",
        ...(rest.data.deprecated
          ? {
              background: `repeating-linear-gradient(135deg, #9ca3af, #9ca3af 1px, ${color} 2px, ${color} 10px)`,
            }
          : { backgroundColor: color }),
      }}
    >
      {header}
      <div
        style={{
          width: "100%",
          height: "100%",
          display: "block",
          padding: 1.4,
        }}
      >
        <div
          style={{
            flexGrow: 1,
            opacity:
              theme?.theme === "light"
                ? 0.2
                : theme?.theme === "high-contrast"
                  ? 1
                  : 0.8,
            borderRadius: 3,
            backgroundColor: "black",
            height: "100%",
            width: "100%",
            ...contentStyle,
          }}
        />
      </div>
    </NodeContentWrapper>
  );
};
