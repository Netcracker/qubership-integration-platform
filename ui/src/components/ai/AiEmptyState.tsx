import { Empty, Typography } from "antd";
import React from "react";

export const AI_EMPTY_STATE_HINT =
  "Ask about this chain, a service, or an element.";

export interface AiEmptyStateProps {
  assistantName: string;
}

export const AiEmptyState: React.FC<AiEmptyStateProps> = ({
  assistantName,
}) => (
  <div className="ai-empty-state">
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
