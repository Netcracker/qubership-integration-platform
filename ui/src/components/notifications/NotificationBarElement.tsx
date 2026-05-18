import React from "react";
import { List } from "antd";
import { NotificationItem } from "./contexts/NotificationLogContext.tsx";
import { OverridableIcon } from "../../icons/IconProvider.tsx";

type NotificationBarElementProps = {
  value: NotificationItem;
  onRemove: (value: NotificationItem) => void;
};

export const NotificationBarElement: React.FC<NotificationBarElementProps> = ({
  value,
  onRemove,
}) => {
  const getIconByType = (type?: string) => {
    const baseStyle = { fontSize: "18", marginTop: 4 };
    const errorColor =
      getComputedStyle(document.documentElement)
        .getPropertyValue("--vscode-errorForeground")
        .trim() || "#ff4d4f";
    const infoColor =
      getComputedStyle(document.documentElement)
        .getPropertyValue("--vscode-textLink-foreground")
        .trim() || "#1890ff";
    const warningColor =
      getComputedStyle(document.documentElement)
        .getPropertyValue("--vscode-warningForeground")
        .trim() || "#faad14";

    switch (type) {
      case "error":
        return (
          <OverridableIcon
            name="closeCircle"
            style={{ ...baseStyle, color: errorColor }}
          />
        );
      case "warning":
        return (
          <OverridableIcon
            name="exclamationCircle"
            style={{ ...baseStyle, color: warningColor }}
          />
        );
      case "info":
      default:
        return (
          <OverridableIcon
            name="info"
            style={{ ...baseStyle, color: infoColor }}
          />
        );
    }
  };

  return (
    <List.Item
      actions={[
        <OverridableIcon
          name="close"
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
