import {Badge, Button, Drawer, List} from "antd";
import { BellOutlined,} from "@ant-design/icons";
import styles from "../Navigation.module.css";
import React, {useState} from "react";
import {useNotificationLog} from "./contexts/NotificationLogContext.tsx";
import {ArgsProps} from "antd/es/notification";
import {NotificationBarElement} from "./NotificationBarElement.tsx";


export const NotificationBar: React.FC = () => {
    const [open, setOpen] = useState(false);
    const notificationLogData = useNotificationLog();
    const {clearHistory, removeFromHistory} = useNotificationLog();


    const showDrawer = () => {
        setOpen(true);
    };

    const onClose = () => {
        setOpen(false);
    };

    const clearAll = () => {
        clearHistory();
    }

    const removeNotification = (notificationConfig: ArgsProps) => {
        removeFromHistory(notificationConfig);
    }


    return <Badge count={notificationLogData.history.length}>
        <Button
            type="default"
            icon={<BellOutlined />}
            onClick={showDrawer}
            className={styles.button}
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
                        props={item}
                        onRemove={removeNotification}
                    />
                )}
            >
            </List>
        </Drawer>
    </Badge>
}
