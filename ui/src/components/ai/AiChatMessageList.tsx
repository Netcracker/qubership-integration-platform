import { Button, Tooltip, Typography } from "antd";
import React from "react";
import type { ChatMessage } from "../../ai/modelProviders/types.ts";
import { OverridableIcon } from "../../icons/IconProvider.tsx";
import { MarkdownRenderer } from "./AiMarkdownRenderer.tsx";
import {
  collapseProgressLines,
  parseProgressLines,
  type ProgressLineStatus,
  stripInlineProgressSummary,
  stripProgressBlocks,
} from "./aiProgressParsing.ts";
import {
  lastUserMessageIsAgree,
  looksLikePlanResponse,
  looksLikeValidationResult,
  replaceChainModificationProposalForDisplay,
} from "./chainModificationContent.ts";
import { getRoleLabel } from "./chatMessageUtils.ts";
import { ExecutionLog } from "./ExecutionLog.tsx";
import { WORKING_DOTS } from "./aiAssistantConstants.ts";
import type { ChainContext } from "./useChainContext.ts";

export interface AiChatMessageListProps {
  visibleMessages: ChatMessage[];
  isLoading: boolean;
  isStreaming: boolean;
  assistantName: string;
  workingDots: number;
  chainContext: ChainContext | null;
  showLongRunningHint: boolean;
  scrollContainerRef: React.RefObject<HTMLDivElement | null>;
  onScroll: () => void;
  onExecutePlan: () => void;
  onPrepareRegenerate: (messageIndex: number) => void;
  onRegenerate: (messageIndex: number) => void;
}

export const AiChatMessageList: React.FC<AiChatMessageListProps> = ({
  visibleMessages,
  isLoading,
  isStreaming,
  assistantName,
  workingDots,
  chainContext,
  showLongRunningHint,
  scrollContainerRef,
  onScroll,
  onExecutePlan,
  onPrepareRegenerate,
  onRegenerate,
}) => (
  <div
    ref={scrollContainerRef as React.LegacyRef<HTMLDivElement>}
    className="ai-message-list"
    onScroll={onScroll}
  >
    {visibleMessages.length === 0 ? (
      <div className="ai-empty-state">
        <Typography.Text type="secondary">
          Ask a question about QIP, chains, services, or elements.
        </Typography.Text>
      </div>
    ) : (
      <>
        {visibleMessages.map((message, index) => {
          if (
            message.role === "assistant" &&
            !message.content.trim() &&
            (isLoading || isStreaming)
          ) {
            return null;
          }

          const isUser = message.role === "user";
          const rawLines =
            message.role === "assistant"
              ? parseProgressLines(message.content)
              : [];
          let progressLines =
            message.role === "assistant" ? collapseProgressLines(rawLines) : [];
          const isLastAssistant =
            index === visibleMessages.length - 1 &&
            message.role === "assistant";
          if (isLastAssistant && (isLoading || isStreaming)) {
            const lastLine = progressLines[progressLines.length - 1];
            if (lastLine?.status !== "pending") {
              progressLines = [
                ...progressLines,
                {
                  text: `Working${WORKING_DOTS[workingDots]}`,
                  status: "pending" as ProgressLineStatus,
                },
              ];
            }
          }

          const hasProgressLog = progressLines.length > 0;
          const narrativeContent =
            message.role === "assistant"
              ? stripInlineProgressSummary(
                  replaceChainModificationProposalForDisplay(
                    stripProgressBlocks(message.content),
                  ),
                )
              : message.content;

          return (
            <div
              key={message.id || `msg-${index}`}
              className={`ai-message ai-message--${message.role}`}
            >
              <div className="ai-message__meta">
                <span className="ai-message__role">
                  {getRoleLabel(message.role, assistantName)}
                </span>
              </div>
              <div className="ai-message__bubble">
                {hasProgressLog && (
                  <ExecutionLog
                    lines={progressLines}
                    title="Steps"
                    defaultCollapsed={false}
                  />
                )}
                {narrativeContent.trim() ? (
                  <MarkdownRenderer>{narrativeContent}</MarkdownRenderer>
                ) : null}

                {message.role === "assistant" &&
                  index === visibleMessages.length - 1 &&
                  !isLoading &&
                  !isStreaming &&
                  looksLikePlanResponse(message.content) &&
                  !lastUserMessageIsAgree(visibleMessages) && (
                    <div
                      className="ai-message__plan-actions"
                      style={{
                        marginTop: 14,
                        paddingTop: 12,
                        borderTop: "1px solid var(--vscode-border, #eee)",
                      }}
                    >
                      <Typography.Text
                        type="secondary"
                        style={{
                          display: "block",
                          marginBottom: 8,
                          fontSize: 12,
                        }}
                      >
                        Ready to execute?
                      </Typography.Text>
                      <Button
                        type="primary"
                        size="middle"
                        onClick={() => void onExecutePlan()}
                      >
                        Execute plan
                      </Button>
                    </div>
                  )}

                {message.role === "assistant" &&
                  index === visibleMessages.length - 1 &&
                  !isLoading &&
                  !isStreaming &&
                  looksLikeValidationResult(message.content) &&
                  chainContext?.chain?.id && (
                    <div
                      className="ai-message__plan-actions"
                      style={{
                        marginTop: 14,
                        paddingTop: 12,
                        borderTop: "1px solid var(--vscode-border, #eee)",
                      }}
                    >
                      <Button
                        type="primary"
                        size="middle"
                        onClick={() => {
                          window.location.href = `/chains/${chainContext.chain.id}/sessions`;
                        }}
                      >
                        Go to Sessions
                      </Button>
                    </div>
                  )}

                {isUser && (
                  <div className="ai-message__actions">
                    <Tooltip title="Edit message and send again">
                      <Button
                        size="small"
                        type="text"
                        icon={<OverridableIcon name="edit" />}
                        onClick={() => onPrepareRegenerate(index)}
                      />
                    </Tooltip>
                    <Tooltip title="Regenerate from this answer">
                      <Button
                        size="small"
                        type="text"
                        icon={
                          <OverridableIcon
                            name="redo"
                            className="ai-icon-rotate-vertical"
                          />
                        }
                        onClick={() => void onRegenerate(index)}
                      />
                    </Tooltip>
                  </div>
                )}
              </div>
            </div>
          );
        })}

        {(isLoading || isStreaming) &&
          (() => {
            const lastMessage = visibleMessages[visibleMessages.length - 1];
            const shouldShowThinking =
              lastMessage?.role === "user" ||
              (lastMessage?.role === "assistant" &&
                !lastMessage.content.trim());
            if (!shouldShowThinking) return null;
            return (
              <div className="ai-message ai-message--assistant">
                <div className="ai-message__meta">
                  <span className="ai-message__role">
                    {getRoleLabel("assistant", assistantName)}
                  </span>
                </div>
                <div className="ai-message__bubble">
                  <Typography.Text
                    type="secondary"
                    style={{ fontStyle: "italic" }}
                  >
                    {showLongRunningHint
                      ? "Working… (this may take a minute)"
                      : "Thinking"}
                    <span className="ai-thinking-dots">
                      <span className="ai-thinking-dot ai-thinking-dot--1">
                        .
                      </span>
                      <span className="ai-thinking-dot ai-thinking-dot--2">
                        .
                      </span>
                      <span className="ai-thinking-dot ai-thinking-dot--3">
                        .
                      </span>
                    </span>
                  </Typography.Text>
                </div>
              </div>
            );
          })()}
      </>
    )}
  </div>
);
