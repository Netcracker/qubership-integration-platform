import { Empty, Typography } from "antd";
import React, { useEffect, useState } from "react";

export const AI_EMPTY_STATE_HINT =
  "Ask about this chain, a service, or an element.";

export const CHAT_MOTION_MS = 400;

export interface AiEmptyStateProps {
  assistantName: string;
  visible?: boolean;
}

function useChatPresence(visible: boolean): {
  rendered: boolean;
  exiting: boolean;
} {
  const [rendered, setRendered] = useState(visible);
  const [exiting, setExiting] = useState(false);

  useEffect(() => {
    if (visible) {
      setRendered(true);
      setExiting(false);
      return undefined;
    }
    if (!rendered) {
      return undefined;
    }
    setExiting(true);
    const timer = window.setTimeout(() => {
      setRendered(false);
      setExiting(false);
    }, CHAT_MOTION_MS);
    return () => window.clearTimeout(timer);
  }, [visible, rendered]);

  return { rendered, exiting };
}

export const AiEmptyState: React.FC<AiEmptyStateProps> = ({
  assistantName,
  visible = true,
}) => {
  const { rendered, exiting } = useChatPresence(visible);
  if (!rendered) {
    return null;
  }
  return (
    <div
      className={`ai-empty-state${exiting ? " ai-chat-motion-out" : ""}`}
      aria-hidden={exiting}
    >
      <Empty
        image={Empty.PRESENTED_IMAGE_SIMPLE}
        description={
          <>
            <Typography.Title level={5} className="ai-empty-state__title">
              {assistantName}
            </Typography.Title>
            <Typography.Text type="secondary">
              {AI_EMPTY_STATE_HINT}
            </Typography.Text>
          </>
        }
      />
    </div>
  );
};
