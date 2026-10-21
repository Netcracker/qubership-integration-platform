import { Button } from "antd";
import React, { useState } from "react";
import { Prism as SyntaxHighlighter } from "react-syntax-highlighter";

type CollapsibleChainPlanJsonBlockProps = {
  code: string;
  language: string;
};

function CollapsibleChainPlanJsonBlockInner({
  code,
  language,
}: CollapsibleChainPlanJsonBlockProps) {
  const [collapsed, setCollapsed] = useState(true);

  return (
    <div className="ai-execution-log ai-chain-plan-json">
      <button
        type="button"
        className="ai-execution-log__header"
        onClick={() => setCollapsed((current) => !current)}
        aria-expanded={!collapsed}
      >
        <span className="ai-execution-log__title">
          Chain implementation plan (JSON)
        </span>
        <span className="ai-execution-log__chevron">
          {collapsed ? "▶" : "▼"}
        </span>
      </button>
      {!collapsed && (
        <div className="ai-code-block">
          <div className="ai-code-block__header">
            <span className="ai-code-block__lang">{language}</span>
            <Button
              size="small"
              type="text"
              className="ai-code-block__copy"
              onClick={() => {
                void navigator.clipboard?.writeText(code);
              }}
            >
              Copy
            </Button>
          </div>
          <SyntaxHighlighter language="json" PreTag="div">
            {code}
          </SyntaxHighlighter>
        </div>
      )}
    </div>
  );
}

export const CollapsibleChainPlanJsonBlock = React.memo(
  CollapsibleChainPlanJsonBlockInner,
);
