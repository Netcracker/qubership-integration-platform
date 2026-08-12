import { Button, Input, List, Space, Typography } from "antd";
import React, { useRef, useState } from "react";
import type { ChatDecision } from "../../ai/modelProviders/types.ts";

/**
 * Action labels live here rather than on the wire: the question is server-authored in the language
 * of the conversation, the buttons are interface vocabulary. A new pipeline declaring a gate needs
 * no change here as long as it reuses these action names.
 */
const ACTION_LABELS: Record<string, string> = {
  approve: "Approve",
  "approve-and-create": "Approve and create chain",
  "create-chain": "Create chain",
  "import-specification": "Import specification",
  "request-changes": "Request changes",
};

/** Actions that run the primary command of their gate. */
const PRIMARY_ACTIONS = new Set([
  "approve",
  "approve-and-create",
  "create-chain",
  "import-specification",
]);

function actionLabel(action: string): string {
  return ACTION_LABELS[action] ?? action;
}

export interface AiDecisionCardProps {
  decision: ChatDecision;
  /** Invoked with the clicked action and the (possibly empty) comment. Used for kind === "approve". */
  onAnswer: (action: string, comment: string) => void;
  /**
   * Invoked with the typed text for kind === "clarify". The caller sends it as an ordinary chat
   * message rather than a decision command, since a clarification has no enumerable answer.
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
  const answeredAction = decision.answeredAction;
  const [text, setText] = useState("");
  // Guards against a double click sending the answer twice before `busy` catches up.
  const clickedRef = useRef(false);
  const disabled = busy || answeredAction !== undefined;

  const handleClick = (action: string) => {
    if (disabled || clickedRef.current) return;
    clickedRef.current = true;
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

  return (
    <div className="ai-decision-card" data-decision-id={decision.id}>
      {cardText ? (
        <Typography.Paragraph style={{ marginBottom: 8 }}>
          {cardText}
        </Typography.Paragraph>
      ) : null}

      {isClarify && missingEvidence.length > 0 ? (
        <List
          className="ai-decision-card__missing-evidence"
          size="small"
          dataSource={missingEvidence}
          renderItem={(item) => <List.Item>{item}</List.Item>}
          style={{ marginBottom: 8 }}
        />
      ) : null}

      {answeredAction !== undefined ? (
        <Typography.Text type="secondary">
          {isClarify ? "Sent" : actionLabel(answeredAction)}
        </Typography.Text>
      ) : (
        <>
          <Input.TextArea
            className="ai-decision-card__comment"
            placeholder={
              isClarify ? "Provide the missing information" : "Add a comment (optional)"
            }
            autoSize={{ minRows: 1, maxRows: 4 }}
            value={text}
            onChange={(e) => setText(e.target.value)}
            disabled={disabled}
          />
          <Space style={{ marginTop: 8 }}>
            {isClarify ? (
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
                  disabled={disabled}
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
