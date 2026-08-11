/**
 * @jest-environment jsdom
 */

import { describe, it, expect } from "@jest/globals";
import "@testing-library/jest-dom";
import { fireEvent, render, screen } from "@testing-library/react";
import { AiActivityInline } from "../../../src/components/ai/activity/AiActivityInline.tsx";

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
});
