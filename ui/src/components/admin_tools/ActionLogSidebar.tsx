import { ActionLog } from "../../api/apiTypes.ts";
import React, { ReactNode, useMemo } from "react";
import { Col, Drawer, Listy, Row } from "antd";
import {
  capitalize,
  formatOptional,
  formatSnakeCased,
  formatTimestamp,
  PLACEHOLDER,
} from "../../misc/format-utils.ts";

export type ActionLogSidebarProps = {
  value: ActionLog | null | undefined;
  open: boolean;
  onClose: () => void;
  entityLinkRenderer: (v: ActionLog) => ReactNode;
  parentLinkRenderer: (v: ActionLog) => ReactNode;
};

type SidebarItem = {
  key: ReactNode;
  value: ReactNode;
};

export const ActionLogSidebar: React.FC<ActionLogSidebarProps> = ({
  value,
  open,
  onClose,
  entityLinkRenderer,
  parentLinkRenderer,
}): ReactNode => {
  const items = useMemo(
    () => [
      {
        key: "Action Time",
        value: formatTimestamp(value?.actionTime),
      },
      {
        key: "Initiator",
        value: formatOptional(value?.username),
      },
      {
        key: "Operation",
        value: formatOptional(value?.operation, (v) =>
          formatSnakeCased(capitalize(v)),
        ),
      },
      {
        key: "Entity Id",
        value: formatOptional(value?.entityId),
      },
      {
        key: "Entity Type",
        value: formatOptional(value?.entityType, (v) =>
          formatSnakeCased(capitalize(v)),
        ),
      },
      {
        key: "Entity Name",
        value: value ? entityLinkRenderer(value) : PLACEHOLDER,
      },
      {
        key: "Parent Id",
        value: formatOptional(value?.parentId),
      },
      {
        key: "Parent Name",
        value: value ? parentLinkRenderer(value) : PLACEHOLDER,
      },
      {
        key: "Request Id",
        value: formatOptional(value?.requestId),
      },
    ],
    [value, entityLinkRenderer, parentLinkRenderer],
  );

  return (
    <Drawer
      title="Action Details"
      placement="right"
      size={350}
      open={open}
      onClose={onClose}
      destroyOnHidden
      closable={{ placement: "end" }}
      getContainer={".page-content"}
      rootStyle={{ position: "absolute" }}
      classNames={{ body: "details-sidebar-body" }}
    >
      <Listy<SidebarItem>
        classNames={{
          groupHeader: "details-sidebar-properties-group",
        }}
        style={{ height: "100%" }}
        items={items}
        rowKey={"key"}
        group={{
          key: () => "general info",
          title: (groupKey) => `${groupKey}`,
        }}
        itemRender={(item) => (
          <Row wrap={false}>
            <Col span={8}>{item.key}</Col>
            <Col>{item.value}</Col>
          </Row>
        )}
        sticky
      />
    </Drawer>
  );
};
