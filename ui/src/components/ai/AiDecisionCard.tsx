import { Button, Input, Space, Typography } from "antd";
import React from "react";
import type { ChatDecision } from "../../ai/modelProviders/types.ts";

/**
 * Action labels live here rather than on the wire: the question is server-authored in the language
 * of the conversation, the buttons are interface vocabulary. A new pipeline declaring a gate needs
 * no change here as long as it reuses these action names.
 */
const ACTION_LABELS: Record<string, string> = {
  approve: "Approve",
  "request-changes": "Request changes",
};

function actionLabel(action: string): string {
  return ACTION_LABELS[action] ?? action;
}

export interface AiDecisionCardProps {
  decision: ChatDecision;
}

/** A gate the run stopped at, rendered inside the transcript so it stays in history. */
export const AiDecisionCard: React.FC<AiDecisionCardProps> = ({ decision }) => {
  const answeredAction = decision.answeredAction;

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
            disabled
          />
          <Space style={{ marginTop: 8 }}>
            {decision.actions.map((action) => (
              <Button
                key={action}
                size="small"
                type={action === "approve" ? "primary" : "default"}
                // ponytail: ticket 04 wires the click; disabled until then so the card cannot lie.
                disabled
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
