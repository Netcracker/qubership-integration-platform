import React from "react";
import {
  CloseOutlined,
  ExclamationCircleOutlined,
  CloseCircleOutlined,
} from "@ant-design/icons";
import { List } from "antd";
import { NotificationItem } from "./contexts/NotificationLogContext.tsx";

type NotificationBarElementProps = {
  value: NotificationItem;
  onRemove: (value: NotificationItem) => void;
};

export const NotificationBarElement: React.FC<NotificationBarElementProps> = ({
  value,
  onRemove,
}) => {
  const getIconByType = (type?: string) => {
    const style = { fontSize: "18", marginTop: 4 };
    switch (type) {
      case "error":
        return <CloseCircleOutlined style={{ ...style, color: "#ff4d4f" }} />;
      case "info":
      default:
        return (
          <ExclamationCircleOutlined style={{ ...style, color: "#1890ff" }} />
        );
    }
  };

  return (
    <List.Item
      actions={[
        <CloseOutlined
          key="close"
          style={{ cursor: "pointer" }}
          onClick={() => onRemove(value)}
        />,
      ]}
    >
      <List.Item.Meta
        avatar={getIconByType(value.type)}
        title={value.message}
        description={value.description}
      />
    </List.Item>
  );
};
