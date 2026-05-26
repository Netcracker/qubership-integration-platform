/**
 * @jest-environment jsdom
 */
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

jest.mock("../../../src/pages/ElkDirectionContext", () => ({
  useElkDirectionContext: () => ({
    direction: "RIGHT",
    toggleDirection: mockToggleDirection,
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

import { ChainGraphViewControls } from "../../../src/components/graph/ChainGraphViewControls.tsx";

describe("ChainGraphViewControls", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockZoom = 1;
    mockMinZoom = 0.5;
    mockMaxZoom = 2;
    mockFullscreenCtx = {
      fullscreen: false,
      toggleFullscreen: mockToggleFullscreen,
    };
  });

  it("renders standard buttons", () => {
    render(<ChainGraphViewControls />);

    expect(screen.getByTitle("Zoom In")).toBeInTheDocument();
    expect(screen.getByTitle("Zoom Out")).toBeInTheDocument();
    expect(screen.getByTitle("Fit View")).toBeInTheDocument();
    expect(screen.getByTitle("Change Layout Direction")).toBeInTheDocument();
    expect(screen.getByTitle("Expand All")).toBeInTheDocument();
    expect(screen.getByTitle("Collapse All")).toBeInTheDocument();
  });

  it("renders Fullscreen button when fullscreen context is available", () => {
    render(<ChainGraphViewControls />);
    expect(screen.getByTitle("Fullscreen")).toBeInTheDocument();
  });

  it("does not render Fullscreen button when fullscreen context is null", () => {
    mockFullscreenCtx = null;
    render(<ChainGraphViewControls />);
    expect(screen.queryByTitle("Fullscreen")).not.toBeInTheDocument();
  });

  it("renders before slot content", () => {
    render(
      <ChainGraphViewControls
        before={<button data-testid="before-btn">Before</button>}
      />,
    );
    expect(screen.getByTestId("before-btn")).toBeInTheDocument();
  });

  it("renders after slot content with divider", () => {
    render(
      <ChainGraphViewControls
        after={<button data-testid="after-btn">After</button>}
      />,
    );
    expect(screen.getByTestId("after-btn")).toBeInTheDocument();
  });

  it("calls toggleFullscreen on Fullscreen click", () => {
    render(<ChainGraphViewControls />);
    fireEvent.click(screen.getByTitle("Fullscreen"));
    expect(mockToggleFullscreen).toHaveBeenCalledTimes(1);
  });

  it("marks Fullscreen as active when fullscreen is true", () => {
    mockFullscreenCtx = {
      fullscreen: true,
      toggleFullscreen: mockToggleFullscreen,
    };
    render(<ChainGraphViewControls />);
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
    render(<ChainGraphViewControls />);
    expect(screen.getByTitle("Fullscreen")).toHaveAttribute(
      "data-active",
      "false",
    );
  });

  it("calls zoomIn on Zoom In click", () => {
    render(<ChainGraphViewControls />);
    fireEvent.click(screen.getByTitle("Zoom In"));
    expect(mockZoomIn).toHaveBeenCalledTimes(1);
  });

  it("calls zoomOut on Zoom Out click", () => {
    render(<ChainGraphViewControls />);
    fireEvent.click(screen.getByTitle("Zoom Out"));
    expect(mockZoomOut).toHaveBeenCalledTimes(1);
  });

  it("calls fitView on Fit View click", () => {
    render(<ChainGraphViewControls />);
    fireEvent.click(screen.getByTitle("Fit View"));
    expect(mockFitView).toHaveBeenCalledTimes(1);
  });

  it("calls toggleDirection on Change Layout Direction click", () => {
    render(<ChainGraphViewControls />);
    fireEvent.click(screen.getByTitle("Change Layout Direction"));
    expect(mockToggleDirection).toHaveBeenCalledTimes(1);
  });

  it("disables Zoom In when zoom is at max", () => {
    mockZoom = 2;
    mockMaxZoom = 2;
    render(<ChainGraphViewControls />);
    expect(screen.getByTitle("Zoom In")).toBeDisabled();
  });

  it("does not disable Zoom In when zoom is below max", () => {
    mockZoom = 1;
    mockMaxZoom = 2;
    render(<ChainGraphViewControls />);
    expect(screen.getByTitle("Zoom In")).not.toBeDisabled();
  });

  it("disables Zoom Out when zoom is at min", () => {
    mockZoom = 0.5;
    mockMinZoom = 0.5;
    render(<ChainGraphViewControls />);
    expect(screen.getByTitle("Zoom Out")).toBeDisabled();
  });

  it("does not disable Zoom Out when zoom is above min", () => {
    mockZoom = 1;
    mockMinZoom = 0.5;
    render(<ChainGraphViewControls />);
    expect(screen.getByTitle("Zoom Out")).not.toBeDisabled();
  });
});
