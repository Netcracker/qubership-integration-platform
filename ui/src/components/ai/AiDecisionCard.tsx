import { Button, Input, List, Space, Typography } from "antd";
import React, { useRef, useState } from "react";
import type { ChatDecision } from "../../ai/modelProviders/types.ts";
import { MarkdownRenderer } from "./AiMarkdownRenderer.tsx";

/**
 * Action labels live here rather than on the wire: the question is server-authored in the language
 * of the conversation, the buttons are interface vocabulary. A new pipeline declaring a gate needs
 * no change here as long as it reuses these action names.
 */
const ACTION_LABELS: Record<string, string> = {
  approve: "Approve",
  "approve-and-create": "Approve and create chain",
  "apply-chain-patch": "Apply",
  "create-chain": "Create chain",
  "import-specification": "Import specification",
  "request-changes": "Request changes",
  yes: "Yes",
  no: "No",
  pass_through: "Pass through",
  describe_mappings: "Describe mappings",
};

/**
 * Actions the server runs as a typed command against the run. Everything else is an answer the
 * stage reads, so it travels as an ordinary message.
 */
const COMMAND_ACTIONS = new Set([
  "approve",
  "approve-and-create",
  "create-chain",
  "import-specification",
]);

/** Actions that run the primary command of their gate. */
const PRIMARY_ACTIONS = new Set([
  "approve",
  "approve-and-create",
  "apply-chain-patch",
  "create-chain",
  "import-specification",
  "yes",
  "pass_through",
]);

function actionLabel(action: string): string {
  return ACTION_LABELS[action] ?? action;
}

function commentPlaceholder(
  isMappingGapClarify: boolean,
  isFreeTextClarify: boolean,
): string {
  if (isMappingGapClarify) {
    return "One rule per line: 1: $.source -> $.target";
  }
  if (isFreeTextClarify) {
    return "Provide the missing information";
  }
  return "Add a comment (optional)";
}

function answeredLabel(decision: ChatDecision): string {
  const answered = decision.answeredAction;
  if (answered === undefined) {
    return "";
  }
  if (answered === "yes" || answered === "no" || answered === "pass_through") {
    return actionLabel(answered);
  }
  if (decision.kind === "clarify") {
    return "Sent";
  }
  return actionLabel(answered);
}

export interface AiDecisionCardProps {
  decision: ChatDecision;
  /** Invoked with the clicked action and the (possibly empty) comment. Used for kind === "approve". */
  onAnswer: (action: string, comment: string) => void;
  /**
   * Invoked with the typed text for kind === "clarify". The caller sends it as an ordinary chat
   * message rather than a decision command, since a clarification has no enumerable answer.
   * Also used when a clarify gate offers enumerable actions (for example Yes / No).
   */
  onSubmitClarification?: (text: string) => void;
  /** Disables the buttons while a request is already in flight. */
  busy?: boolean;
}

/** A gate the run stopped at, rendered inside the transcript so it stays in history. */
export const AiDecisionCard: React.FC<AiDecisionCardProps> = ({
  decision,
  onAnswer,
  onSubmitClarification,
  busy = false,
}) => {
  const isClarify = decision.kind === "clarify";
  const isMappingGapClarify =
    isClarify && decision.actions.includes("pass_through");
  const isFreeTextClarify = isClarify && decision.actions.length === 0;
  const answeredAction = decision.answeredAction;
  const [text, setText] = useState("");
  // Guards against a double click sending the answer twice before `busy` catches up.
  const clickedRef = useRef(false);
  const disabled = busy || answeredAction !== undefined;

  const handleClick = (action: string) => {
    if (disabled || clickedRef.current) return;
    if (action === "describe_mappings") {
      const trimmed = text.trim();
      if (!trimmed) return;
      clickedRef.current = true;
      onSubmitClarification?.(trimmed);
      return;
    }
    clickedRef.current = true;
    if (isClarify && !COMMAND_ACTIONS.has(action)) {
      onSubmitClarification?.(action);
      return;
    }
    onAnswer(action, text.trim());
  };

  const handleSubmitClarification = () => {
    const trimmed = text.trim();
    if (disabled || clickedRef.current || !trimmed) return;
    clickedRef.current = true;
    onSubmitClarification?.(trimmed);
  };

  const cardText = isClarify
    ? decision.reason?.trim() || decision.question.trim()
    : decision.question.trim();
  const missingEvidence = decision.missingEvidence ?? [];
  const showTextArea = isFreeTextClarify || isMappingGapClarify || !isClarify;

  return (
    <div className="ai-decision-card" data-decision-id={decision.id}>
      {cardText ? (
        <div className="ai-decision-card__question">
          {/* Server text is Markdown: numbered actions and **Adds** / **Removes** verbs. */}
          <MarkdownRenderer>{cardText}</MarkdownRenderer>
        </div>
      ) : null}

      {isClarify && missingEvidence.length > 0 ? (
        <List
          className="ai-decision-card__missing-evidence"
          size="small"
          dataSource={missingEvidence}
          renderItem={(item, index) => (
            <List.Item>
              {isMappingGapClarify ? `${index + 1}. ` : null}
              {item}
            </List.Item>
          )}
          style={{ marginBottom: 8 }}
        />
      ) : null}

      {answeredAction !== undefined ? (
        <Typography.Text type="secondary">
          {answeredLabel(decision)}
        </Typography.Text>
      ) : (
        <>
          {showTextArea ? (
            <Input.TextArea
              className="ai-decision-card__comment"
              placeholder={commentPlaceholder(
                isMappingGapClarify,
                isFreeTextClarify,
              )}
              autoSize={{ minRows: 1, maxRows: 4 }}
              value={text}
              onChange={(e) => setText(e.target.value)}
              disabled={disabled}
            />
          ) : null}
          <Space style={{ marginTop: 8 }}>
            {isFreeTextClarify ? (
              <Button
                size="small"
                type="primary"
                disabled={disabled || text.trim() === ""}
                onClick={handleSubmitClarification}
              >
                Submit
              </Button>
            ) : (
              decision.actions.map((action) => (
                <Button
                  key={action}
                  size="small"
                  type={PRIMARY_ACTIONS.has(action) ? "primary" : "default"}
                  disabled={
                    disabled ||
                    (action === "describe_mappings" && text.trim() === "")
                  }
                  onClick={() => handleClick(action)}
                >
                  {actionLabel(action)}
                </Button>
              ))
            )}
          </Space>
        </>
      )}
    </div>
  );
};
