import { NodeProps } from "@xyflow/react";
import { ChainGraphNode } from "./ChainGraphNodeTypes.ts";
import { Badge, Button, Flex, Tooltip } from "antd";
import { useMemo } from "react";
import { EllipsisLabel } from "./EllipsisLabel";
import styles from "./ContainerNode.module.css";
import { IconName, OverridableIcon } from "../../../icons/IconProvider.tsx";
import { ContainerNodeBase } from "./ContainerNodeBase.tsx";

export function ContainerNode({ data, ...rest }: NodeProps<ChainGraphNode>) {
  const isCollapsed = !!data.collapsed;

  const trimmedLabel = useMemo(
    () => (data.label?.split("\n")[0] ?? "Container").trim(),
    [data.label],
  );

  return (
    <ContainerNodeBase
      header={
        <Flex
          vertical={false}
          align={"center"}
          justify={"space-between"}
          wrap={false}
          gap={"small"}
          style={{ padding: "4px" }}
        >
          <OverridableIcon
            name={data.elementType as IconName}
            style={{ fontSize: 16 }}
          />
          <EllipsisLabel
            text={trimmedLabel}
            style={{ flexGrow: 1, minWidth: 0, display: "block" }}
          />
          {data.unitCount! > 0 && (
            <Badge
              count={data.unitCount}
              status="default"
              size="small"
              className={styles.badge}
            />
          )}
          <div>
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
        </Flex>
      }
      color={"var(--container-border-color, #dedacd)"}
      contentStyle={{
        opacity: 1,
        backgroundColor: "var(--vscode-editor-background, #fff)",
      }}
      {...{ data, ...rest }}
    />
  );
}
