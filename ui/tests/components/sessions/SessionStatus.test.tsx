/**
 * @jest-environment jsdom
 */
import { describe, it, expect } from "@jest/globals";
import { render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";
import { SessionStatus } from "../../../src/components/sessions/SessionStatus";
import { ExecutionStatus } from "../../../src/api/apiTypes";

describe("SessionStatus", () => {
  it("should render a processing badge when status is in progress", () => {
    const { container } = render(
      <SessionStatus status={ExecutionStatus.IN_PROGRESS} />,
    );
    expect(screen.getByText("In progress")).toBeInTheDocument();
    expect(
      container.querySelector(".ant-badge-status-processing"),
    ).toBeInTheDocument();
  });

  it("should render an error badge when status is completed with errors", () => {
    const { container } = render(
      <SessionStatus status={ExecutionStatus.COMPLETED_WITH_ERRORS} />,
    );
    expect(screen.getByText("Completed with errors")).toBeInTheDocument();
    expect(
      container.querySelector(".ant-badge-status-error"),
    ).toBeInTheDocument();
  });

  it("should render a success badge when status is completed normally", () => {
    const { container } = render(
      <SessionStatus status={ExecutionStatus.COMPLETED_NORMALLY} />,
    );
    expect(screen.getByText("Completed normally")).toBeInTheDocument();
    expect(
      container.querySelector(".ant-badge-status-success"),
    ).toBeInTheDocument();
  });

  it("should append the suffix to the label when a suffix is provided", () => {
    render(
      <SessionStatus
        status={ExecutionStatus.CANCELLED_OR_UNKNOWN}
        suffix="(retry)"
      />,
    );
    expect(
      screen.getByText("Cancelled or unknown (retry)"),
    ).toBeInTheDocument();
  });
});
