/**
 * @jest-environment jsdom
 */

import React from "react";
import { render, screen, fireEvent, act } from "@testing-library/react";
import "@testing-library/jest-dom";
import { DeploymentStatus, RuntimeState } from "../../../src/api/apiTypes";

Object.defineProperty(globalThis, "matchMedia", {
  writable: true,
  value: jest.fn().mockImplementation((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: jest.fn(),
    removeListener: jest.fn(),
    addEventListener: jest.fn(),
    removeEventListener: jest.fn(),
    dispatchEvent: jest.fn(),
  })),
});

type ShowModalCall = { component: React.ReactElement<Record<string, unknown>> };
const mockShowModal = jest.fn() as jest.MockedFunction<
  (opts: ShowModalCall) => void
>;
jest.mock("../../../src/Modals.tsx", () => ({
  useModalsContext: () => ({
    showModal: mockShowModal,
    closeModal: jest.fn(),
  }),
}));

jest.mock("../../../src/components/modal/ErrorDetails.tsx", () => ({
  ErrorDetails: (props: Record<string, unknown>) => (
    <div data-testid="error-details-stub" data-props={JSON.stringify(props)} />
  ),
}));

import { DeploymentRuntimeState } from "../../../src/components/deployment_runtime_states/DeploymentRuntimeState";

function makeState(overrides: Partial<RuntimeState> = {}): RuntimeState {
  return {
    status: DeploymentStatus.DEPLOYED,
    error: "",
    stacktrace: "",
    suspended: false,
    ...overrides,
  };
}

describe("DeploymentRuntimeState", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("renders status label formatted as title case", () => {
    render(
      <DeploymentRuntimeState
        name="DEPLOYED"
        service="svc"
        timestamp={0}
        runtimeState={makeState()}
      />,
    );
    expect(screen.getByText("Deployed")).toBeInTheDocument();
  });

  it("does not show info icon or clickable role when there are no details", () => {
    render(
      <DeploymentRuntimeState
        name="DEPLOYED"
        service="svc"
        timestamp={0}
        runtimeState={makeState()}
      />,
    );
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
    expect(screen.getByLabelText("Status: Deployed")).toBeInTheDocument();
  });

  it("marks pill as a keyboard-focusable button when error is present", () => {
    render(
      <DeploymentRuntimeState
        name="FAILED"
        service="svc"
        timestamp={42}
        runtimeState={makeState({
          status: DeploymentStatus.FAILED,
          error: "boom",
        })}
      />,
    );
    const pill = screen.getByRole("button", {
      name: /Failed — click to view error details/i,
    });
    expect(pill).toHaveAttribute("tabindex", "0");
  });

  it("opens ErrorDetails modal when pill is clicked", () => {
    render(
      <DeploymentRuntimeState
        name="FAILED"
        service="svc-1"
        timestamp={1234}
        runtimeState={makeState({
          status: DeploymentStatus.FAILED,
          error: "compile failed",
          stacktrace: "at Foo",
        })}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: /Failed/i }));

    expect(mockShowModal).toHaveBeenCalledTimes(1);
    const [call] = mockShowModal.mock.calls[0];
    expect(call.component.props).toMatchObject({
      service: "svc-1",
      timestamp: 1234,
      message: "compile failed",
      stacktrace: "at Foo",
      status: "FAILED",
    });
  });

  it("opens ErrorDetails on Enter and Space key press", () => {
    render(
      <DeploymentRuntimeState
        name="FAILED"
        service="svc"
        timestamp={0}
        runtimeState={makeState({
          status: DeploymentStatus.FAILED,
          error: "e",
        })}
      />,
    );

    const pill = screen.getByRole("button", { name: /Failed/i });
    fireEvent.keyDown(pill, { key: "Enter" });
    fireEvent.keyDown(pill, { key: " " });
    fireEvent.keyDown(pill, { key: "Tab" });

    expect(mockShowModal).toHaveBeenCalledTimes(2);
  });

  it("opens modal even when only stacktrace is provided (no error message)", () => {
    render(
      <DeploymentRuntimeState
        name="FAILED"
        service="svc"
        timestamp={0}
        runtimeState={makeState({
          status: DeploymentStatus.FAILED,
          error: "",
          stacktrace: "trace-only",
        })}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: /Failed/i }));

    expect(mockShowModal).toHaveBeenCalledTimes(1);
    const [call] = mockShowModal.mock.calls[0];
    expect(call.component.props).toMatchObject({
      message: "",
      stacktrace: "trace-only",
      status: "FAILED",
    });
  });

  it("does not open modal when clicking a pill without details", () => {
    render(
      <DeploymentRuntimeState
        name="DEPLOYED"
        service="svc"
        timestamp={0}
        runtimeState={makeState()}
      />,
    );
    act(() => {
      fireEvent.click(screen.getByText("Deployed"));
    });
    expect(mockShowModal).not.toHaveBeenCalled();
  });

  it("passes the runtime status enum value straight through to ErrorDetails", () => {
    render(
      <DeploymentRuntimeState
        name="Processing"
        service="svc"
        timestamp={0}
        runtimeState={makeState({
          status: DeploymentStatus.PROCESSING,
          error: "still compiling",
        })}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: /Processing/i }));

    const [call] = mockShowModal.mock.calls[0];
    expect(call.component.props.status).toBe(DeploymentStatus.PROCESSING);
  });
});
