import { Badge, Button, Drawer, List } from "antd";
import { BellOutlined } from "@ant-design/icons";
import React, { useState } from "react";
import {
  NotificationItem,
  useNotificationLog,
} from "./contexts/NotificationLogContext.tsx";
import { NotificationBarElement } from "./NotificationBarElement.tsx";

export const NotificationBar: React.FC = () => {
  const [open, setOpen] = useState(false);
  const notificationLogData = useNotificationLog();
  const { clearHistory, removeFromHistory } = useNotificationLog();

  const showDrawer = () => {
    setOpen(true);
  };

  const onClose = () => {
    setOpen(false);
  };

  const clearAll = () => {
    clearHistory();
  };

  const removeNotification = (notification: NotificationItem) => {
    removeFromHistory(notification);
  };

  return (
    <Badge offset={[-8, 8]} count={notificationLogData.history.length}>
      <Button
        type="text"
        style={{ fontSize: "18px" }}
        icon={<BellOutlined />}
        onClick={showDrawer}
      />
      <Drawer
        title="Notifications"
        placement="right"
        open={open}
        closable={false}
        onClose={onClose}
        extra={
          <Button type="default" onClick={clearAll}>
            Clear All
          </Button>
        }
      >
        <List
          dataSource={notificationLogData.history}
          renderItem={(item) => (
            <NotificationBarElement
              value={item}
              onRemove={removeNotification}
            />
          )}
        ></List>
      </Drawer>
    </Badge>
  );
};
