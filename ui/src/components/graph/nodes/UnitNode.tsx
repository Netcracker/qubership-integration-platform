import { NodeProps } from "@xyflow/react";
import { ChainGraphNode } from "./ChainGraphNodeTypes.ts";
import { useMemo } from "react";
import { IconName, OverridableIcon } from "../../../icons/IconProvider.tsx";
import { Flex } from "antd";
import { NodeContentWrapper } from "./NodeContentWrapper.tsx";

export function UnitNode({ data, ...rest }: NodeProps<ChainGraphNode>) {
  const trimmedLabel = useMemo(
    () => (data.label?.split("\n")[0] ?? "Node").trim(),
    [data.label],
  );

  return (
    <NodeContentWrapper
      {...{ data, ...rest }}
      style={{
        boxShadow:
          data.mandatoryChecksPassed === false
            ? "0 0 0 2px var(--ant-color-error, #ff4d4f)"
            : "none",
        display: "flex",
        flexDirection: "column",
      }}
    >
      <Flex
        gap={"2px"}
        style={{ paddingTop: "8px", paddingBottom: "6px" }}
        vertical={true}
      >
        <Flex
          gap={"small"}
          vertical={false}
          align={"center"}
          justify={"left"}
          wrap={false}
          style={{ width: "100%", padding: "0 8px 0 8px" }}
        >
          <OverridableIcon
            name={data.elementType as IconName}
            style={{ fontSize: 16 }}
          />
          <div
            style={{
              flex: 1,
            }}
          >
            <span
              style={{
                fontSize: 12,
                textAlign: "left",
                overflowWrap: "anywhere",
                display: "block",
              }}
            >
              {trimmedLabel}
            </span>
          </div>
        </Flex>
        <div
          style={{
            textAlign: "right",
          }}
        >
          <span
            style={{
              background: "rgba(0, 0, 0, 0.15)",
              padding: "2px 5px",
              borderTopLeftRadius: 3,
              borderBottomLeftRadius: 3,
              borderTopRightRadius: 0,
              borderBottomRightRadius: 0,
              fontSize: 8,
            }}
          >
            {data.typeTitle}
          </span>
        </div>
      </Flex>
    </NodeContentWrapper>
  );
}
