/**
 * @jest-environment jsdom
 */

import { describe, expect, it } from "@jest/globals";
import "@testing-library/jest-dom";
import { render, screen } from "@testing-library/react";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import {
  AI_EMPTY_STATE_HINT,
  AiEmptyState,
} from "../../../src/components/ai/AiEmptyState.tsx";

function declarationsFor(css: string, selector: string): string {
  const rulePattern = /([^{}]+)\{([^{}]*)\}/g;
  const stylesheet = css.replace(/\/\*[\s\S]*?\*\//g, "");

  for (const match of stylesheet.matchAll(rulePattern)) {
    if (match[1].split(",").some((item) => item.trim() === selector)) {
      return match[2].replace(/\s+/g, " ").trim();
    }
  }

  return "";
}

describe("AiEmptyState", () => {
  it("should show the assistant name and a secondary hint when the chat is empty", () => {
    render(<AiEmptyState assistantName="Rocky" />);

    expect(screen.getByRole("heading", { name: "Rocky" })).toBeInTheDocument();
    expect(screen.getByText(AI_EMPTY_STATE_HINT)).toBeInTheDocument();
    expect(screen.queryAllByRole("button")).toHaveLength(0);
    expect(
      screen.queryByRole("button", { name: "Explain this chain" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Find a service" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Create an integration" }),
    ).not.toBeInTheDocument();
  });

  it("should use the configured assistant name as the empty-state heading", () => {
    render(<AiEmptyState assistantName="Atlas" />);

    expect(screen.getByRole("heading", { name: "Atlas" })).toBeInTheDocument();
    expect(
      screen.queryByRole("heading", { name: "Rocky" }),
    ).not.toBeInTheDocument();
  });

  it("should color the empty-state title and hint with vscode tokens", () => {
    const css = readFileSync(
      resolve(__dirname, "../../../src/components/ai/AiAssistantPanel.css"),
      "utf8",
    );
    const title = declarationsFor(css, ".ai-empty-state__title.ant-typography");
    const hint = declarationsFor(
      css,
      ".ai-empty-state .ant-typography.ant-typography-secondary",
    );

    expect(title).toContain("--vscode-foreground");
    expect(hint).toContain("--vscode-descriptionForeground");
  });
});
