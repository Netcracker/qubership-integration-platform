import React from "react";
import {
    CloseOutlined,
    ExclamationCircleOutlined,
    CloseCircleOutlined,
} from "@ant-design/icons";
import {List} from "antd";
import {ArgsProps} from "antd/es/notification";

type NotificationBarElementProps = {
    props: ArgsProps,
    onRemove: (props: ArgsProps) => void
}

export const NotificationBarElement: React.FC<NotificationBarElementProps> = ({props, onRemove}) => {

    const getIconByType = (type?: string) => {
        const style = {fontSize: "18", marginTop: 4};
        switch (type) {
            case "error":
                return <CloseCircleOutlined style={{...style, color: "#ff4d4f"}}/>;
            case "info":
            default:
                return <ExclamationCircleOutlined style={{...style, color: "#1890ff"}}/>;
        }
    };

    return (
        <List.Item
            actions={[
                <CloseOutlined
                    key="close"
                    style={{cursor: "pointer"}}
                    onClick={() => onRemove(props)}
                />
            ]}
        >
            <List.Item.Meta
                avatar={getIconByType(props.type)}
                title={props.message}
                description={props.description}
            />
        </List.Item>);
}
