import { NodeProps } from "@xyflow/react";
import React, { ReactNode, useContext, useMemo } from "react";
import { ChainGraphNode } from "./ChainGraphNodeTypes.ts";
import { EllipsisLabel } from "./EllipsisLabel.tsx";
import { Flex } from "antd";
import { SWIMLANE_COLORS } from "../../../theme/semanticColors.ts";
import { ContainerNodeBase } from "./ContainerNodeBase.tsx";
import { ChainContext } from "../../../pages/ChainPage.tsx";
import styles from "./SwimlaneNode.module.css";

export function getSwimlaneBorderColor(color: string): string {
  return SWIMLANE_COLORS[color] ?? SWIMLANE_COLORS.blue;
}

export const SwimlaneNode: React.FC<NodeProps<ChainGraphNode>> = ({
  id,
  data,
  ...rest
}): ReactNode => {
  const chainContext = useContext(ChainContext);
  const isDefault = id === chainContext?.chain?.defaultSwimlaneId;
  const isReuse = id === chainContext?.chain?.reuseSwimlaneId;

  const trimmedLabel = useMemo(
    () => (data.label?.split("\n")[0] ?? "Swimlane").trim(),
    [data.label],
  );

  const color = getSwimlaneBorderColor(
    (data.properties as Record<string, unknown>)["color"] as string,
  );

  return (
    <ContainerNodeBase
      header={
        <Flex
          style={{
            padding: 8,
            backgroundColor: color,
            position: "relative",
          }}
        >
          {(isDefault || isReuse) && (
            <span
              className={
                data.direction === "RIGHT"
                  ? styles.labelTagRight
                  : styles.labelTagDown
              }
            >
              {isDefault ? "Default" : "Reuse"}
            </span>
          )}
          <EllipsisLabel
            text={trimmedLabel}
            style={{
              minWidth: 0,
              writingMode:
                data.direction === "RIGHT" ? "sideways-lr" : "horizontal-tb",
              display: "block",
            }}
          />
        </Flex>
      }
      color={color}
      direction={data.direction}
      {...{ id, data, ...rest }}
    />
  );
};
