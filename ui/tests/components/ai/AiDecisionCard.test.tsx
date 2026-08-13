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

  it("should render the import action as a primary button and send it on click", () => {
    const onAnswer = jest.fn();
    render(
      <AiDecisionCard
        decision={buildDecision({
          id: "import:pkg.geosite",
          question: "Import the API Hub specification GeoSite API into the runtime catalog?",
          artifactType: undefined,
          artifactHash: undefined,
          revision: 0,
          actions: ["import-specification"],
        })}
        onAnswer={onAnswer}
      />,
    );

    const importButton = screen.getByRole("button", { name: "Import specification" });
    expect(importButton.className).toMatch(/ant-btn-primary/);
    fireEvent.click(importButton);

    expect(onAnswer).toHaveBeenCalledTimes(1);
    expect(onAnswer).toHaveBeenCalledWith("import-specification", "");
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

  it("should render the reason, the missing-evidence items, and no action buttons for a clarify card", () => {
    const onAnswer = jest.fn();
    const decision = buildDecision({
      kind: "clarify",
      reason: "The run needs the target service name before it can continue.",
      question: "",
      missingEvidence: ["Target service name", "Target operation id"],
      actions: [],
    });
    render(<AiDecisionCard decision={decision} onAnswer={onAnswer} />);

    expect(
      screen.getByText(
        "The run needs the target service name before it can continue.",
      ),
    ).toBeInTheDocument();
    expect(screen.getByText("Target service name")).toBeInTheDocument();
    expect(screen.getByText("Target operation id")).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Approve" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Request changes" }),
    ).not.toBeInTheDocument();
  });

  it("should fall back to the question when reason is empty for a clarify card", () => {
    const onAnswer = jest.fn();
    const decision = buildDecision({
      kind: "clarify",
      reason: "",
      question: "Which environment should this target?",
      missingEvidence: [],
      actions: [],
    });
    render(<AiDecisionCard decision={decision} onAnswer={onAnswer} />);

    expect(
      screen.getByText("Which environment should this target?"),
    ).toBeInTheDocument();
  });

  it("should keep the submit button disabled until text is typed for a clarify card", () => {
    const onAnswer = jest.fn();
    const onSubmitClarification = jest.fn();
    const decision = buildDecision({
      kind: "clarify",
      reason: "Missing evidence.",
      missingEvidence: [],
      actions: [],
    });
    render(
      <AiDecisionCard
        decision={decision}
        onAnswer={onAnswer}
        onSubmitClarification={onSubmitClarification}
      />,
    );

    const submitButton = screen.getByRole("button", { name: "Submit" });
    expect(submitButton).toBeDisabled();

    fireEvent.change(
      screen.getByPlaceholderText("Provide the missing information"),
      { target: { value: "It targets the billing service." } },
    );
    expect(submitButton).not.toBeDisabled();
  });

  it("should call onSubmitClarification with the typed text when submit is clicked", () => {
    const onAnswer = jest.fn();
    const onSubmitClarification = jest.fn();
    const decision = buildDecision({
      kind: "clarify",
      reason: "Missing evidence.",
      missingEvidence: [],
      actions: [],
    });
    render(
      <AiDecisionCard
        decision={decision}
        onAnswer={onAnswer}
        onSubmitClarification={onSubmitClarification}
      />,
    );

    fireEvent.change(
      screen.getByPlaceholderText("Provide the missing information"),
      { target: { value: "It targets the billing service." } },
    );
    fireEvent.click(screen.getByRole("button", { name: "Submit" }));

    expect(onAnswer).not.toHaveBeenCalled();
    expect(onSubmitClarification).toHaveBeenCalledTimes(1);
    expect(onSubmitClarification).toHaveBeenCalledWith(
      "It targets the billing service.",
    );
  });

  it("should render Yes and No buttons for an IDS path-choice clarify gate", () => {
    const onAnswer = jest.fn();
    const onSubmitClarification = jest.fn();
    const decision = buildDecision({
      kind: "clarify",
      reason:
        "Would you like an integration design document (IDS) for your approved requirements?",
      missingEvidence: [],
      actions: ["yes", "no"],
    });
    render(
      <AiDecisionCard
        decision={decision}
        onAnswer={onAnswer}
        onSubmitClarification={onSubmitClarification}
      />,
    );

    expect(
      screen.queryByPlaceholderText("Provide the missing information"),
    ).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Yes" }));

    expect(onAnswer).not.toHaveBeenCalled();
    expect(onSubmitClarification).toHaveBeenCalledWith("yes");
  });

  it("should send a command action through onAnswer even on a clarify card", () => {
    // The import gate is a clarification the server can execute: it has to arrive as a typed
    // command so the transcript records the marker the import stage reads. A stage answer such as
    // "yes" travels as an ordinary message instead.
    const onAnswer = jest.fn();
    const onSubmitClarification = jest.fn();
    const decision = buildDecision({
      kind: "clarify",
      reason: "Import the API Hub specification into the runtime catalog before planning?",
      missingEvidence: [],
      actions: ["import-specification"],
    });
    render(
      <AiDecisionCard
        decision={decision}
        onAnswer={onAnswer}
        onSubmitClarification={onSubmitClarification}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: "Import specification" }));

    expect(onSubmitClarification).not.toHaveBeenCalled();
    expect(onAnswer).toHaveBeenCalledWith("import-specification", "");
  });

  it("should render Pass through and Describe mappings for a mapping-gap clarify gate", () => {
    const onAnswer = jest.fn();
    const onSubmitClarification = jest.fn();
    const decision = buildDecision({
      kind: "clarify",
      reason: "Some data mappings are still missing before design can continue.",
      missingEvidence: [
        'INITIALIZATION: ENDPOINT "GET /orders" → SERVICE_CALL "Outbound call call-1"',
      ],
      actions: ["pass_through", "describe_mappings"],
    });
    render(
      <AiDecisionCard
        decision={decision}
        onAnswer={onAnswer}
        onSubmitClarification={onSubmitClarification}
      />,
    );

    expect(
      screen.getByText(
        'INITIALIZATION: ENDPOINT "GET /orders" → SERVICE_CALL "Outbound call call-1"',
      ),
    ).toBeInTheDocument();
    expect(
      screen.getByPlaceholderText(
        "Describe field mappings (sourcePath to targetPath)",
      ),
    ).toBeInTheDocument();

    const describe = screen.getByRole("button", { name: "Describe mappings" });
    expect(describe).toBeDisabled();

    const passThrough = screen.getByRole("button", { name: "Pass through" });
    expect(passThrough.className).toMatch(/ant-btn-primary/);
    fireEvent.click(passThrough);

    expect(onAnswer).not.toHaveBeenCalled();
    expect(onSubmitClarification).toHaveBeenCalledWith("pass_through");
  });

  it("should submit described mappings text when Describe mappings is clicked", () => {
    const onSubmitClarification = jest.fn();
    const decision = buildDecision({
      kind: "clarify",
      reason: "Some data mappings are still missing before design can continue.",
      missingEvidence: [],
      actions: ["pass_through", "describe_mappings"],
    });
    render(
      <AiDecisionCard
        decision={decision}
        onAnswer={jest.fn()}
        onSubmitClarification={onSubmitClarification}
      />,
    );

    fireEvent.change(
      screen.getByPlaceholderText(
        "Describe field mappings (sourcePath to targetPath)",
      ),
      { target: { value: "$.id → $.customerId" } },
    );
    fireEvent.click(screen.getByRole("button", { name: "Describe mappings" }));

    expect(onSubmitClarification).toHaveBeenCalledWith("$.id → $.customerId");
  });

  it("should freeze the clarify card and hide the text area once submitted", () => {
    const onAnswer = jest.fn();
    const decision = buildDecision({
      kind: "clarify",
      reason: "Missing evidence.",
      missingEvidence: [],
      actions: [],
      answeredAction: "clarify",
    });
    render(<AiDecisionCard decision={decision} onAnswer={onAnswer} />);

    expect(screen.getByText("Sent")).toBeInTheDocument();
    expect(
      screen.queryByPlaceholderText("Provide the missing information"),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Submit" }),
    ).not.toBeInTheDocument();
  });
});
