/**
 * @jest-environment jsdom
 */
import type { Node } from "@xyflow/react";
import { prepareBatchGraphExport } from "../../../src/batch/export/prepareBatchGraphExport";

function makeContainer(viewport: HTMLElement | null): HTMLElement {
  const container = document.createElement("div");
  if (viewport) {
    container.appendChild(viewport);
  }
  return container;
}

function makeNode(type: string, collapsed?: boolean): Node {
  return {
    id: `${type}-${collapsed ?? "open"}`,
    type,
    position: { x: 0, y: 0 },
    data: collapsed === undefined ? {} : { collapsed },
  };
}

describe("prepareBatchGraphExport", () => {
  beforeEach(() => {
    jest.useFakeTimers();
    jest.spyOn(globalThis, "requestAnimationFrame").mockImplementation((cb) => {
      cb(0);
      return 0;
    });
  });

  afterEach(() => {
    jest.useRealTimers();
    jest.restoreAllMocks();
  });

  test("expands containers and returns the viewport element", async () => {
    const viewport = document.createElement("div");
    viewport.className = "react-flow__viewport";
    const container = makeContainer(viewport);
    const expandAllContainers = jest.fn();
    const waitForNextAutoArrange = jest.fn();

    const result = await prepareBatchGraphExport({
      container,
      getNodes: () => [makeNode("service")],
      expandAllContainers,
      waitForNextAutoArrange,
    });

    expect(expandAllContainers).toHaveBeenCalled();
    expect(waitForNextAutoArrange).not.toHaveBeenCalled();
    expect(result).toBe(viewport);
  });

  test("waits for auto-arrange when collapsed containers need layout", async () => {
    const viewport = document.createElement("div");
    viewport.className = "react-flow__viewport";
    const container = makeContainer(viewport);
    const expandAllContainers = jest.fn();
    const waitForNextAutoArrange = jest.fn().mockResolvedValue(undefined);

    const promise = prepareBatchGraphExport({
      container,
      getNodes: () => [makeNode("container", true)],
      expandAllContainers,
      waitForNextAutoArrange,
    });

    await promise;

    expect(waitForNextAutoArrange).toHaveBeenCalled();
    expect(expandAllContainers).toHaveBeenCalled();
  });

  test("calls onLayoutWaitTimeout when layout does not finish in time", async () => {
    const viewport = document.createElement("div");
    viewport.className = "react-flow__viewport";
    const container = makeContainer(viewport);
    const onLayoutWaitTimeout = jest.fn();
    const waitForNextAutoArrange = jest.fn(
      () => new Promise<void>(() => undefined),
    );

    const promise = prepareBatchGraphExport({
      container,
      getNodes: () => [makeNode("container", true)],
      expandAllContainers: jest.fn(),
      waitForNextAutoArrange,
      onLayoutWaitTimeout,
    });

    await jest.advanceTimersByTimeAsync(15_000);
    await promise;

    expect(onLayoutWaitTimeout).toHaveBeenCalled();
  });

  test("returns null when the viewport element is missing", async () => {
    const container = makeContainer(null);

    const result = await prepareBatchGraphExport({
      container,
      getNodes: () => [],
      expandAllContainers: jest.fn(),
      waitForNextAutoArrange: jest.fn(),
    });

    expect(result).toBeNull();
  });
});
