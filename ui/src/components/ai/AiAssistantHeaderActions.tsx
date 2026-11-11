import { Button, Space } from "antd";
import React from "react";
import { OverridableIcon } from "../../icons/IconProvider.tsx";
import { confirmAndRun } from "../../misc/confirm-utils.ts";

export interface AiAssistantHeaderActionsProps {
  onNewChat: () => void;
  onClearChat: () => void | Promise<void>;
  clearDisabled: boolean;
}

export const AiAssistantHeaderActions: React.FC<
  AiAssistantHeaderActionsProps
> = ({ onNewChat, onClearChat, clearDisabled }) => (
  <Space size="small">
    <Button
      size="small"
      icon={<OverridableIcon name="plus" />}
      onClick={onNewChat}
      title="New chat"
      aria-label="New chat"
    />
    <Button
      size="small"
      onClick={() => {
        confirmAndRun({
          title: "Clear this chat?",
          okText: "Clear",
          onOk: onClearChat,
        });
      }}
      disabled={clearDisabled}
    >
      Clear
    </Button>
  </Space>
);
