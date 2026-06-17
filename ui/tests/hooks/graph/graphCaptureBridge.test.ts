import {
  registerGraphCaptureSource,
  getGraphCaptureSource,
  GraphCaptureSource,
} from "../../../src/hooks/graph/graphCaptureBridge";

describe("graphCaptureBridge", () => {
  const cleanups: Array<() => void> = [];

  const register = (
    mounted = true,
  ): { source: GraphCaptureSource; unregister: () => void } => {
    const source: GraphCaptureSource = {
      isMounted: () => mounted,
      getViewportEl: () => null,
      getNodes: () => [],
      prepareForExport: () => Promise.resolve(() => undefined),
    };
    const unregister = registerGraphCaptureSource(source);
    cleanups.push(unregister);
    return { source, unregister };
  };

  afterEach(() => {
    while (cleanups.length) {
      cleanups.pop()?.();
    }
  });

  test("should return null when no source is registered", () => {
    expect(getGraphCaptureSource()).toBeNull();
  });

  test("should return the registered source when it is mounted", () => {
    const { source } = register(true);
    expect(getGraphCaptureSource()).toBe(source);
  });

  test("should return null when the only source is unmounted", () => {
    register(false);
    expect(getGraphCaptureSource()).toBeNull();
  });

  test("should return the first mounted source when several are registered", () => {
    const { source: first } = register(true);
    register(true);
    expect(getGraphCaptureSource()).toBe(first);
  });

  test("should skip unmounted sources and return the first mounted one", () => {
    register(false);
    const { source: mounted } = register(true);
    expect(getGraphCaptureSource()).toBe(mounted);
  });

  test("should stop returning a source after it is unregistered", () => {
    const { unregister } = register(true);
    unregister();
    expect(getGraphCaptureSource()).toBeNull();
  });

  test("should unregister only its own source", () => {
    const { unregister: unregisterFirst } = register(true);
    const { source: second } = register(true);
    unregisterFirst();
    expect(getGraphCaptureSource()).toBe(second);
  });
});
