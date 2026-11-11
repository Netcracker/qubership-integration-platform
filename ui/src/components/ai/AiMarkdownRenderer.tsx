import { Button } from "antd";
import React, { useMemo } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { Prism as SyntaxHighlighter } from "react-syntax-highlighter";
import type { Components } from "react-markdown";
import {
  extractMarkdownText,
  isCollapsibleChainPlanJsonBlock,
} from "./chatMessageUtils.ts";
import { CollapsibleChainPlanJsonBlock } from "./CollapsibleChainPlanJsonBlock.tsx";
import { AiMermaidDiagram } from "./AiMermaidDiagram.tsx";

const markdownComponents: Components = {
  code({ children }) {
    return <code className="ai-inline-code">{children}</code>;
  },
  pre({ children }) {
    const element = React.Children.toArray(children)[0];
    if (
      !React.isValidElement<{ className?: string; children?: React.ReactNode }>(
        element,
      )
    ) {
      return <pre>{children}</pre>;
    }
    const raw = extractMarkdownText(element.props.children);
    const code = raw.endsWith("\n") ? raw.slice(0, -1) : raw;
    const language =
      element.props.className
        ?.split(" ")
        .find((value) => value.startsWith("language-"))
        ?.slice("language-".length) || "text";
    if (isCollapsibleChainPlanJsonBlock(language, code)) {
      return (
        <CollapsibleChainPlanJsonBlock
          code={code}
          language={language === "chain-plan-json" ? "chain-plan-json" : "json"}
        />
      );
    }
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
        {language === "mermaid" ? (
          <AiMermaidDiagram code={code} />
        ) : language === "text" ? (
          <pre>
            <code>{code}</code>
          </pre>
        ) : (
          <SyntaxHighlighter language={language} PreTag="div">
            {code}
          </SyntaxHighlighter>
        )}
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
