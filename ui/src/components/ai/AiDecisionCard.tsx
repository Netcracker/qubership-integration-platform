import { Button, Input, Space, Typography } from "antd";
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
  "request-changes": "Request changes",
};

/** Actions that run the primary command of their gate. */
const PRIMARY_ACTIONS = new Set(["approve", "approve-and-create", "create-chain"]);

function actionLabel(action: string): string {
  return ACTION_LABELS[action] ?? action;
}

export interface AiDecisionCardProps {
  decision: ChatDecision;
  /** Invoked with the clicked action and the (possibly empty) comment. */
  onAnswer: (action: string, comment: string) => void;
  /** Disables the buttons while a request is already in flight. */
  busy?: boolean;
}

/** A gate the run stopped at, rendered inside the transcript so it stays in history. */
export const AiDecisionCard: React.FC<AiDecisionCardProps> = ({
  decision,
  onAnswer,
  busy = false,
}) => {
  const answeredAction = decision.answeredAction;
  const [comment, setComment] = useState("");
  // Guards against a double click sending the answer twice before `busy` catches up.
  const clickedRef = useRef(false);
  const disabled = busy || answeredAction !== undefined;

  const handleClick = (action: string) => {
    if (disabled || clickedRef.current) return;
    clickedRef.current = true;
    onAnswer(action, comment.trim());
  };

  return (
    <div className="ai-decision-card" data-decision-id={decision.id}>
      {decision.question.trim() ? (
        <Typography.Paragraph style={{ marginBottom: 8 }}>
          {decision.question}
        </Typography.Paragraph>
      ) : null}

      {answeredAction !== undefined ? (
        <Typography.Text type="secondary">
          {actionLabel(answeredAction)}
        </Typography.Text>
      ) : (
        <>
          <Input.TextArea
            className="ai-decision-card__comment"
            placeholder="Add a comment (optional)"
            autoSize={{ minRows: 1, maxRows: 4 }}
            value={comment}
            onChange={(e) => setComment(e.target.value)}
            disabled={disabled}
          />
          <Space style={{ marginTop: 8 }}>
            {decision.actions.map((action) => (
              <Button
                key={action}
                size="small"
                type={PRIMARY_ACTIONS.has(action) ? "primary" : "default"}
                disabled={disabled}
                onClick={() => handleClick(action)}
              >
                {actionLabel(action)}
              </Button>
            ))}
          </Space>
        </>
      )}
    </div>
  );
};
