/**
 * @jest-environment jsdom
 */

import React from "react";
import { render, act, renderHook } from "@testing-library/react";
import {
  ElementFocus,
  ElementFocusContext,
  useElementFocusRef,
  useFocusToElementId,
  type FitViewToElementIdFn,
} from "../../../src/components/graph/ElementFocus";

const mockFitView = jest.fn();
const mockGetNodes = jest.fn();
const mockSetNodes = jest.fn();

jest.mock("@xyflow/react", () => ({
  useReactFlow: () => ({
    fitView: mockFitView,
    getNodes: mockGetNodes,
    setNodes: mockSetNodes,
  }),
}));

const renderElementFocus = () => {
  const focusRef = { current: null as FitViewToElementIdFn | null };
  render(
    <ElementFocusContext.Provider value={focusRef}>
      <ElementFocus />
    </ElementFocusContext.Provider>,
  );
  return focusRef;
};

describe("ElementFocus", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockGetNodes.mockReturnValue([
      { id: "node-a", selected: false },
      { id: "node-b", selected: false },
    ]);
  });

  it("registers a handler that selects the node and fits the view", () => {
    const focusRef = renderElementFocus();

    act(() => {
      focusRef.current?.("node-b");
    });

    expect(mockSetNodes).toHaveBeenCalledTimes(1);
    const updater = mockSetNodes.mock.calls[0][0] as (
      nodes: { id: string; selected: boolean }[],
    ) => { id: string; selected: boolean }[];
    expect(
      updater([
        { id: "node-a", selected: false },
        { id: "node-b", selected: false },
      ]),
    ).toEqual([
      { id: "node-a", selected: false },
      { id: "node-b", selected: true },
    ]);

    expect(mockFitView).toHaveBeenCalledWith({
      nodes: [{ id: "node-b" }],
      padding: 0.2,
      duration: 300,
    });
  });

  it("does nothing when the element id is not on the graph", () => {
    mockGetNodes.mockReturnValue([{ id: "node-a", selected: false }]);
    const focusRef = renderElementFocus();

    act(() => {
      focusRef.current?.("missing-node");
    });

    expect(mockSetNodes).not.toHaveBeenCalled();
    expect(mockFitView).not.toHaveBeenCalled();
  });

  it("clears the handler on unmount", () => {
    const focusRef = { current: null as FitViewToElementIdFn | null };
    const { unmount } = render(
      <ElementFocusContext.Provider value={focusRef}>
        <ElementFocus />
      </ElementFocusContext.Provider>,
    );

    expect(focusRef.current).not.toBeNull();

    unmount();

    expect(focusRef.current).toBeNull();
  });
});

describe("useFocusToElementId", () => {
  it("delegates to the context ref handler", () => {
    const handler = jest.fn();
    const focusRef = { current: handler };

    const { result } = renderHook(() => useFocusToElementId(), {
      wrapper: ({ children }) => (
        <ElementFocusContext.Provider value={focusRef}>
          {children}
        </ElementFocusContext.Provider>
      ),
    });

    act(() => {
      result.current("node-1");
    });

    expect(handler).toHaveBeenCalledWith("node-1");
  });
});

describe("useElementFocusRef", () => {
  it("throws when used outside ElementFocusContext.Provider", () => {
    const consoleError = jest
      .spyOn(console, "error")
      .mockImplementation(() => undefined);

    expect(() => renderHook(() => useElementFocusRef())).toThrow(
      "useElementFocusRef must be used within ElementFocusContext.Provider",
    );

    consoleError.mockRestore();
  });
});
