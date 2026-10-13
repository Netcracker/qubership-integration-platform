import { Typography } from "antd";
import type { ReactNode } from "react";
import type { ChatRole } from "../../ai/modelProviders/types.ts";

export const AiChatMessage = ({
  role,
  label,
  error = false,
  children,
  actions,
}: {
  role: ChatRole;
  label: string;
  error?: boolean;
  children: ReactNode;
  actions?: ReactNode;
}) => (
  <div
    className={`ai-message ai-message--${role}${error ? " ai-message--error" : ""}`}
  >
    {role !== "user" && (
      <div className="ai-message__meta">
        <Typography.Text type="secondary" className="ai-message__role">
          {label}
        </Typography.Text>
      </div>
    )}
    <div className="ai-message__bubble">{children}</div>
    {actions}
  </div>
);
