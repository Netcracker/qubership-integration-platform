/**
 * @jest-environment jsdom
 */
if (globalThis.PointerEvent === undefined) {
  globalThis.PointerEvent = class PolyPointerEvent extends MouseEvent {
    pointerId: number;
    constructor(type: string, init?: PointerEventInit) {
      super(type, init);
      this.pointerId = init?.pointerId ?? 0;
    }
  } as unknown as typeof PointerEvent;
}

import React from "react";
import { render, screen, fireEvent, cleanup } from "@testing-library/react";
import "@testing-library/jest-dom";
import { ResizableTitle } from "../../../src/components/table/ResizableTitle";

describe("ResizableTitle", () => {
  let rectSpy: jest.SpyInstance;
  const pointerOriginals: {
    setPointerCapture?: typeof HTMLElement.prototype.setPointerCapture;
    releasePointerCapture?: typeof HTMLElement.prototype.releasePointerCapture;
    hasPointerCapture?: typeof HTMLElement.prototype.hasPointerCapture;
  } = {};

  beforeAll(() => {
    const p = HTMLElement.prototype as HTMLElement & {
      setPointerCapture?: (id: number) => void;
      releasePointerCapture?: (id: number) => void;
      hasPointerCapture?: (id: number) => boolean;
    };
    pointerOriginals.setPointerCapture = p.setPointerCapture;
    pointerOriginals.releasePointerCapture = p.releasePointerCapture;
    pointerOriginals.hasPointerCapture = p.hasPointerCapture;
    p.setPointerCapture = jest.fn();
    p.releasePointerCapture = jest.fn();
    p.hasPointerCapture = jest.fn().mockReturnValue(true);
  });

  afterAll(() => {
    const p = HTMLElement.prototype as HTMLElement & {
      setPointerCapture?: unknown;
      releasePointerCapture?: unknown;
      hasPointerCapture?: unknown;
    };
    if (pointerOriginals.setPointerCapture === undefined) {
      delete p.setPointerCapture;
    } else {
      p.setPointerCapture = pointerOriginals.setPointerCapture;
    }
    if (pointerOriginals.releasePointerCapture === undefined) {
      delete p.releasePointerCapture;
    } else {
      p.releasePointerCapture = pointerOriginals.releasePointerCapture;
    }
    if (pointerOriginals.hasPointerCapture === undefined) {
      delete p.hasPointerCapture;
    } else {
      p.hasPointerCapture = pointerOriginals.hasPointerCapture;
    }
  });

  beforeEach(() => {
    (HTMLElement.prototype.setPointerCapture as jest.Mock).mockClear();
    (HTMLElement.prototype.releasePointerCapture as jest.Mock).mockClear();
    (HTMLElement.prototype.hasPointerCapture as jest.Mock).mockReturnValue(
      true,
    );
    rectSpy = jest
      .spyOn(HTMLElement.prototype, "getBoundingClientRect")
      .mockReturnValue({
        width: 200,
        height: 40,
        top: 0,
        left: 0,
        right: 200,
        bottom: 40,
        x: 0,
        y: 0,
        toJSON: () => ({}),
      } as DOMRect);
  });

  afterEach(() => {
    rectSpy.mockRestore();
    cleanup();
  });

  it("does not render resize handle without both resize callbacks", () => {
    const onResize = jest.fn();
    render(
      <table>
        <thead>
          <tr>
            <ResizableTitle width={100} onResize={onResize}>
              A
            </ResizableTitle>
          </tr>
        </thead>
      </table>,
    );
    expect(screen.queryByRole("button", { name: /resize column/i })).toBeNull();
  });

  it("does not render resize handle when width is missing or non-positive", () => {
    const handlers = { onResize: jest.fn(), onResizeStop: jest.fn() };
    const { rerender } = render(
      <table>
        <thead>
          <tr>
            <ResizableTitle width={undefined} {...handlers}>
              A
            </ResizableTitle>
          </tr>
        </thead>
      </table>,
    );
    expect(screen.queryByRole("button", { name: /resize column/i })).toBeNull();
    rerender(
      <table>
        <thead>
          <tr>
            <ResizableTitle width={0} {...handlers}>
              A
            </ResizableTitle>
          </tr>
        </thead>
      </table>,
    );
    expect(screen.queryByRole("button", { name: /resize column/i })).toBeNull();
  });

  it("starts drag and emits onResize on pointer move", () => {
    const onResize = jest.fn();
    const onResizeStop = jest.fn();
    render(
      <table>
        <thead>
          <tr>
            <ResizableTitle
              width={200}
              onResize={onResize}
              onResizeStop={onResizeStop}
            >
              Col
            </ResizableTitle>
          </tr>
        </thead>
      </table>,
    );
    const handle = screen.getByRole("button", { name: /resize column/i });
    fireEvent.pointerDown(handle, {
      button: 0,
      clientX: 100,
      pointerId: 42,
      bubbles: true,
    });
    fireEvent(
      globalThis,
      new PointerEvent("pointermove", {
        bubbles: true,
        clientX: 120,
        pointerId: 42,
      }),
    );
    expect(onResize).toHaveBeenCalled();
    fireEvent(
      globalThis,
      new PointerEvent("pointerup", {
        bubbles: true,
        pointerId: 42,
      }),
    );
    expect(onResizeStop).toHaveBeenCalled();
  });

  it("ignores pointerup when pointer id does not match active drag", () => {
    const onResize = jest.fn();
    const onResizeStop = jest.fn();
    render(
      <table>
        <thead>
          <tr>
            <ResizableTitle
              width={100}
              onResize={onResize}
              onResizeStop={onResizeStop}
            >
              Col
            </ResizableTitle>
          </tr>
        </thead>
      </table>,
    );
    const handle = screen.getByRole("button", { name: /resize column/i });
    fireEvent.pointerDown(handle, {
      button: 0,
      clientX: 100,
      pointerId: 7,
      bubbles: true,
    });
    fireEvent(
      globalThis,
      new PointerEvent("pointerup", {
        bubbles: true,
        pointerId: 999,
      }),
    );
    expect(onResizeStop).not.toHaveBeenCalled();
    fireEvent(
      globalThis,
      new PointerEvent("pointerup", {
        bubbles: true,
        pointerId: 7,
      }),
    );
    expect(onResizeStop).toHaveBeenCalled();
  });

  it("ignores non-primary pointer button", () => {
    const onResize = jest.fn();
    const onResizeStop = jest.fn();
    render(
      <table>
        <thead>
          <tr>
            <ResizableTitle
              width={100}
              onResize={onResize}
              onResizeStop={onResizeStop}
            >
              Col
            </ResizableTitle>
          </tr>
        </thead>
      </table>,
    );
    const handle = screen.getByRole("button", { name: /resize column/i });
    fireEvent.pointerDown(handle, {
      button: 1,
      clientX: 100,
      pointerId: 1,
      bubbles: true,
    });
    fireEvent(
      globalThis,
      new PointerEvent("pointermove", {
        bubbles: true,
        clientX: 200,
        pointerId: 1,
      }),
    );
    expect(onResize).not.toHaveBeenCalled();
  });

  it("clamps width to maxResizeWidth", () => {
    const onResize = jest.fn();
    const onResizeStop = jest.fn();
    render(
      <table>
        <thead>
          <tr>
            <ResizableTitle
              width={100}
              maxResizeWidth={110}
              minResizeWidth={50}
              onResize={onResize}
              onResizeStop={onResizeStop}
            >
              Col
            </ResizableTitle>
          </tr>
        </thead>
      </table>,
    );
    const handle = screen.getByRole("button", { name: /resize column/i });
    fireEvent.pointerDown(handle, {
      button: 0,
      clientX: 0,
      pointerId: 99,
      bubbles: true,
    });
    fireEvent(
      globalThis,
      new PointerEvent("pointermove", {
        bubbles: true,
        clientX: 500,
        pointerId: 99,
      }),
    );
    const lastCall = onResize.mock.calls.at(-1);
    expect(lastCall?.[1]?.size?.width).toBeLessThanOrEqual(110);
  });

  it("stops click propagation on resize handle", () => {
    const onResize = jest.fn();
    const onResizeStop = jest.fn();
    const onThClick = jest.fn();
    render(
      <table>
        <thead>
          <tr>
            <ResizableTitle
              width={100}
              onResize={onResize}
              onResizeStop={onResizeStop}
              onClick={onThClick}
            >
              Col
            </ResizableTitle>
          </tr>
        </thead>
      </table>,
    );
    const handle = screen.getByRole("button", { name: /resize column/i });
    fireEvent.click(handle);
    expect(onThClick).not.toHaveBeenCalled();
  });
});
