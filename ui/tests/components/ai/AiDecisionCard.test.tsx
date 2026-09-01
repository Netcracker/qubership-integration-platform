/**
 * @jest-environment jsdom
 */

import { describe, expect, it, jest } from "@jest/globals";
import "@testing-library/jest-dom";
import { fireEvent, render, screen } from "@testing-library/react";
import type { ReactElement, ReactNode } from "react";
import { AiDecisionCard } from "../../../src/components/ai/AiDecisionCard.tsx";
import type { ChatDecision } from "../../../src/ai/modelProviders/types.ts";

jest.mock("../../../src/components/ai/AiMarkdownRenderer.tsx", () => {
  // eslint-disable-next-line @typescript-eslint/no-require-imports -- jest.mock factory runs before imports
  const R = require("react") as typeof import("react");

  function withBold(text: string): ReactNode[] {
    return text.split(/(\*\*[^*]+\*\*)/g).map((part, index) => {
      const match = /^\*\*(.+)\*\*$/.exec(part);
      return match ? R.createElement("strong", { key: index }, match[1]) : part;
    });
  }

  return {
    MarkdownRenderer: ({ children }: { children: string }) => {
      const listItems: ReactElement[] = [];
      const body: ReactNode[] = [];
      const flushList = () => {
        if (listItems.length === 0) {
          return;
        }
        body.push(
          R.createElement(
            "ol",
            { key: `ol-${body.length}` },
            listItems.splice(0),
          ),
        );
      };
      for (const line of String(children).split("\n")) {
        const item = /^(\d+)\.\s+(.*)$/.exec(line);
        if (item) {
          listItems.push(
            R.createElement("li", { key: item[1] }, withBold(item[2])),
          );
        } else if (line.trim() === "") {
          flushList();
        } else {
          flushList();
          body.push(
            R.createElement("p", { key: `p-${body.length}` }, withBold(line)),
          );
        }
      }
      flushList();
      return R.createElement("div", { className: "ai-markdown" }, body);
    },
  };
});

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
          question:
            "Import the API Hub specification GeoSite API into the runtime catalog?",
          artifactType: undefined,
          artifactHash: undefined,
          revision: 0,
          actions: ["import-specification"],
        })}
        onAnswer={onAnswer}
      />,
    );

    const importButton = screen.getByRole("button", {
      name: "Import specification",
    });
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
    render(
      <AiDecisionCard decision={buildDecision()} onAnswer={onAnswer} busy />,
    );

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

  it("should not list missing-evidence items that already appear as the clarify reason", () => {
    const onAnswer = jest.fn();
    const question =
      "What should the new integration chain do? Please provide its trigger (for example, HTTP method and path), expected response or business outcome, and any downstream service calls.";
    const decision = buildDecision({
      kind: "clarify",
      reason: question,
      question,
      missingEvidence: [question],
      actions: [],
    });
    render(<AiDecisionCard decision={decision} onAnswer={onAnswer} />);

    expect(screen.getAllByText(question)).toHaveLength(1);
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

  it("should render Retry for a stage-retry clarify gate", () => {
    const onAnswer = jest.fn();
    const onSubmitClarification = jest.fn();
    const decision = buildDecision({
      kind: "clarify",
      reason: "bad domain",
      missingEvidence: [],
      actions: ["retry"],
    });
    render(
      <AiDecisionCard
        decision={decision}
        onAnswer={onAnswer}
        onSubmitClarification={onSubmitClarification}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: "Retry" }));

    expect(onAnswer).not.toHaveBeenCalled();
    expect(onSubmitClarification).toHaveBeenCalledWith("retry");
  });

  it("should render a contextual retry with collapsed technical details and semantic actions", () => {
    const onAnswer = jest.fn();
    const decision = buildDecision({
      kind: "clarify",
      question: "The provider temporarily limited requests.",
      actions: ["retry-creation", "stop-with-report"],
      recovery: {
        category: "temporary-technical-failure",
        title: "Creation paused temporarily",
        summary: "The provider temporarily limited requests.",
        preservedWork: "Your approved requirements and plan are saved.",
        technicalDetails: "rate_limit_exceeded",
        retryDelayMs: 2_000,
        runId: "run-1",
        failedStageId: "design-execution",
      },
    });

    render(<AiDecisionCard decision={decision} onAnswer={onAnswer} />);

    expect(screen.getByRole("alert")).toHaveAttribute("aria-labelledby");
    expect(screen.getByText("Creation paused temporarily")).toBeInTheDocument();
    expect(
      screen.getByText("Your approved requirements and plan are saved."),
    ).toBeInTheDocument();
    expect(screen.getByText("Retry in 2 seconds.")).toBeInTheDocument();
    const details = screen.getByText("Technical details").closest("details");
    expect(details).not.toHaveAttribute("open");

    fireEvent.click(screen.getByText("Technical details"));
    expect(details).toHaveAttribute("open");
    expect(screen.getByText(/rate_limit_exceeded/)).toBeInTheDocument();

    const retry = screen.getByRole("button", { name: "Retry creation" });
    expect(retry.className).toMatch(/ant-btn-primary/);
    fireEvent.click(retry);

    expect(onAnswer).toHaveBeenCalledWith("retry-creation", "");
    expect(
      screen.getByRole("button", { name: "End run and keep report" }),
    ).toBeInTheDocument();
  });

  it("should render a requirement defect with Edit requirements and no stage identifiers", () => {
    const onAnswer = jest.fn();
    const decision = buildDecision({
      kind: "clarify",
      question: "The approved requirements need correction.",
      actions: ["edit-requirements", "stop-with-report"],
      recovery: {
        category: "requirement-brief-defect",
        title: "Requirements need correction",
        summary: "The approved requirements need correction.",
        preservedWork: "Your approved product facts stay available.",
        technicalDetails: "PLAN_BLOCKER: missing quartz",
        runId: "run-1",
        failedStageId: "planning",
      },
    });

    render(<AiDecisionCard decision={decision} onAnswer={onAnswer} />);

    expect(
      screen.getByText("Requirements need correction"),
    ).toBeInTheDocument();
    expect(
      screen.getByText("Your approved product facts stay available."),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "planning" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "requirement-analysis" }),
    ).not.toBeInTheDocument();

    const details = screen.getByText("Technical details").closest("details");
    expect(details).not.toHaveAttribute("open");
    fireEvent.click(screen.getByText("Technical details"));
    expect(
      screen.getByText(/PLAN_BLOCKER: missing quartz/),
    ).toBeInTheDocument();

    const edit = screen.getByRole("button", { name: "Edit requirements" });
    expect(edit.className).toMatch(/ant-btn-primary/);
    fireEvent.click(edit);

    expect(onAnswer).toHaveBeenCalledWith("edit-requirements", "");
    expect(
      screen.getByRole("button", { name: "End run and keep report" }),
    ).toBeInTheDocument();
  });

  it("should render a plan defect with Rebuild plan and no stage identifiers", () => {
    const onAnswer = jest.fn();
    const decision = buildDecision({
      kind: "clarify",
      question: "The plan is missing information required to create the chain.",
      actions: ["rebuild-plan", "stop-with-report"],
      recovery: {
        category: "plan-artifact-defect",
        title: "The plan cannot be used",
        summary:
          "The plan is missing information required to create the chain.",
        preservedWork: "Your approved requirements stay unchanged.",
        technicalDetails: "PLAN_BLOCKER: invalid graph edge",
        runId: "run-1",
        failedStageId: "design-execution",
      },
    });

    render(<AiDecisionCard decision={decision} onAnswer={onAnswer} />);

    expect(screen.getByText("The plan cannot be used")).toBeInTheDocument();
    expect(
      screen.getByText("Your approved requirements stay unchanged."),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "design-planning" }),
    ).not.toBeInTheDocument();

    const rebuild = screen.getByRole("button", { name: "Rebuild plan" });
    expect(rebuild.className).toMatch(/ant-btn-primary/);
    fireEvent.click(rebuild);

    expect(onAnswer).toHaveBeenCalledWith("rebuild-plan", "");
    expect(
      screen.getByRole("button", { name: "End run and keep report" }),
    ).toBeInTheDocument();
  });

  it("should render an environment failure with only End run and keep report", () => {
    const onAnswer = jest.fn();
    const decision = buildDecision({
      kind: "clarify",
      question: "This region is not supported for chain creation.",
      actions: ["stop-with-report"],
      recovery: {
        category: "permanent-environment-failure",
        title: "Creation cannot continue here",
        summary: "This region is not supported for chain creation.",
        preservedWork: "Your approved requirements and plan are saved.",
        technicalDetails: "PKIX path building failed",
        runId: "run-1",
        failedStageId: "design-execution",
      },
    });

    render(<AiDecisionCard decision={decision} onAnswer={onAnswer} />);

    expect(
      screen.getByText("Creation cannot continue here"),
    ).toBeInTheDocument();
    expect(
      screen.getByText("Your approved requirements and plan are saved."),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Retry creation" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Edit requirements" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Rebuild plan" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "design-execution" }),
    ).not.toBeInTheDocument();

    const endRun = screen.getByRole("button", {
      name: "End run and keep report",
    });
    expect(endRun.className).not.toMatch(/ant-btn-primary/);
    fireEvent.click(endRun);

    expect(onAnswer).toHaveBeenCalledWith("stop-with-report", "");
  });

  it("should render an internal failure with only End run and keep report", () => {
    const onAnswer = jest.fn();
    const decision = buildDecision({
      kind: "clarify",
      question:
        "A step inside the service broke. Repeating the same request will not help.",
      actions: ["stop-with-report"],
      recovery: {
        category: "internal-service-failure",
        title: "Creation hit an internal problem",
        summary:
          "A step inside the service broke. Repeating the same request will not help.",
        preservedWork: "Your approved requirements and plan are saved.",
        technicalDetails:
          "java.lang.IllegalStateException: catalog lookup broke",
        runId: "run-1",
        failedStageId: "design-execution",
      },
    });

    render(<AiDecisionCard decision={decision} onAnswer={onAnswer} />);

    expect(
      screen.getByText("Creation hit an internal problem"),
    ).toBeInTheDocument();
    expect(
      screen.getByText("Your approved requirements and plan are saved."),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Retry creation" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "design-execution" }),
    ).not.toBeInTheDocument();

    const endRun = screen.getByRole("button", {
      name: "End run and keep report",
    });
    expect(endRun.className).not.toMatch(/ant-btn-primary/);
    fireEvent.click(endRun);

    expect(onAnswer).toHaveBeenCalledWith("stop-with-report", "");
  });

  it("should send deployment follow-up buttons as typed decisions", () => {
    const onAnswer = jest.fn();
    const onSubmitClarification = jest.fn();
    const decision = buildDecision({
      kind: "clarify",
      question: "Would you like me to propose a chain fix?",
      missingEvidence: [],
      actions: ["propose-deployment-fix", "dismiss-deployment-failure"],
    });
    render(
      <AiDecisionCard
        decision={decision}
        onAnswer={onAnswer}
        onSubmitClarification={onSubmitClarification}
      />,
    );

    const propose = screen.getByRole("button", { name: "Propose a fix" });
    expect(propose.className).toMatch(/ant-btn-primary/);
    fireEvent.click(propose);

    expect(onAnswer).toHaveBeenCalledWith("propose-deployment-fix", "");
    expect(onSubmitClarification).not.toHaveBeenCalled();
  });

  it("should send session logging buttons as typed decisions", () => {
    const onAnswer = jest.fn();
    const onSubmitClarification = jest.fn();
    const decision = buildDecision({
      kind: "clarify",
      question:
        "Which session logging level should this chain use? Current: OFF.",
      missingEvidence: [],
      actions: [
        "session-logging-off",
        "session-logging-error",
        "session-logging-info",
        "session-logging-debug",
      ],
    });
    render(
      <AiDecisionCard
        decision={decision}
        onAnswer={onAnswer}
        onSubmitClarification={onSubmitClarification}
      />,
    );

    expect(screen.getByRole("button", { name: "Off" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Error" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Info" })).toBeInTheDocument();
    const debug = screen.getByRole("button", { name: "Debug" });
    expect(debug.className).not.toMatch(/ant-btn-primary/);
    fireEvent.click(debug);

    expect(onAnswer).toHaveBeenCalledWith("session-logging-debug", "");
    expect(onSubmitClarification).not.toHaveBeenCalled();
  });

  it("should render Revise for a stage-revise clarify gate", () => {
    const onAnswer = jest.fn();
    const onSubmitClarification = jest.fn();
    const decision = buildDecision({
      kind: "clarify",
      reason: "The brief omitted the scheduler.",
      missingEvidence: [],
      actions: ["retry", "revise"],
    });
    render(
      <AiDecisionCard
        decision={decision}
        onAnswer={onAnswer}
        onSubmitClarification={onSubmitClarification}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: "Revise" }));

    expect(onAnswer).not.toHaveBeenCalled();
    expect(onSubmitClarification).toHaveBeenCalledWith("revise");
  });

  it("should send a command action through onAnswer even on a clarify card", () => {
    // The import gate is a clarification the server can execute: it has to arrive as a typed
    // command so the transcript records the marker the import stage reads. A stage answer such as
    // "yes" travels as an ordinary message instead.
    const onAnswer = jest.fn();
    const onSubmitClarification = jest.fn();
    const decision = buildDecision({
      kind: "clarify",
      reason:
        "Import the API Hub specification into the runtime catalog before planning?",
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

    fireEvent.click(
      screen.getByRole("button", { name: "Import specification" }),
    );

    expect(onSubmitClarification).not.toHaveBeenCalled();
    expect(onAnswer).toHaveBeenCalledWith("import-specification", "");
  });

  it("should render Pass through and Describe mappings for a mapping-gap clarify gate", () => {
    const onAnswer = jest.fn();
    const onSubmitClarification = jest.fn();
    const decision = buildDecision({
      kind: "clarify",
      reason:
        "Some data mappings are still missing before design can continue.",
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
        '1. INITIALIZATION: ENDPOINT "GET /orders" → SERVICE_CALL "Outbound call call-1"',
      ),
    ).toBeInTheDocument();
    expect(
      screen.getByPlaceholderText("One rule per line: 1: $.source -> $.target"),
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
      reason:
        "Some data mappings are still missing before design can continue.",
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
      screen.getByPlaceholderText("One rule per line: 1: $.source -> $.target"),
      { target: { value: "1: $.id → $.customerId" } },
    );
    fireEvent.click(screen.getByRole("button", { name: "Describe mappings" }));

    expect(onSubmitClarification).toHaveBeenCalledWith(
      "1: $.id → $.customerId",
    );
  });

  it("should render numbered patch actions as a list with bold verbs", () => {
    render(
      <AiDecisionCard
        decision={buildDecision({
          question:
            "Add an if branch to the Available pets decision.\n\n1. **Adds** Available is at least ten (if)\n\n2. **Adds** Log healthy inventory (log-record)\n\nApply this to the chain?",
          actions: ["apply-chain-patch", "request-changes"],
        })}
        onAnswer={jest.fn()}
      />,
    );

    const items = screen.getAllByRole("listitem");
    expect(items).toHaveLength(2);
    expect(items[0]).toHaveTextContent("Adds Available is at least ten (if)");
    expect(items[1]).toHaveTextContent(
      "Adds Log healthy inventory (log-record)",
    );
    expect(items[0].querySelector("strong")).toHaveTextContent("Adds");
    expect(items[1].querySelector("strong")).toHaveTextContent("Adds");
    expect(screen.getByRole("button", { name: "Apply" })).toBeInTheDocument();
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
