import React, { useState } from "react";
import type { ParsedProgressLine } from "./aiProgressParsing.ts";

function ExecutionLogInner({
  lines,
  defaultCollapsed = false,
  title = "Execution log",
}: {
  lines: ParsedProgressLine[];
  defaultCollapsed?: boolean;
  title?: string;
}) {
  const [collapsed, setCollapsed] = useState(defaultCollapsed);
  if (lines.length === 0) return null;
  return (
    <div className="ai-execution-log">
      <button
        type="button"
        className="ai-execution-log__header"
        onClick={() => setCollapsed((c) => !c)}
        aria-expanded={!collapsed}
      >
        <span className="ai-execution-log__title">{title}</span>
        <span className="ai-execution-log__count">
          {lines.length} step{lines.length !== 1 ? "s" : ""}
        </span>
        <span className="ai-execution-log__chevron">
          {collapsed ? "▶" : "▼"}
        </span>
      </button>
      {!collapsed && (
        <div className="ai-execution-log__body" role="log">
          {lines.map((line, i) => (
            <div
              key={i}
              className={`ai-execution-log__line ai-execution-log__line--${line.status}`}
              title={line.text}
            >
              <span className="ai-execution-log__step-num">{i + 1}</span>
              <span className="ai-execution-log__icon">
                {line.status === "success" && "✓"}
                {line.status === "error" && "✗"}
                {line.status === "pending" && "○"}
              </span>
              <span className="ai-execution-log__text">{line.text}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

export const ExecutionLog = React.memo(ExecutionLogInner);
