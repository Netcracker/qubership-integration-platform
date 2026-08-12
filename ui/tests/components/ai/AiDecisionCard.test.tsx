/**
 * @jest-environment jsdom
 */

import { describe, expect, it, jest } from "@jest/globals";
import "@testing-library/jest-dom";
import { fireEvent, render, screen } from "@testing-library/react";
import { AiDecisionCard } from "../../../src/components/ai/AiDecisionCard.tsx";
import type { ChatDecision } from "../../../src/ai/modelProviders/types.ts";

function buildDecision(overrides: Partial<ChatDecision> = {}): ChatDecision {
  return {
    id: "gate-1",
    kind: "approve",
    question: "Approve the chain revision?",
    artifactType: "chain",
    artifactHash: "abc123",
    revision: 2,
    actions: ["approve", "request-changes"],
    ...overrides,
  };
}

describe("AiDecisionCard", () => {
  it("should send the clicked action with the typed comment when a button is clicked", () => {
    const onAnswer = jest.fn();
    render(<AiDecisionCard decision={buildDecision()} onAnswer={onAnswer} />);

    fireEvent.change(screen.getByPlaceholderText("Add a comment (optional)"), {
      target: { value: "Looks good to me" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Approve" }));

    expect(onAnswer).toHaveBeenCalledTimes(1);
    expect(onAnswer).toHaveBeenCalledWith("approve", "Looks good to me");
  });

  it("should render frozen with the chosen action once answered and hide the buttons", () => {
    const onAnswer = jest.fn();
    const decision = buildDecision({ answeredAction: "approve" });
    render(<AiDecisionCard decision={decision} onAnswer={onAnswer} />);

    expect(screen.getByText("Approve")).toBeInTheDocument();
    expect(
      screen.queryByPlaceholderText("Add a comment (optional)"),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Request changes" }),
    ).not.toBeInTheDocument();
  });

  it("should not send a second answer when a button is clicked twice before busy catches up", () => {
    const onAnswer = jest.fn();
    render(<AiDecisionCard decision={buildDecision()} onAnswer={onAnswer} />);

    const approveButton = screen.getByRole("button", { name: "Approve" });
    fireEvent.click(approveButton);
    fireEvent.click(approveButton);

    expect(onAnswer).toHaveBeenCalledTimes(1);
  });

  it("should disable the buttons while busy", () => {
    const onAnswer = jest.fn();
    render(<AiDecisionCard decision={buildDecision()} onAnswer={onAnswer} busy />);

    fireEvent.click(screen.getByRole("button", { name: "Approve" }));

    expect(onAnswer).not.toHaveBeenCalled();
  });
});
