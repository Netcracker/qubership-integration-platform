/**
 * @jest-environment jsdom
 */
import { act, render, screen } from "@testing-library/react";
import { useChatStickToBottom } from "../../../src/components/ai/useChatStickToBottom.ts";

let resizeObserverCallback: ResizeObserverCallback | undefined;

class ResizeObserverMock {
  observe = jest.fn();
  unobserve = jest.fn();
  disconnect = jest.fn();

  constructor(callback: ResizeObserverCallback) {
    resizeObserverCallback = callback;
  }
}

Object.defineProperty(globalThis, "ResizeObserver", {
  writable: true,
  configurable: true,
  value: ResizeObserverMock,
});

type Metrics = {
  clientHeight: number;
  scrollHeight: number;
  scrollTop: number;
};

function installScrollerMetrics(
  scroller: HTMLElement,
  metrics: Metrics,
  onScrollTopWrite?: () => void,
): void {
  Object.defineProperty(scroller, "clientHeight", {
    configurable: true,
    get: () => metrics.clientHeight,
  });
  Object.defineProperty(scroller, "scrollHeight", {
    configurable: true,
    get: () => metrics.scrollHeight,
  });
  Object.defineProperty(scroller, "scrollTop", {
    configurable: true,
    get: () => metrics.scrollTop,
    set: (value: number) => {
      metrics.scrollTop = value;
      onScrollTopWrite?.();
      scroller.dispatchEvent(new Event("scroll"));
    },
  });
}

async function flushPinFrame(): Promise<void> {
  await act(async () => {
    await new Promise<void>((resolve) => {
      requestAnimationFrame(() => resolve());
    });
  });
}

async function renderHarness(): Promise<{
  scroller: HTMLElement;
  api: ReturnType<typeof useChatStickToBottom>;
}> {
  let latest: ReturnType<typeof useChatStickToBottom> | null = null;
  function Capture() {
    const api = useChatStickToBottom();
    latest = api;
    return (
      <div
        ref={api.scrollContainerRef}
        data-testid="scroller"
        onScroll={api.onScroll}
      >
        <div ref={api.contentRef} data-testid="content" />
      </div>
    );
  }
  render(<Capture />);
  await flushPinFrame();
  if (!latest) {
    throw new Error("hook did not initialize");
  }
  return { scroller: screen.getByTestId("scroller"), api: latest };
}

describe("useChatStickToBottom", () => {
  beforeEach(() => {
    resizeObserverCallback = undefined;
  });

  it("should pin the scroller to the bottom when content grows while following", async () => {
    const { scroller } = await renderHarness();
    const metrics: Metrics = {
      clientHeight: 200,
      scrollHeight: 400,
      scrollTop: 200,
    };
    installScrollerMetrics(scroller, metrics);

    metrics.scrollHeight = 550;
    resizeObserverCallback?.([], {} as ResizeObserver);

    expect(metrics.scrollTop).toBe(550);
  });

  it("should leave the scroller where it is when the reader has scrolled up", async () => {
    const { scroller, api } = await renderHarness();
    const metrics: Metrics = {
      clientHeight: 200,
      scrollHeight: 400,
      scrollTop: 200,
    };
    installScrollerMetrics(scroller, metrics);

    metrics.scrollTop = 20;
    api.onScroll();

    metrics.scrollHeight = 700;
    resizeObserverCallback?.([], {} as ResizeObserver);

    expect(metrics.scrollTop).toBe(20);
  });

  it("should follow again when resumeFollowing is called after the reader scrolled up", async () => {
    const { scroller, api } = await renderHarness();
    const metrics: Metrics = {
      clientHeight: 200,
      scrollHeight: 400,
      scrollTop: 20,
    };
    installScrollerMetrics(scroller, metrics);
    api.onScroll();

    api.resumeFollowing();

    expect(metrics.scrollTop).toBe(400);
  });

  it("should keep following when a programmatic pin fires scroll before layout catches up", async () => {
    const { scroller, api } = await renderHarness();
    const metrics: Metrics = {
      clientHeight: 200,
      scrollHeight: 400,
      scrollTop: 200,
    };
    let inflated = false;
    installScrollerMetrics(scroller, metrics, () => {
      if (!inflated) {
        inflated = true;
        metrics.scrollHeight = 550;
      }
    });

    api.pinToBottom();
    resizeObserverCallback?.([], {} as ResizeObserver);

    expect(metrics.scrollTop).toBe(metrics.scrollHeight);
  });
});
