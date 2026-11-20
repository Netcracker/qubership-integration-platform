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

// The icon's labelled element is aria-hidden, so it is found by its class.
function queryWarningIcon(): HTMLElement | null {
  return document.querySelector(".anticon-exclamation-circle");
}

async function openDetailsFromTooltip() {
  fireEvent.mouseEnter(queryWarningIcon()!);
  fireEvent.click(await screen.findByText("Show details"));
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

  it("does not show the warning icon when there are no details", () => {
    render(
      <DeploymentRuntimeState
        name="DEPLOYED"
        service="svc"
        timestamp={0}
        runtimeState={makeState()}
      />,
    );
    expect(queryWarningIcon()).not.toBeInTheDocument();
  });

  it("shows the error in the warning icon tooltip", async () => {
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
    fireEvent.mouseEnter(queryWarningIcon()!);
    expect(await screen.findByText("boom")).toBeInTheDocument();
  });

  it("opens ErrorDetails modal from the Show details link", async () => {
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

    await openDetailsFromTooltip();

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

  it("opens modal even when only stacktrace is provided (no error message)", async () => {
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

    await openDetailsFromTooltip();

    expect(mockShowModal).toHaveBeenCalledTimes(1);
    const [call] = mockShowModal.mock.calls[0];
    expect(call.component.props).toMatchObject({
      message: "",
      stacktrace: "trace-only",
      status: "FAILED",
    });
  });

  it("does not open modal when clicking a label without details", () => {
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

  it("passes the runtime status enum value straight through to ErrorDetails", async () => {
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

    await openDetailsFromTooltip();

    const [call] = mockShowModal.mock.calls[0];
    expect(call.component.props.status).toBe(DeploymentStatus.PROCESSING);
  });
});
