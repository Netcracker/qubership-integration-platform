/**
 * @jest-environment node
 */

import { describe, expect, it } from "@jest/globals";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const PANEL_CSS = resolve(
  __dirname,
  "../../../src/components/ai/AiAssistantPanel.css",
);
const ASSISTANT_TSX = resolve(
  __dirname,
  "../../../src/components/ai/AiAssistant.tsx",
);

function declarationsFor(css: string, selector: string): string {
  const rulePattern = /([^{}]+)\{([^{}]*)\}/g;
  const stylesheet = css.replace(/\/\*[\s\S]*?\*\//g, "");
  const normalizedSelector = selector.replace(/\s+/g, " ").trim();

  for (const match of stylesheet.matchAll(rulePattern)) {
    if (
      match[1]
        .split(",")
        .some((item) => item.trim().replace(/\s+/g, " ") === normalizedSelector)
    ) {
      return match[2].replace(/\s+/g, " ").trim();
    }
  }

  return "";
}

function stripCssVars(css: string): string {
  let result = css.replace(/\/\*[\s\S]*?\*\//g, "");
  let start = result.lastIndexOf("var(");
  while (start !== -1) {
    let depth = 0;
    let end = -1;
    for (let index = start; index < result.length; index += 1) {
      const character = result[index];
      if (character === "(") {
        depth += 1;
      } else if (character === ")") {
        depth -= 1;
        if (depth === 0) {
          end = index;
          break;
        }
      }
    }
    if (end === -1) {
      break;
    }
    result = result.slice(0, start) + result.slice(end + 1);
    start = result.lastIndexOf("var(");
  }
  return result;
}

describe("AiAssistantPanel.css polish contracts", () => {
  const css = readFileSync(PANEL_CSS, "utf8");
  const assistantSource = readFileSync(ASSISTANT_TSX, "utf8");

  it("should observe message-list content size and disable scroll anchoring", () => {
    expect(assistantSource).toContain("useChatStickToBottom");
    expect(assistantSource).toContain("ai-message-list__content");

    const list = declarationsFor(css, ".ai-message-list");
    expect(list).toContain("overflow-anchor: none");

    const content = declarationsFor(css, ".ai-message-list__content");
    expect(content).toContain("min-height: 100%");
  });

  it("should right-align Copy and Regenerate on assistant turns", () => {
    expect(assistantSource).toContain('aria-label="Copy"');
    expect(assistantSource).toContain('aria-label="Regenerate this answer"');
    expect(assistantSource).toContain('className="ai-message__actions"');

    const actions = declarationsFor(
      css,
      ".ai-message--assistant .ai-message__actions",
    );
    expect(actions).toContain("justify-content: flex-end");
    expect(actions).toContain("width: 100%");
    expect(actions).toContain("display: flex");
  });

  it("should color empty-state, bubbles, and high-contrast user turns with vscode tokens", () => {
    expect(
      declarationsFor(css, ".ai-empty-state__title.ant-typography"),
    ).toContain("--vscode-foreground");
    expect(
      declarationsFor(
        css,
        ".ai-empty-state .ant-typography.ant-typography-secondary",
      ),
    ).toContain("--vscode-descriptionForeground");

    const userBubble = declarationsFor(
      css,
      ".ai-message--user .ai-message__bubble",
    );
    expect(userBubble).toContain("--vscode-list-inactiveSelectionBackground");
    expect(userBubble).toContain("--vscode-textCodeBlock-background");

    const highContrastUserBubble = declarationsFor(
      css,
      ':root:not(.vscode-webview)[data-theme="high-contrast"] .ai-message--user .ai-message__bubble',
    );
    expect(highContrastUserBubble).toContain("--vscode-list-hoverBackground");

    const errorBubble = declarationsFor(
      css,
      ".ai-message--error .ai-message__bubble",
    );
    expect(errorBubble).toContain("--vscode-errorForeground");
    expect(errorBubble).toContain("--vscode-editorWidget-background");
  });

  it("should keep every painted color on a vscode token", () => {
    const withoutTokens = stripCssVars(css);
    expect(withoutTokens).not.toMatch(/#[0-9a-fA-F]{3,8}\b/);
    expect(withoutTokens).not.toMatch(/\brgba?\(/);
  });
});
