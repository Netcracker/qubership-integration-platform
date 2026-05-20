/**
 * @jest-environment jsdom
 */

import "@testing-library/jest-dom";
import { fireEvent, render, screen } from "@testing-library/react";
import { DeploymentStatus, RuntimeState } from "../../../src/api/apiTypes";

const mockShowModal = jest.fn();
jest.mock("../../../src/Modals.tsx", () => ({
  useModalsContext: () => ({
    showModal: mockShowModal,
    closeModal: jest.fn(),
  }),
}));

jest.mock("../../../src/components/modal/ErrorDetails.tsx", () => ({
  ErrorDetails: ({ message }: { message: string }) => (
    <div data-testid="error-details-stub">{message}</div>
  ),
}));

// Import after mocks
import { DeploymentStateTag } from "../../../src/components/deployment_runtime_states/DeploymentStateTag";

const healthy: RuntimeState = {
  status: DeploymentStatus.DEPLOYED,
  error: "",
  stacktrace: "",
  suspended: false,
};

const failing: RuntimeState = {
  status: DeploymentStatus.FAILED,
  error: "Something broke",
  stacktrace: "trace goes here",
  suspended: false,
};

describe("DeploymentStateTag", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("renders a plain tag (no popover, no pointer cursor) when the state has no error", () => {
    const { container } = render(
      <DeploymentStateTag
        name="10.0.0.1"
        service="svc"
        timestamp={123}
        runtimeState={healthy}
      />,
    );
    // Label is the engine host capitalized (numeric string stays as-is).
    expect(screen.getByText("10.0.0.1")).toBeInTheDocument();
    // success color class is applied by antd Tag.
    const tag = container.querySelector(".ant-tag-success");
    expect(tag).toBeInTheDocument();
    expect(tag).not.toHaveStyle({ cursor: "pointer" });
  });

  it("renders a clickable tag when the state has an error", () => {
    render(
      <DeploymentStateTag
        name="10.0.0.2"
        service="svc"
        timestamp={123}
        runtimeState={failing}
      />,
    );
    const label = screen.getByText("10.0.0.2");
    const tag = label.closest(".ant-tag") as HTMLElement;
    expect(tag).toHaveStyle({ cursor: "pointer" });
  });

  it("opens the ErrorDetails modal when the tag is clicked", () => {
    render(
      <DeploymentStateTag
        name="10.0.0.2"
        service="svc"
        timestamp={123}
        runtimeState={failing}
      />,
    );
    const tag = screen.getByText("10.0.0.2").closest(".ant-tag") as HTMLElement;
    fireEvent.click(tag);
    expect(mockShowModal).toHaveBeenCalledTimes(1);
  });

  it("falls back to stacktrace when error is empty", () => {
    const stackOnly: RuntimeState = {
      status: DeploymentStatus.FAILED,
      error: "",
      stacktrace: "trace-only",
      suspended: false,
    };
    render(
      <DeploymentStateTag
        name="10.0.0.3"
        service="svc"
        timestamp={123}
        runtimeState={stackOnly}
      />,
    );
    const tag = screen.getByText("10.0.0.3").closest(".ant-tag") as HTMLElement;
    expect(tag).toHaveStyle({ cursor: "pointer" });
    fireEvent.click(tag);
    expect(mockShowModal).toHaveBeenCalled();
  });
});
