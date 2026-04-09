import { Button, Input, Space, Tooltip, Typography } from "antd";
import type { TextAreaRef } from "antd/es/input/TextArea";
import React from "react";
import { OverridableIcon } from "../../icons/IconProvider.tsx";
import type { ChatMeta } from "./chatMessageUtils.ts";

const MAX_FILES = 5;
const MAX_FILE_BYTES = 10 * 1024 * 1024;
const ACCEPT_ATTR =
  ".txt,.md,.json,.csv,.pdf,text/plain,application/json,text/markdown,text/csv,application/pdf,image/png,image/jpeg,image/gif,image/webp";

export interface AiChatInputProps {
  fileInputRef: React.RefObject<HTMLInputElement | null>;
  inputRef: React.RefObject<TextAreaRef | null>;
  attachedFiles: File[];
  setAttachedFiles: React.Dispatch<React.SetStateAction<File[]>>;
  inputValue: string;
  setInputValue: (v: string) => void;
  textareaRows: number;
  sendKey: string;
  isLoading: boolean;
  isStreaming: boolean;
  meta: ChatMeta | null;
  onSend: () => void;
  onAbort: () => void;
}

export const AiChatInput: React.FC<AiChatInputProps> = ({
  fileInputRef,
  inputRef,
  attachedFiles,
  setAttachedFiles,
  inputValue,
  setInputValue,
  textareaRows,
  sendKey,
  isLoading,
  isStreaming,
  meta,
  onSend,
  onAbort,
}) => (
  <div className="ai-input">
    <input
      type="file"
      ref={fileInputRef as React.LegacyRef<HTMLInputElement>}
      multiple
      accept={ACCEPT_ATTR}
      style={{ display: "none" }}
      onChange={(e) => {
        const files = e.target.files ? Array.from(e.target.files) : [];
        const valid = files
          .filter((f) => f.size <= MAX_FILE_BYTES)
          .slice(0, MAX_FILES);
        setAttachedFiles((prev) => [...prev, ...valid].slice(0, MAX_FILES));
        e.target.value = "";
      }}
    />
    {attachedFiles.length > 0 && (
      <div
        className="ai-input__attachments"
        style={{
          marginBottom: 8,
          display: "flex",
          flexWrap: "wrap",
          gap: 6,
          alignItems: "center",
        }}
      >
        {attachedFiles.map((file, i) => (
          <span
            key={`${file.name}-${i}`}
            style={{
              fontSize: 12,
              padding: "2px 8px",
              background: "var(--vscode-badge-background, #eee)",
              borderRadius: 4,
              display: "inline-flex",
              alignItems: "center",
              gap: 4,
            }}
          >
            {file.name}
            <Button
              type="text"
              size="small"
              style={{ padding: 0, minWidth: 20 }}
              icon={<OverridableIcon name="close" />}
              onClick={() =>
                setAttachedFiles((prev) => prev.filter((_, j) => j !== i))
              }
              aria-label="Remove attachment"
            />
          </span>
        ))}
      </div>
    )}
    <Input.TextArea
      ref={inputRef as React.LegacyRef<TextAreaRef>}
      value={inputValue}
      onChange={(e) => setInputValue(e.target.value)}
      placeholder="Type your message..."
      rows={textareaRows}
      disabled={isLoading || isStreaming}
      onKeyDown={(e) => {
        if (e.key === sendKey && !e.shiftKey) {
          e.preventDefault();
          void onSend();
        }
      }}
    />
    <div className="ai-input__actions">
      <Space size="small">
        <Tooltip title="Attach file">
          <Button
            type="text"
            size="small"
            icon={<OverridableIcon name="paperClip" />}
            onClick={() => fileInputRef.current?.click()}
            disabled={isLoading || isStreaming}
            aria-label="Attach file"
          />
        </Tooltip>
        {meta?.usage?.totalTokens ? (
          <Typography.Text type="secondary" className="ai-meta">
            Tokens: {meta.usage.totalTokens} · {meta.durationMs}ms
          </Typography.Text>
        ) : null}
        <Button
          type="primary"
          className={
            isLoading || isStreaming
              ? "ai-send-button ai-send-button--loading"
              : "ai-send-button"
          }
          onClick={() => {
            if (isLoading || isStreaming) {
              onAbort();
            } else {
              void onSend();
            }
          }}
        >
          {(isLoading || isStreaming) && (
            <OverridableIcon name="redo" style={{ marginRight: 6 }} />
          )}
          {isLoading || isStreaming ? "Abort" : "Send"}
        </Button>
      </Space>
    </div>
  </div>
);
