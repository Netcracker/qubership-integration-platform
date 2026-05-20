/**
 * @jest-environment jsdom
 */
import React from "react";
import { render } from "@testing-library/react";
import { useResizeHeight } from "../../src/hooks/useResizeHeigth";

let resizeObserverCallback: ResizeObserverCallback | undefined;

class ResizeObserverMock {
  observe = jest.fn();
  disconnect = jest.fn();

  constructor(callback: ResizeObserverCallback) {
    resizeObserverCallback = callback;
  }
}

Object.defineProperty(globalThis, "ResizeObserver", {
  writable: true,
  value: ResizeObserverMock,
});

function HookHost() {
  const [ref] = useResizeHeight<HTMLDivElement>();

  return <div ref={ref}>content</div>;
}

function HookHostWithoutAttachedRef() {
  useResizeHeight<HTMLDivElement>();

  return <div>content</div>;
}

describe("useResizeHeight", () => {
  beforeEach(() => {
    resizeObserverCallback = undefined;
  });

  it("does nothing when no element is attached to the ref", () => {
    render(<HookHostWithoutAttachedRef />);

    expect(resizeObserverCallback).toBeUndefined();
  });

  it("does not throw if a queued resize callback runs after unmount", () => {
    const { unmount } = render(<HookHost />);

    expect(resizeObserverCallback).toBeDefined();

    unmount();

    expect(() => {
      resizeObserverCallback?.([], {} as ResizeObserver);
    }).not.toThrow();
  });
});
