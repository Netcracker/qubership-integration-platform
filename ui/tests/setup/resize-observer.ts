// jsdom has no ResizeObserver. Antd v6's resize-observer helper calls
// observe / unobserve / disconnect, so install a complete no-op mock before
// every test file. Tests that need callback capture override this global with
// their own mock.
class ResizeObserverMock {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}

Object.defineProperty(globalThis, "ResizeObserver", {
  writable: true,
  configurable: true,
  value: ResizeObserverMock,
});
