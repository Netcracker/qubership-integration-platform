/**
 * @jest-environment jsdom
 */

import { describe, it, expect } from "@jest/globals";
import "@testing-library/jest-dom";
import { act, fireEvent, render, screen } from "@testing-library/react";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { AiActivityInline } from "../../../src/components/ai/activity/AiActivityInline.tsx";
import type { ActivityStepPayload } from "../../../src/components/ai/activity/activityTypes.ts";

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

function hasDeclaration(css: string, property: string, value: string): boolean {
  return new RegExp(`(?:^|; )${property}: ${value}(?:;|$)`).test(css);
}

describe("AiActivityInline", () => {
  it("renders nothing when rows are empty", () => {
    const { container } = render(<AiActivityInline rows={[]} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("renders expanded parent and nested tool badges while running", () => {
    render(
      <AiActivityInline
        collapsed={false}
        rows={[
          {
            id: "pipeline:validate",
            kind: "pipeline",
            status: "running",
            label: "validate",
          },
          {
            id: "tool:search",
            kind: "tool",
            status: "completed",
            label: "searchCompilerKnowledge",
            parentId: "pipeline:validate",
          },
        ]}
      />,
    );

    expect(screen.getByText("validate")).toBeInTheDocument();
    expect(screen.getByText("searchCompilerKnowledge")).toBeInTheDocument();
    expect(screen.getByText("api")).toBeInTheDocument();
    expect(screen.getByText("tool")).toBeInTheDocument();
  });

  it("should show collapsed one-liner summary when collapsed is true", () => {
    render(
      <AiActivityInline
        collapsed
        summary="3 tools · 1.2s"
        rows={[
          {
            id: "pipeline:validate",
            kind: "pipeline",
            status: "completed",
            label: "validate",
          },
          {
            id: "tool:a",
            kind: "tool",
            status: "completed",
            label: "a",
            parentId: "pipeline:validate",
          },
        ]}
      />,
    );

    expect(screen.getByText("3 tools · 1.2s")).toBeInTheDocument();
    expect(screen.queryByText("validate")).not.toBeInTheDocument();
  });

  it("should expand details when collapsed summary is clicked", () => {
    render(
      <AiActivityInline
        collapsed
        summary="1 tool"
        rows={[
          {
            id: "pipeline:validate",
            kind: "pipeline",
            status: "completed",
            label: "validate",
          },
          {
            id: "tool:a",
            kind: "tool",
            status: "completed",
            label: "a",
            parentId: "pipeline:validate",
          },
        ]}
      />,
    );

    fireEvent.click(screen.getByText("1 tool"));
    expect(screen.getByText("validate")).toBeInTheDocument();
  });

  it("should render a long tool label in full so CSS can wrap it inside the bubble", () => {
    render(
      <AiActivityInline
        collapsed={false}
        rows={[
          {
            id: "skill:search",
            kind: "skill",
            status: "running",
            label: "searchSystems",
          },
          {
            id: "tool:post-search",
            kind: "tool",
            status: "running",
            label: "POST /v1/systems/search",
            parentId: "skill:search",
          },
        ]}
      />,
    );

    const label = screen.getByText("POST /v1/systems/search");
    expect(label).toHaveClass("ai-activity-inline__label");
    expect(label.closest(".ai-activity-inline__row")).toHaveClass(
      "ai-activity-inline__row",
    );
  });

  it("should render every nested tool row when seven tools are in flight", () => {
    const toolLabels = [
      "searchCatalogSystems",
      "POST /v1/systems/search",
      "searchCatalogSpecifications",
      "GET /v1/specifications",
      "searchCatalogModels",
      "GET /v1/models",
      "GET /v1/operations",
    ];
    const rows: ActivityStepPayload[] = [
      {
        id: "skill:search",
        kind: "skill",
        status: "running",
        label: "searchSystems",
      },
      ...toolLabels.map((label, index) => ({
        id: `tool:${index}`,
        kind: "tool" as const,
        status: "completed" as const,
        label,
        parentId: "skill:search",
      })),
    ];

    const { container } = render(
      <div className="ai-message__bubble">
        <AiActivityInline collapsed={false} rows={rows} />
      </div>,
    );

    expect(screen.getByText("searchSystems")).toBeInTheDocument();
    expect(screen.getByText("GET /v1/operations")).toBeInTheDocument();
    expect(container.querySelectorAll(".ai-activity-inline__row")).toHaveLength(
      8,
    );
    expect(
      container.querySelectorAll(".ai-activity-inline__badge--tool"),
    ).toHaveLength(7);
    const lastRow = container.querySelector(
      ".ai-activity-inline__children .ai-activity-inline__row:last-child",
    );
    expect(lastRow).toHaveTextContent("GET /v1/operations");
    expect(
      lastRow?.querySelector(".ai-activity-inline__badge--tool"),
    ).not.toBeNull();
  });

  it("should keep activity CSS from clipping rows and trailing badges", () => {
    const css = readFileSync(
      resolve(__dirname, "../../../src/components/ai/AiAssistantPanel.css"),
      "utf8",
    );

    const activity = declarationsFor(css, ".ai-activity-inline");
    expect(activity).not.toMatch(/max-height/);
    expect(activity).toContain("overflow: visible");
    expect(hasDeclaration(activity, "width", "100%")).toBe(true);

    const card = declarationsFor(css, ".ai-activity-inline__card");
    expect(card).toContain("overflow: visible");
    expect(card).not.toContain("overflow: hidden");
    expect(hasDeclaration(card, "width", "100%")).toBe(true);
    expect(hasDeclaration(card, "max-width", "100%")).toBe(true);
    expect(card).toMatch(/padding: 0 4px 4px/);

    const children = declarationsFor(css, ".ai-activity-inline__children");
    expect(hasDeclaration(children, "width", "100%")).toBe(true);
    expect(hasDeclaration(children, "max-width", "100%")).toBe(true);
    expect(hasDeclaration(children, "min-width", "0")).toBe(true);
    expect(hasDeclaration(children, "overflow", "hidden")).toBe(true);
    expect(hasDeclaration(children, "display", "grid")).toBe(true);
    expect(hasDeclaration(children, "box-sizing", "border-box")).toBe(true);
    expect(children).toContain("transition: grid-template-rows 0.4s ease");

    const childrenOpen = declarationsFor(
      css,
      ".ai-activity-inline__children--open",
    );
    expect(hasDeclaration(childrenOpen, "grid-template-rows", "1fr")).toBe(
      true,
    );

    const childRow = declarationsFor(css, ".ai-activity-inline__row--child");
    expect(hasDeclaration(childRow, "max-width", "100%")).toBe(true);
    expect(hasDeclaration(childRow, "box-sizing", "border-box")).toBe(true);
    expect(childRow).toMatch(/padding-left: 28px/);
    expect(childRow).toMatch(/padding-right: 10px/);

    const markdown = declarationsFor(css, ".ai-markdown");
    expect(hasDeclaration(markdown, "overflow-wrap", "anywhere")).toBe(true);

    const bubble = declarationsFor(css, ".ai-message__bubble");
    expect(bubble).toContain("overflow: visible");
    expect(bubble).not.toContain("overflow-x: hidden");
    expect(bubble).not.toMatch(/padding:/);

    const assistantBubble = declarationsFor(
      css,
      ".ai-message--assistant .ai-message__bubble",
    );
    expect(hasDeclaration(assistantBubble, "padding", "0")).toBe(true);
    expect(hasDeclaration(assistantBubble, "border", "none")).toBe(true);
    expect(hasDeclaration(assistantBubble, "background", "transparent")).toBe(
      true,
    );

    const userBubble = declarationsFor(
      css,
      ".ai-message--user .ai-message__bubble",
    );
    expect(hasDeclaration(userBubble, "border", "none")).toBe(true);
    expect(userBubble).toMatch(/padding: 8px 12px/);
    expect(userBubble).toContain("--vscode-textCodeBlock-background");

    const errorBubble = declarationsFor(
      css,
      ".ai-message--error .ai-message__bubble",
    );
    expect(errorBubble).toContain("--vscode-errorForeground");
    expect(errorBubble).toMatch(/padding: 8px 12px/);

    const activityBubble = declarationsFor(
      css,
      ".ai-message__bubble:has(.ai-activity-inline)",
    );
    expect(hasDeclaration(activityBubble, "width", "100%")).toBe(true);

    const row = declarationsFor(css, ".ai-activity-inline__row");
    expect(row).toMatch(/padding: 6px 12px 8px 8px/);
    expect(hasDeclaration(row, "max-width", "100%")).toBe(true);

    const badge = declarationsFor(css, ".ai-activity-inline__badge");
    expect(badge).toContain("flex-shrink: 0");

    const label = declarationsFor(css, ".ai-activity-inline__label");
    expect(label).toContain("overflow-wrap: anywhere");
    expect(label).toContain("white-space: normal");

    const copy = declarationsFor(css, ".ai-activity-inline__copy");
    expect(hasDeclaration(copy, "flex", "1 1 0")).toBe(true);

    const runningIcon = declarationsFor(
      css,
      ".ai-activity-inline__row--running .ai-activity-inline__icon",
    );
    expect(runningIcon).toContain("--vscode-textLink-foreground");
    expect(runningIcon).not.toContain("--vscode-testing-iconFailed");

    const aiBadge = declarationsFor(css, ".ai-activity-inline__badge--ai");
    expect(aiBadge).toContain("--vscode-textLink-foreground");
    expect(aiBadge).not.toContain("--vscode-testing-iconFailed");
    expect(aiBadge).not.toContain("--vscode-charts-orange");
  });

  it("should nest tool rows inside the skill card so CSS can share its width", () => {
    const { container } = render(
      <div className="ai-message__bubble">
        <AiActivityInline
          collapsed={false}
          rows={[
            {
              id: "skill:req",
              kind: "skill",
              status: "completed",
              label: "cip-requirement-analyzer",
            },
            {
              id: "tool:search",
              kind: "tool",
              status: "completed",
              label: "POST /v1/systems/search",
              parentId: "skill:req",
            },
            {
              id: "tool:draft",
              kind: "tool",
              status: "completed",
              label: "captureRequirementDraft",
              parentId: "skill:req",
            },
          ]}
        />
      </div>,
    );

    const card = container.querySelector(".ai-activity-inline__card");
    const nestedRows = container.querySelectorAll(
      ".ai-activity-inline__card .ai-activity-inline__children .ai-activity-inline__row--child",
    );
    expect(card).not.toBeNull();
    expect(nestedRows).toHaveLength(2);
    expect(nestedRows[0]).toHaveTextContent("POST /v1/systems/search");
    expect(
      card?.querySelector(".ai-activity-inline__badge--skill"),
    ).not.toBeNull();
    expect(
      nestedRows[0]?.querySelector(".ai-activity-inline__badge--tool"),
    ).not.toBeNull();
  });

  it("should show a completed mark on an earlier skill once a later skill is running", () => {
    render(
      <AiActivityInline
        collapsed={false}
        rows={[
          {
            id: "skill:brainstorming",
            kind: "skill",
            status: "running",
            label: "brainstorming",
          },
          {
            id: "tool:draft",
            kind: "tool",
            status: "completed",
            label: "captureRequirementDraft",
            parentId: "skill:brainstorming",
          },
          {
            id: "skill:analyzer",
            kind: "skill",
            status: "running",
            label: "cip-requirement-analyzer",
          },
        ]}
      />,
    );

    const brainstorming = screen
      .getByText("brainstorming")
      .closest(".ai-activity-inline__row");
    const analyzer = screen
      .getByText("cip-requirement-analyzer")
      .closest(".ai-activity-inline__row");
    expect(
      brainstorming?.querySelector(".ai-activity-inline__spinner"),
    ).toBeNull();
    expect(brainstorming).toHaveClass("ai-activity-inline__row--completed");
    expect(
      analyzer?.querySelector(".ai-activity-inline__spinner"),
    ).not.toBeNull();
  });

  it("should omit a parent chevron when the skill has no nested tools", () => {
    render(
      <AiActivityInline
        collapsed={false}
        rows={[
          {
            id: "skill:materialization",
            kind: "skill",
            status: "completed",
            label: "materialization",
          },
        ]}
      />,
    );

    expect(screen.getByText("materialization")).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: /materialization/i }),
    ).not.toBeInTheDocument();
    expect(document.querySelector(".ai-activity-inline__chevron")).toBeNull();
  });

  it("should nest a running llm pass under the active skill with an AI badge", () => {
    render(
      <AiActivityInline
        collapsed={false}
        rows={[
          {
            id: "skill:cip-design-planner",
            kind: "skill",
            status: "running",
            label: "Planning the implementation",
          },
          {
            id: "llm:rate-limit-backoff",
            kind: "llm",
            status: "running",
            label: "Taking another pass",
            parentId: "skill:cip-design-planner",
          },
        ]}
      />,
    );

    expect(screen.getByText(/Taking another pass/)).toBeInTheDocument();
    expect(screen.getByText("AI")).toBeInTheDocument();
    const llmRow = screen
      .getByText(/Taking another pass/)
      .closest(".ai-activity-inline__row");
    expect(llmRow).toHaveClass("ai-activity-inline__row--running");
    expect(llmRow).not.toHaveClass("ai-activity-inline__row--error");
    expect(
      llmRow?.querySelector(".ai-activity-inline__spinner"),
    ).not.toBeNull();
    expect(llmRow?.querySelector(".ai-thinking-dots")).not.toBeNull();
  });

  it("should replace the llm label with Ready to continue when the pass completes", () => {
    const { rerender } = render(
      <AiActivityInline
        collapsed={false}
        rows={[
          {
            id: "skill:cip-design-planner",
            kind: "skill",
            status: "running",
            label: "Planning the implementation",
          },
          {
            id: "llm:rate-limit-backoff",
            kind: "llm",
            status: "running",
            label: "Taking another pass",
            parentId: "skill:cip-design-planner",
          },
        ]}
      />,
    );

    rerender(
      <AiActivityInline
        collapsed={false}
        rows={[
          {
            id: "skill:cip-design-planner",
            kind: "skill",
            status: "running",
            label: "Planning the implementation",
          },
          {
            id: "llm:rate-limit-backoff",
            kind: "llm",
            status: "completed",
            label: "Ready to continue",
            parentId: "skill:cip-design-planner",
          },
        ]}
      />,
    );

    expect(screen.getByText("Ready to continue")).toBeInTheDocument();
    expect(screen.queryByText(/Taking another pass/)).not.toBeInTheDocument();
    const llmRow = screen
      .getByText("Ready to continue")
      .closest(".ai-activity-inline__row");
    expect(llmRow).toHaveClass("ai-activity-inline__row--completed");
    expect(llmRow).not.toHaveClass("ai-activity-inline__row--error");
  });

  it("should collapse a completed skill once a later skill is running", () => {
    render(
      <AiActivityInline
        collapsed={false}
        rows={[
          {
            id: "skill:chain-semantic-design",
            kind: "skill",
            status: "completed",
            label: "Capturing the chain design",
          },
          {
            id: "tool:library",
            kind: "tool",
            status: "completed",
            label: "Loading an element type",
            parentId: "skill:chain-semantic-design",
          },
          {
            id: "skill:cip-design-planner",
            kind: "skill",
            status: "running",
            label: "Planning the implementation",
          },
        ]}
      />,
    );

    const capturing = screen.getByRole("button", {
      name: /Capturing the chain design/i,
    });
    const panel = capturing
      .closest(".ai-activity-inline__card")
      ?.querySelector(".ai-activity-inline__children");
    expect(capturing).toHaveAttribute("aria-expanded", "false");
    expect(panel).not.toHaveClass("ai-activity-inline__children--open");
    expect(panel).toHaveAttribute("aria-hidden", "true");
    expect(screen.getByText("Loading an element type")).toBeInTheDocument();
  });

  it("should expand a collapsed completed skill when its row is clicked", () => {
    render(
      <AiActivityInline
        collapsed={false}
        rows={[
          {
            id: "skill:chain-semantic-design",
            kind: "skill",
            status: "completed",
            label: "Capturing the chain design",
          },
          {
            id: "tool:library",
            kind: "tool",
            status: "completed",
            label: "Loading an element type",
            parentId: "skill:chain-semantic-design",
          },
          {
            id: "skill:cip-design-planner",
            kind: "skill",
            status: "running",
            label: "Planning the implementation",
          },
        ]}
      />,
    );

    const capturing = screen.getByRole("button", {
      name: /Capturing the chain design/i,
    });
    fireEvent.click(capturing);
    expect(capturing).toHaveAttribute("aria-expanded", "true");
    expect(
      capturing
        .closest(".ai-activity-inline__card")
        ?.querySelector(".ai-activity-inline__children"),
    ).toHaveClass("ai-activity-inline__children--open");
    expect(screen.getByText("Loading an element type")).toBeInTheDocument();
  });

  it("should show a wait hint after eight seconds on a running llm step", () => {
    jest.useFakeTimers();
    render(
      <AiActivityInline
        collapsed={false}
        rows={[
          {
            id: "skill:cip-design-planner",
            kind: "skill",
            status: "running",
            label: "Planning the implementation",
          },
          {
            id: "llm:rate-limit-backoff",
            kind: "llm",
            status: "running",
            label: "Taking another pass",
            parentId: "skill:cip-design-planner",
          },
        ]}
      />,
    );

    expect(
      screen.queryByText("Rocky is still working. No action is needed."),
    ).not.toBeInTheDocument();
    act(() => {
      jest.advanceTimersByTime(8000);
    });
    expect(
      screen.getByText("Rocky is still working. No action is needed."),
    ).toBeInTheDocument();
    jest.useRealTimers();
  });

  it("should hide the wait hint when the llm step completes", () => {
    jest.useFakeTimers();
    const { rerender } = render(
      <AiActivityInline
        collapsed={false}
        rows={[
          {
            id: "skill:cip-design-planner",
            kind: "skill",
            status: "running",
            label: "Planning the implementation",
          },
          {
            id: "llm:rate-limit-backoff",
            kind: "llm",
            status: "running",
            label: "Taking another pass",
            parentId: "skill:cip-design-planner",
          },
        ]}
      />,
    );

    act(() => {
      jest.advanceTimersByTime(8000);
    });
    rerender(
      <AiActivityInline
        collapsed={false}
        rows={[
          {
            id: "skill:cip-design-planner",
            kind: "skill",
            status: "running",
            label: "Planning the implementation",
          },
          {
            id: "llm:rate-limit-backoff",
            kind: "llm",
            status: "completed",
            label: "Ready to continue",
            parentId: "skill:cip-design-planner",
          },
        ]}
      />,
    );
    expect(
      screen.queryByText("Rocky is still working. No action is needed."),
    ).not.toBeInTheDocument();
    jest.useRealTimers();
  });

  it("should show Thinking below the list when the turn is in flight and every row looks finished", () => {
    render(
      <AiActivityInline
        collapsed={false}
        inFlight
        rows={[
          {
            id: "skill:cip-design-planner",
            kind: "skill",
            status: "completed",
            label: "Planning the implementation",
          },
          {
            id: "tool:library",
            kind: "tool",
            status: "completed",
            label: "Loading an element type",
            parentId: "skill:cip-design-planner",
          },
        ]}
      />,
    );

    const thinking = document.querySelector(".ai-activity-inline__thinking");
    expect(thinking).toHaveClass("ai-activity-inline__thinking--open");
    expect(thinking).toHaveAttribute("aria-hidden", "false");
    expect(thinking).toHaveTextContent("Thinking");
    expect(thinking?.querySelector(".ai-thinking-dots")).not.toBeNull();
    expect(
      document.querySelector(".ai-activity-inline__row--continuation"),
    ).toBeNull();
  });

  it("should hide Thinking while a skill still looks in progress", () => {
    render(
      <AiActivityInline
        collapsed={false}
        inFlight
        rows={[
          {
            id: "skill:cip-design-planner",
            kind: "skill",
            status: "running",
            label: "Planning the implementation",
          },
          {
            id: "tool:library",
            kind: "tool",
            status: "completed",
            label: "Loading an element type",
            parentId: "skill:cip-design-planner",
          },
        ]}
      />,
    );

    const thinking = document.querySelector(".ai-activity-inline__thinking");
    expect(thinking).not.toHaveClass("ai-activity-inline__thinking--open");
    expect(thinking).toHaveAttribute("aria-hidden", "true");
  });

  it("should hide Thinking while a tool or llm step is running", () => {
    render(
      <AiActivityInline
        collapsed={false}
        inFlight
        rows={[
          {
            id: "skill:cip-design-planner",
            kind: "skill",
            status: "running",
            label: "Planning the implementation",
          },
          {
            id: "tool:library",
            kind: "tool",
            status: "running",
            label: "Loading an element type",
            parentId: "skill:cip-design-planner",
          },
        ]}
      />,
    );

    const thinking = document.querySelector(".ai-activity-inline__thinking");
    expect(thinking).not.toHaveClass("ai-activity-inline__thinking--open");
    expect(thinking).toHaveAttribute("aria-hidden", "true");
  });

  it("should not show Thinking when inFlight is false", () => {
    render(
      <AiActivityInline
        collapsed={false}
        rows={[
          {
            id: "skill:cip-design-planner",
            kind: "skill",
            status: "completed",
            label: "Planning the implementation",
          },
          {
            id: "tool:library",
            kind: "tool",
            status: "completed",
            label: "Loading an element type",
            parentId: "skill:cip-design-planner",
          },
        ]}
      />,
    );

    const thinking = document.querySelector(".ai-activity-inline__thinking");
    expect(thinking).not.toHaveClass("ai-activity-inline__thinking--open");
    expect(thinking).toHaveAttribute("aria-hidden", "true");
  });

  it("should show Taking another pass instead of an error mark while the turn is in flight", () => {
    render(
      <AiActivityInline
        collapsed={false}
        inFlight
        rows={[
          {
            id: "skill:chain-semantic-design",
            kind: "skill",
            status: "error",
            label: "Capturing the chain design",
          },
          {
            id: "tool:captureChainSemanticRevision",
            kind: "tool",
            status: "completed",
            label: "Capturing chain semantic revision",
            parentId: "skill:chain-semantic-design",
          },
        ]}
      />,
    );

    const skillRow = screen
      .getByText("Capturing the chain design")
      .closest(".ai-activity-inline__row");
    expect(skillRow).toHaveClass("ai-activity-inline__row--running");
    expect(skillRow).not.toHaveClass("ai-activity-inline__row--error");
    expect(
      skillRow?.querySelector(".ai-activity-inline__spinner"),
    ).not.toBeNull();
    expect(screen.getByText(/Taking another pass/)).toBeInTheDocument();
    expect(screen.getByText("AI")).toBeInTheDocument();
    expect(
      document.querySelector(".ai-activity-inline__thinking--open"),
    ).toBeNull();
    const passRow = screen
      .getByText(/Taking another pass/)
      .closest(".ai-activity-inline__row");
    expect(passRow).toHaveClass("ai-activity-inline__row--pass");
    expect(passRow).toHaveClass("ai-activity-inline__row--running");
    expect(
      passRow?.querySelector(".ai-activity-inline__spinner"),
    ).not.toBeNull();
    expect(passRow?.querySelector(".ai-thinking-dots")).not.toBeNull();
  });

  it("should keep the error mark after the turn ends", () => {
    render(
      <AiActivityInline
        collapsed={false}
        rows={[
          {
            id: "skill:chain-semantic-design",
            kind: "skill",
            status: "error",
            label: "Capturing the chain design",
          },
        ]}
      />,
    );

    const skillRow = screen
      .getByText("Capturing the chain design")
      .closest(".ai-activity-inline__row");
    expect(skillRow).toHaveClass("ai-activity-inline__row--error");
    expect(screen.queryByText(/Taking another pass/)).not.toBeInTheDocument();
  });
});
