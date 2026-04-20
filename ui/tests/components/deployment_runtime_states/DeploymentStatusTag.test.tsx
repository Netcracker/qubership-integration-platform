/**
 * @jest-environment jsdom
 */

import "@testing-library/jest-dom";
import React from "react";
import { render, screen } from "@testing-library/react";
import {
  DeploymentStatusTag,
  getDeploymentStatusVisuals,
} from "../../../src/components/deployment_runtime_states/DeploymentStatusTag";

describe("getDeploymentStatusVisuals", () => {
  it.each([
    ["DEPLOYED", "success"],
    ["PROCESSING", "processing"],
    ["FAILED", "error"],
    ["WARNING", "warning"],
    ["REMOVED", "default"],
    ["DRAFT", "default"],
    ["unknown", "default"],
  ])("maps %s to Tag color %s", (status, expected) => {
    expect(getDeploymentStatusVisuals(status).color).toBe(expected);
  });

  it("is case-insensitive", () => {
    expect(getDeploymentStatusVisuals("processing").color).toBe("processing");
    expect(getDeploymentStatusVisuals("Deployed").color).toBe("success");
  });

  it("returns an icon for every known status", () => {
    const statuses = [
      "DEPLOYED",
      "PROCESSING",
      "FAILED",
      "WARNING",
      "REMOVED",
      "DRAFT",
      "UNKNOWN",
    ];
    for (const s of statuses) {
      expect(getDeploymentStatusVisuals(s).icon).toBeTruthy();
    }
  });
});

describe("DeploymentStatusTag", () => {
  it("renders capitalized status text by default", () => {
    render(<DeploymentStatusTag status="DEPLOYED" />);
    expect(screen.getByText("Deployed")).toBeInTheDocument();
  });

  it("renders custom text when provided", () => {
    render(<DeploymentStatusTag status="DEPLOYED" text="10.0.0.1" />);
    expect(screen.getByText("10.0.0.1")).toBeInTheDocument();
    expect(screen.queryByText("Deployed")).not.toBeInTheDocument();
  });

  it("applies the icon for processing status (sync-spin)", () => {
    const { container } = render(<DeploymentStatusTag status="PROCESSING" />);
    // SyncOutlined renders with data-icon="sync"
    const icon = container.querySelector('[data-icon="sync"]');
    expect(icon).toBeInTheDocument();
  });

  it.each([
    ["DEPLOYED", "check-circle"],
    ["FAILED", "close-circle"],
    ["WARNING", "exclamation-circle"],
    ["REMOVED", "minus-circle"],
    ["DRAFT", "clock-circle"],
  ])("renders the %s icon for status %s", (status, dataIcon) => {
    const { container } = render(<DeploymentStatusTag status={status} />);
    expect(
      container.querySelector(`[data-icon="${dataIcon}"]`),
    ).toBeInTheDocument();
  });

  it("accepts custom className and style", () => {
    const { container } = render(
      <DeploymentStatusTag
        status="DEPLOYED"
        className="my-tag"
        style={{ opacity: 0.5 }}
      />,
    );
    const tag = container.querySelector(".my-tag");
    expect(tag).toBeInTheDocument();
    expect(tag).toHaveStyle({ opacity: "0.5" });
  });
});
