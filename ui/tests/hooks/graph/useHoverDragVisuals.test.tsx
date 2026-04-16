/**
 * @jest-environment jsdom
 */

Object.defineProperty(window, "matchMedia", {
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

import { act, renderHook } from "@testing-library/react";

const mockGetIntersectingNodes = jest.fn();
jest.mock("@xyflow/react", () => ({
  useReactFlow: () => ({
    getIntersectingNodes: mockGetIntersectingNodes,
  }),
}));

jest.mock("../../../src/misc/chain-graph-utils", () => ({
  applyHighlight: jest.fn((nodes: unknown[]) => nodes),
  collectChildren: jest.fn(() => []),
  getPossibleGraphIntersection: jest.fn(),
}));

import { useHoverDragVisuals } from "../../../src/hooks/graph/useHoverDragVisuals";
import {
  applyHighlight,
  getPossibleGraphIntersection,
} from "../../../src/misc/chain-graph-utils";
import type {
  ChainGraphNode,
  ChainGraphNodeData,
} from "../../../src/components/graph/nodes/ChainGraphNodeTypes";
import type { Node } from "@xyflow/react";

const makeNodes = (): Node<ChainGraphNodeData>[] => [
  {
    id: "dragged",
    type: "unit",
    position: { x: 0, y: 0 },
    data: {} as ChainGraphNodeData,
  },
];

const draggedNode = {
  id: "dragged",
  type: "unit",
  position: { x: 0, y: 0 },
  data: {},
} as unknown as ChainGraphNode;

describe("useHoverDragVisuals", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    jest.useFakeTimers();
    mockGetIntersectingNodes.mockReturnValue([]);
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  it("highlightDragIntersections applies highlight with target id", () => {
    (getPossibleGraphIntersection as jest.Mock).mockReturnValue({
      id: "container-2",
    });
    const nodes = makeNodes();
    const setNodes = jest.fn();

    const { result } = renderHook(() =>
      useHoverDragVisuals(nodes, setNodes),
    );

    act(() => {
      result.current.highlightDragIntersections(draggedNode);
    });

    expect(setNodes).toHaveBeenCalled();
    const firstCall = setNodes.mock.calls[0] as unknown as [
      (curr: Node<ChainGraphNodeData>[]) => Node<ChainGraphNodeData>[],
    ];
    firstCall[0](nodes);
    expect(applyHighlight).toHaveBeenLastCalledWith(nodes, "container-2");
  });

  it("clearHighlight applies highlight without target id", () => {
    const nodes = makeNodes();
    const setNodes = jest.fn();

    const { result } = renderHook(() =>
      useHoverDragVisuals(nodes, setNodes),
    );

    act(() => {
      result.current.clearHighlight();
    });

    expect(setNodes).toHaveBeenCalled();
  });

  it("schedules onToggleCollapse when hovering over a collapsed container", () => {
    const onToggleCollapse = jest.fn();
    const collapsedContainer = {
      id: "container-collapsed",
      type: "container",
      position: { x: 0, y: 0 },
      data: { collapsed: true, onToggleCollapse },
    } as unknown as Node<ChainGraphNodeData>;

    (getPossibleGraphIntersection as jest.Mock).mockReturnValue(
      collapsedContainer,
    );

    const nodes = [...makeNodes(), collapsedContainer];
    const setNodes = jest.fn();

    const { result } = renderHook(() =>
      useHoverDragVisuals(nodes, setNodes),
    );

    act(() => {
      result.current.expandDragIntersection(draggedNode);
    });

    expect(onToggleCollapse).not.toHaveBeenCalled();

    act(() => {
      jest.advanceTimersByTime(250);
    });

    expect(onToggleCollapse).toHaveBeenCalled();
  });

  it("clearHoverTimer cancels the pending expansion", () => {
    const onToggleCollapse = jest.fn();
    const collapsedContainer = {
      id: "container-collapsed",
      type: "container",
      position: { x: 0, y: 0 },
      data: { collapsed: true, onToggleCollapse },
    } as unknown as Node<ChainGraphNodeData>;

    (getPossibleGraphIntersection as jest.Mock).mockReturnValue(
      collapsedContainer,
    );

    const nodes = [...makeNodes(), collapsedContainer];
    const setNodes = jest.fn();

    const { result } = renderHook(() =>
      useHoverDragVisuals(nodes, setNodes),
    );

    act(() => {
      result.current.expandDragIntersection(draggedNode);
    });
    act(() => {
      result.current.clearHoverTimer();
    });
    act(() => {
      jest.advanceTimersByTime(500);
    });

    expect(onToggleCollapse).not.toHaveBeenCalled();
  });

  it("does not schedule expansion when container is not collapsed", () => {
    const onToggleCollapse = jest.fn();
    const container = {
      id: "container-open",
      type: "container",
      position: { x: 0, y: 0 },
      data: { collapsed: false, onToggleCollapse },
    } as unknown as Node<ChainGraphNodeData>;

    (getPossibleGraphIntersection as jest.Mock).mockReturnValue(container);

    const nodes = [...makeNodes(), container];
    const setNodes = jest.fn();

    const { result } = renderHook(() =>
      useHoverDragVisuals(nodes, setNodes),
    );

    act(() => {
      result.current.expandDragIntersection(draggedNode);
    });
    act(() => {
      jest.advanceTimersByTime(500);
    });

    expect(onToggleCollapse).not.toHaveBeenCalled();
  });

  it("cancels the pending timer on unmount", () => {
    const collapsedContainer = {
      id: "container-collapsed",
      type: "container",
      position: { x: 0, y: 0 },
      data: { collapsed: true, onToggleCollapse: jest.fn() },
    } as unknown as Node<ChainGraphNodeData>;

    (getPossibleGraphIntersection as jest.Mock).mockReturnValue(
      collapsedContainer,
    );

    const nodes = [...makeNodes(), collapsedContainer];
    const setNodes = jest.fn();

    const { result, unmount } = renderHook(() =>
      useHoverDragVisuals(nodes, setNodes),
    );

    act(() => {
      result.current.expandDragIntersection(draggedNode);
    });
    unmount();
    act(() => {
      jest.advanceTimersByTime(500);
    });

    expect(
      (collapsedContainer.data as unknown as {
        onToggleCollapse: jest.Mock;
      }).onToggleCollapse,
    ).not.toHaveBeenCalled();
  });
});
