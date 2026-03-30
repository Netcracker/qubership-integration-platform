/**
 * @jest-environment jsdom
 */
import React from "react";
import { render, screen, fireEvent } from "@testing-library/react";
import "@testing-library/jest-dom";

const mockZoomIn = jest.fn();
const mockZoomOut = jest.fn();
const mockFitView = jest.fn();

let mockZoom = 1;
let mockMinZoom = 0.5;
let mockMaxZoom = 2;

jest.mock("@xyflow/react", () => ({
  useReactFlow: () => ({
    zoomIn: mockZoomIn,
    zoomOut: mockZoomOut,
    fitView: mockFitView,
  }),
  useViewport: () => ({ zoom: mockZoom }),
  useStore: (selector: (s: { minZoom: number; maxZoom: number }) => unknown) =>
    selector({ minZoom: mockMinZoom, maxZoom: mockMaxZoom }),
}));

const mockToggleDirection = jest.fn();
const mockToggleLeftPanel = jest.fn();
const mockToggleRightPanel = jest.fn();

let mockLeftPanel = true;
let mockRightPanel = false;

jest.mock("../../../src/pages/ElkDirectionContext", () => ({
  useElkDirectionContext: () => ({
    direction: "RIGHT",
    toggleDirection: mockToggleDirection,
    leftPanel: mockLeftPanel,
    toggleLeftPanel: mockToggleLeftPanel,
    rightPanel: mockRightPanel,
    toggleRightPanel: mockToggleRightPanel,
  }),
}));

const mockToggleFullscreen = jest.fn();
let mockFullscreenCtx: {
  fullscreen: boolean;
  toggleFullscreen: () => void;
} | null = {
  fullscreen: false,
  toggleFullscreen: mockToggleFullscreen,
};

jest.mock("../../../src/pages/ChainFullscreenContext", () => ({
  useChainFullscreenContext: () => mockFullscreenCtx,
}));

jest.mock("../../../src/icons/IconProvider", () => ({
  OverridableIcon: ({ name }: { name: string }) => (
    <span data-testid={`icon-${name}`} />
  ),
}));

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

import { CustomControls } from "../../../src/components/graph/CustomControls";

describe("CustomControls", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockLeftPanel = true;
    mockRightPanel = false;
    mockZoom = 1;
    mockMinZoom = 0.5;
    mockMaxZoom = 2;
    mockFullscreenCtx = {
      fullscreen: false,
      toggleFullscreen: mockToggleFullscreen,
    };
  });

  it("renders standard buttons", () => {
    render(<CustomControls />);

    expect(screen.getByTitle("Zoom In")).toBeInTheDocument();
    expect(screen.getByTitle("Zoom Out")).toBeInTheDocument();
    expect(screen.getByTitle("Fit View")).toBeInTheDocument();
    expect(screen.getByTitle("Change Layout Direction")).toBeInTheDocument();
    expect(screen.getByTitle("Expand All")).toBeInTheDocument();
    expect(screen.getByTitle("Collapse All")).toBeInTheDocument();
    expect(screen.getByTitle("Right Panel")).toBeInTheDocument();
  });

  it("renders Left Panel button when showLeftPanelToggle is true", () => {
    render(<CustomControls showLeftPanelToggle={true} />);
    expect(screen.getByTitle("Left Panel")).toBeInTheDocument();
  });

  it("does not render Left Panel button when showLeftPanelToggle is false", () => {
    render(<CustomControls showLeftPanelToggle={false} />);
    expect(screen.queryByTitle("Left Panel")).not.toBeInTheDocument();
  });

  it("does not render Left Panel button when showLeftPanelToggle is undefined", () => {
    render(<CustomControls />);
    expect(screen.queryByTitle("Left Panel")).not.toBeInTheDocument();
  });

  it("renders Fullscreen button when fullscreen context is available", () => {
    render(<CustomControls />);
    expect(screen.getByTitle("Fullscreen")).toBeInTheDocument();
  });

  it("does not render Fullscreen button when fullscreen context is null", () => {
    mockFullscreenCtx = null;
    render(<CustomControls />);
    expect(screen.queryByTitle("Fullscreen")).not.toBeInTheDocument();
  });

  it("calls toggleLeftPanel on Left Panel click", () => {
    render(<CustomControls showLeftPanelToggle={true} />);
    fireEvent.click(screen.getByTitle("Left Panel"));
    expect(mockToggleLeftPanel).toHaveBeenCalledTimes(1);
  });

  it("calls toggleRightPanel on Right Panel click", () => {
    render(<CustomControls />);
    fireEvent.click(screen.getByTitle("Right Panel"));
    expect(mockToggleRightPanel).toHaveBeenCalledTimes(1);
  });

  it("calls toggleFullscreen on Fullscreen click", () => {
    render(<CustomControls />);
    fireEvent.click(screen.getByTitle("Fullscreen"));
    expect(mockToggleFullscreen).toHaveBeenCalledTimes(1);
  });

  it("marks Left Panel as active when leftPanel is true", () => {
    mockLeftPanel = true;
    render(<CustomControls showLeftPanelToggle={true} />);
    expect(screen.getByTitle("Left Panel")).toHaveAttribute(
      "data-active",
      "true",
    );
  });

  it("marks Left Panel as inactive when leftPanel is false", () => {
    mockLeftPanel = false;
    render(<CustomControls showLeftPanelToggle={true} />);
    expect(screen.getByTitle("Left Panel")).toHaveAttribute(
      "data-active",
      "false",
    );
  });

  it("marks Right Panel as active when rightPanel is true", () => {
    mockRightPanel = true;
    render(<CustomControls />);
    expect(screen.getByTitle("Right Panel")).toHaveAttribute(
      "data-active",
      "true",
    );
  });

  it("marks Right Panel as inactive when rightPanel is false", () => {
    mockRightPanel = false;
    render(<CustomControls />);
    expect(screen.getByTitle("Right Panel")).toHaveAttribute(
      "data-active",
      "false",
    );
  });

  it("marks Fullscreen as active when fullscreen is true", () => {
    mockFullscreenCtx = {
      fullscreen: true,
      toggleFullscreen: mockToggleFullscreen,
    };
    render(<CustomControls />);
    expect(screen.getByTitle("Fullscreen")).toHaveAttribute(
      "data-active",
      "true",
    );
  });

  it("marks Fullscreen as inactive when fullscreen is false", () => {
    mockFullscreenCtx = {
      fullscreen: false,
      toggleFullscreen: mockToggleFullscreen,
    };
    render(<CustomControls />);
    expect(screen.getByTitle("Fullscreen")).toHaveAttribute(
      "data-active",
      "false",
    );
  });

  it("calls zoomIn on Zoom In click", () => {
    render(<CustomControls />);
    fireEvent.click(screen.getByTitle("Zoom In"));
    expect(mockZoomIn).toHaveBeenCalledTimes(1);
  });

  it("calls zoomOut on Zoom Out click", () => {
    render(<CustomControls />);
    fireEvent.click(screen.getByTitle("Zoom Out"));
    expect(mockZoomOut).toHaveBeenCalledTimes(1);
  });

  it("calls fitView on Fit View click", () => {
    render(<CustomControls />);
    fireEvent.click(screen.getByTitle("Fit View"));
    expect(mockFitView).toHaveBeenCalledTimes(1);
  });

  it("calls toggleDirection on Change Layout Direction click", () => {
    render(<CustomControls />);
    fireEvent.click(screen.getByTitle("Change Layout Direction"));
    expect(mockToggleDirection).toHaveBeenCalledTimes(1);
  });

  it("disables Zoom In when zoom is at max", () => {
    mockZoom = 2;
    mockMaxZoom = 2;
    render(<CustomControls />);
    expect(screen.getByTitle("Zoom In")).toBeDisabled();
  });

  it("does not disable Zoom In when zoom is below max", () => {
    mockZoom = 1;
    mockMaxZoom = 2;
    render(<CustomControls />);
    expect(screen.getByTitle("Zoom In")).not.toBeDisabled();
  });

  it("disables Zoom Out when zoom is at min", () => {
    mockZoom = 0.5;
    mockMinZoom = 0.5;
    render(<CustomControls />);
    expect(screen.getByTitle("Zoom Out")).toBeDisabled();
  });

  it("does not disable Zoom Out when zoom is above min", () => {
    mockZoom = 1;
    mockMinZoom = 0.5;
    render(<CustomControls />);
    expect(screen.getByTitle("Zoom Out")).not.toBeDisabled();
  });

  it("renders extra buttons with divider", () => {
    render(
      <CustomControls
        extraButtons={<button data-testid="extra-btn">Extra</button>}
      />,
    );
    expect(screen.getByTestId("extra-btn")).toBeInTheDocument();
  });
});
