/**
 * @jest-environment jsdom
 */

import { describe, it, expect } from "@jest/globals";
import "@testing-library/jest-dom";
import { fireEvent, render, screen } from "@testing-library/react";
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
    expect(label.parentElement).toHaveClass("ai-activity-inline__row");
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
    expect(hasDeclaration(children, "overflow", "visible")).toBe(true);
    expect(hasDeclaration(children, "box-sizing", "border-box")).toBe(true);

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
    expect(card?.querySelector(".ai-activity-inline__badge--skill")).not.toBeNull();
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
    expect(brainstorming?.querySelector(".ai-activity-inline__spinner")).toBeNull();
    expect(brainstorming).toHaveClass("ai-activity-inline__row--completed");
    expect(analyzer?.querySelector(".ai-activity-inline__spinner")).not.toBeNull();
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
});
