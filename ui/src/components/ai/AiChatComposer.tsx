import { Input, Tag } from "antd";
import type { TextAreaRef } from "antd/es/input/TextArea";
import type { Dispatch, RefObject, SetStateAction } from "react";
import { AiComposerActions } from "./AiComposerActions.tsx";
import { SEND_KEY } from "./aiAssistantConstants.ts";

interface AiChatComposerProps {
  fileInputRef: RefObject<HTMLInputElement>;
  inputRef: RefObject<TextAreaRef>;
  inputValue: string;
  setInputValue: (value: string) => void;
  attachedFiles: File[];
  setAttachedFiles: Dispatch<SetStateAction<File[]>>;
  placeholder: string;
  isTurnInFlight: boolean;
  disabled?: boolean;
  onSend: () => void;
  onAbort: () => void;
  onFilesRejected?: () => void;
}

export const AiChatComposer = ({
  fileInputRef,
  inputRef,
  inputValue,
  setInputValue,
  attachedFiles,
  setAttachedFiles,
  placeholder,
  isTurnInFlight,
  disabled = false,
  onSend,
  onAbort,
  onFilesRejected,
}: AiChatComposerProps) => (
  <div className="ai-input">
    <input
      type="file"
      ref={fileInputRef}
      multiple
      accept=".txt,.md,.json,.yaml,.yml,.csv,.pdf,text/plain,application/json,text/yaml,application/x-yaml,text/markdown,text/csv,application/pdf,image/png,image/jpeg,image/gif,image/webp"
      style={{ display: "none" }}
      onChange={(e) => {
        const files = e.target.files ? Array.from(e.target.files) : [];
        const valid = files
          .filter((f) => f.size <= 10 * 1024 * 1024)
          .slice(0, 5);
        if (
          valid.length !== files.length ||
          attachedFiles.length + valid.length > 5
        )
          onFilesRejected?.();
        setAttachedFiles((prev) => [...prev, ...valid].slice(0, 5));
        e.target.value = "";
      }}
    />
    {attachedFiles.length > 0 && (
      <div className="ai-input__attachments">
        {attachedFiles.map((file, i) => (
          <Tag
            key={`${file.name}-${i}`}
            closable
            onClose={(event) => {
              event.preventDefault();
              setAttachedFiles((prev) => prev.filter((_, j) => j !== i));
            }}
          >
            {file.name}
          </Tag>
        ))}
      </div>
    )}
    <Input.TextArea
      ref={inputRef}
      value={inputValue}
      onChange={(e) => setInputValue(e.target.value)}
      onInput={(e) => setInputValue(e.currentTarget.value)}
      placeholder={placeholder}
      autoSize={{ minRows: 1, maxRows: 8 }}
      onKeyDown={(e) => {
        if (e.key === SEND_KEY && !e.shiftKey) {
          e.preventDefault();
          if (!isTurnInFlight && !disabled) onSend();
        }
      }}
    />
    <AiComposerActions
      isTurnInFlight={isTurnInFlight}
      disabled={disabled}
      onAttach={() => fileInputRef.current?.click()}
      onSend={onSend}
      onAbort={onAbort}
    />
  </div>
);
