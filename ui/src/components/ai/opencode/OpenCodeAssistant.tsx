import {
  Alert,
  Button,
  Collapse,
  Divider,
  Drawer,
  Form,
  Input,
  Select,
  Space,
  Typography,
} from "antd";
import type { TextAreaRef } from "antd/es/input/TextArea";
import { useEffect, useRef, useState } from "react";
import { getConfig, onConfigChange } from "../../../appConfig.ts";
import { useOpenCodeChat } from "../../../ai/opencode/useOpenCodeChat.ts";
import { OverridableIcon } from "../../../icons/IconProvider.tsx";
import { AiChatComposer } from "../AiChatComposer.tsx";
import { AiAssistantHeaderActions } from "../AiAssistantHeaderActions.tsx";
import { useAiDrawerResize } from "../useAiDrawerResize.ts";
import { useChatStickToBottom } from "../useChatStickToBottom.ts";
import { OpenCodeTranscript } from "./OpenCodeTranscript.tsx";
import {
  OpenCodePermissionCard,
  OpenCodeQuestionCard,
} from "./OpenCodeCards.tsx";
import "../AiAssistantPanel.css";

type ConnectionFields = { url: string; username: string; password: string };

export const OpenCodeAssistant = () => {
  const [open, setOpen] = useState(false);
  const [form] = Form.useForm<ConnectionFields>();
  const chat = useOpenCodeChat(open);
  const resize = useAiDrawerResize(open, "opencode-assistant-drawer-width");
  const scroll = useChatStickToBottom();
  const [inputValue, setInputValue] = useState("");
  const [attachedFiles, setAttachedFiles] = useState<File[]>([]);
  const [fileError, setFileError] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const inputRef = useRef<TextAreaRef>(null);

  useEffect(
    () =>
      onConfigChange((config) => {
        if (config.opencodeUrl !== undefined)
          form.setFieldValue("url", config.opencodeUrl);
      }),
    [form],
  );
  useEffect(() => {
    setInputValue("");
    setAttachedFiles([]);
    setFileError(null);
  }, [chat.sessionID]);

  async function send() {
    setFileError(null);
    scroll.resumeFollowing();
    if (await chat.send(inputValue, attachedFiles)) {
      setInputValue((current) => (current === inputValue ? "" : current));
      setAttachedFiles((current) =>
        current.filter((file) => !attachedFiles.includes(file)),
      );
    }
  }

  return (
    <>
      <Button
        type="text"
        aria-label="OpenCode"
        title="OpenCode"
        style={{ fontSize: 18, color: "inherit" }}
        icon={<OverridableIcon name="code" />}
        onClick={() => setOpen(true)}
      />
      <Drawer
        open={open}
        onClose={() => setOpen(false)}
        placement="right"
        width={resize.drawerWidth}
        zIndex={2000}
        rootClassName="ai-assistant-drawer opencode-assistant-drawer"
        afterOpenChange={(visible) => {
          if (visible) scroll.pinToBottom();
        }}
        title={
          <Space style={{ display: "flex", justifyContent: "space-between" }}>
            <span>OpenCode</span>
            <AiAssistantHeaderActions
              onNewChat={() => void chat.newSession()}
              onClearChat={chat.clear}
              clearDisabled={!chat.ready || chat.busy || !chat.sessionID}
              newChatDisabled={!chat.ready || chat.busy}
            />
          </Space>
        }
      >
        <div
          className={`ai-drawer-resize-handle ${resize.isResizing ? "resizing" : ""}`}
          onMouseDown={resize.onResizeMouseDown}
        />
        <div className="ai-chat-root">
          <Collapse
            size="small"
            defaultActiveKey={["connection"]}
            items={[
              {
                key: "connection",
                label: chat.ready
                  ? `Connected to ${chat.serverUrl}`
                  : "OpenCode connection",
                children: (
                  <Form
                    form={form}
                    layout="vertical"
                    initialValues={{
                      url: getConfig().opencodeUrl || "http://127.0.0.1:4096",
                      username: "opencode",
                      password: "",
                    }}
                    onFinish={({ url, username, password }) =>
                      void chat.connect(url, username, password)
                    }
                  >
                    <Form.Item
                      name="url"
                      label="Server URL"
                      rules={[
                        {
                          required: true,
                          message: "Enter the OpenCode server URL.",
                        },
                      ]}
                    >
                      <Input
                        placeholder="http://127.0.0.1:4096"
                        disabled={chat.connecting}
                      />
                    </Form.Item>
                    <Form.Item name="username" label="Username">
                      <Input autoComplete="off" disabled={chat.connecting} />
                    </Form.Item>
                    <Form.Item name="password" label="Password (if required)">
                      <Input.Password
                        autoComplete="off"
                        disabled={chat.connecting}
                      />
                    </Form.Item>
                    <Button htmlType="submit" loading={chat.connecting}>
                      {chat.connected ? "Reconnect" : "Connect"}
                    </Button>
                  </Form>
                ),
              },
            ]}
          />
          {(chat.error || fileError) && (
            <Alert type="error" showIcon title={chat.error || fileError} />
          )}
          {chat.connected && (
            <>
              <Select
                aria-label="OpenCode conversation"
                value={chat.sessionID}
                disabled={!chat.ready || chat.busy}
                options={chat.sessions.map((s) => ({
                  value: s.id,
                  label: s.title,
                }))}
                onChange={chat.selectSession}
                className="ai-session-switcher"
                popupMatchSelectWidth={false}
              />
              <div
                ref={scroll.scrollContainerRef}
                className="ai-message-list"
                onScroll={scroll.onScroll}
              >
                <div ref={scroll.contentRef}>
                  <OpenCodeTranscript messages={chat.messages} />
                  {chat.permissions.map((request) => (
                    <OpenCodePermissionCard
                      key={request.id}
                      request={request}
                      disabled={!chat.ready}
                      onReply={chat.replyPermission}
                    />
                  ))}
                  {chat.questions.map((request) => (
                    <OpenCodeQuestionCard
                      key={request.id}
                      request={request}
                      disabled={!chat.ready}
                      onReply={chat.replyQuestion}
                    />
                  ))}
                  {chat.busy && (
                    <Typography.Text type="secondary">
                      OpenCode is working…
                    </Typography.Text>
                  )}
                </div>
              </div>
              <Divider className="ai-divider" />
              <AiChatComposer
                fileInputRef={fileInputRef}
                inputRef={inputRef}
                inputValue={inputValue}
                setInputValue={setInputValue}
                attachedFiles={attachedFiles}
                setAttachedFiles={setAttachedFiles}
                placeholder="Ask OpenCode…"
                isTurnInFlight={chat.busy}
                disabled={!chat.ready || !chat.sessionID}
                onSend={() => void send()}
                onAbort={() => void chat.abort()}
                onFilesRejected={() =>
                  setFileError(
                    "Attach up to five files, each no larger than 10 MB.",
                  )
                }
              />
            </>
          )}
        </div>
      </Drawer>
    </>
  );
};
