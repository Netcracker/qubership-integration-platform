import { Collapse, Image, Tag, Typography } from "antd";
import type { ToolPart } from "@opencode-ai/sdk/v2/client";
import type { OpenCodeMessage } from "../../../ai/opencode/messages.ts";
import { chatError } from "../../../ai/opencode/client.ts";
import { AiChatMessage } from "../AiChatMessage.tsx";
import { MarkdownRenderer } from "../AiMarkdownRenderer.tsx";
import { AiActivityInline } from "../activity/AiActivityInline.tsx";

export const OpenCodeTranscript = ({
  messages,
}: {
  messages: OpenCodeMessage[];
}) => (
  <>
    {messages.length === 0 && (
      <Typography.Text type="secondary">
        Start a conversation with OpenCode.
      </Typography.Text>
    )}
    {messages.map(({ id, info, parts }) => {
      if (!info) return null;
      const tools = parts.filter((p): p is ToolPart => p.type === "tool");
      return (
        <AiChatMessage
          key={id}
          role={info.role}
          label="OpenCode"
          error={info.role === "assistant" && Boolean(info.error)}
        >
          {parts.map((part) => {
            if (part.type === "text" && !part.synthetic && !part.ignored)
              return (
                <MarkdownRenderer key={part.id}>{part.text}</MarkdownRenderer>
              );
            if (part.type !== "file") return null;
            const inline = part.url.startsWith(`data:${part.mime};base64,`);
            return (
              <div key={part.id}>
                <Tag>
                  {inline ? (
                    <a href={part.url} download={part.filename ?? "attachment"}>
                      {part.filename ?? "Attachment"}
                    </a>
                  ) : (
                    (part.filename ?? "Attachment")
                  )}
                </Tag>
                {inline && /^image\/(png|jpeg|gif|webp)$/.test(part.mime) && (
                  <Image
                    src={part.url}
                    alt={part.filename ?? "Attached image"}
                    width={160}
                  />
                )}
              </div>
            );
          })}
          {tools.length > 0 && (
            <>
              <AiActivityInline
                rows={tools.map((p) => ({
                  id: p.callID,
                  kind: "tool",
                  label: "title" in p.state ? p.state.title || p.tool : p.tool,
                  status:
                    p.state.status === "pending" ? "running" : p.state.status,
                }))}
              />
              <Collapse
                size="small"
                items={tools
                  .filter(
                    (p) =>
                      p.state.status === "completed" ||
                      p.state.status === "error",
                  )
                  .map((p) => ({
                    key: p.id,
                    label: p.tool,
                    children: (
                      <Typography.Paragraph
                        style={{
                          whiteSpace: "pre-wrap",
                          overflowWrap: "anywhere",
                        }}
                      >
                        {p.state.status === "completed"
                          ? p.state.output
                          : p.state.status === "error"
                            ? p.state.error
                            : ""}
                      </Typography.Paragraph>
                    ),
                  }))}
              />
            </>
          )}
          {info.role === "assistant" && info.error && (
            <Typography.Text type="danger">
              {chatError(info.error)}
            </Typography.Text>
          )}
        </AiChatMessage>
      );
    })}
  </>
);
