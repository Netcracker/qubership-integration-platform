import { Button } from "antd";
import React, { useMemo } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { Prism as SyntaxHighlighter } from "react-syntax-highlighter";
import type { Components } from "react-markdown";
import { extractMarkdownText } from "./chatMessageUtils.ts";

const markdownComponents: Components = {
  code(props) {
    const { children, className, ...rest } = props;
    const match = /language-(\w+)/.exec(className || "");
    const code = extractMarkdownText(children).replace(/\n$/, "");

    if (!match) {
      return (
        <code className="ai-inline-code" {...rest}>
          {children}
        </code>
      );
    }

    const language = match[1] || "text";
    return (
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
        <SyntaxHighlighter language={language} PreTag="div">
          {code}
        </SyntaxHighlighter>
      </div>
    );
  },
};

const MarkdownRendererInner: React.FC<{ children: string }> = ({
  children,
}) => {
  const normalizedContent = useMemo(
    () => (children ? String(children) : ""),
    [children],
  );

  return (
    <div className="ai-markdown">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={markdownComponents}
      >
        {normalizedContent}
      </ReactMarkdown>
    </div>
  );
};

export const MarkdownRenderer = React.memo(
  MarkdownRendererInner,
  (prev, next) => {
    return prev.children === next.children;
  },
);
