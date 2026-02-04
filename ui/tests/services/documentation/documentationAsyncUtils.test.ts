import {
  createDebouncedCallback,
  createLatestOnlyGuard,
} from "../../../src/services/documentation/documentationAsyncUtils";

describe("documentationAsyncUtils", () => {
  test("createLatestOnlyGuard tracks latest token", () => {
    const guard = createLatestOnlyGuard();
    const t1 = guard.nextToken();
    expect(guard.isLatest(t1)).toBe(true);
    const t2 = guard.nextToken();
    expect(guard.isLatest(t1)).toBe(false);
    expect(guard.isLatest(t2)).toBe(true);
  });

  test("createDebouncedCallback calls only once with last args", () => {
    jest.useFakeTimers();
    const fn = jest.fn<void, [number, string]>();
    const debounced = createDebouncedCallback<[number, string]>(250, fn);

    debounced.call(1, "a");
    debounced.call(2, "b");

    expect(fn).not.toHaveBeenCalled();
    jest.advanceTimersByTime(249);
    expect(fn).not.toHaveBeenCalled();
    jest.advanceTimersByTime(1);
    expect(fn).toHaveBeenCalledTimes(1);
    expect(fn).toHaveBeenCalledWith(2, "b");

    jest.useRealTimers();
  });

  test("createDebouncedCallback cancel prevents execution", () => {
    jest.useFakeTimers();
    const fn = jest.fn<void, []>();
    const debounced = createDebouncedCallback<[]>(250, fn);
    debounced.call();
    debounced.cancel();
    jest.runOnlyPendingTimers();
    expect(fn).not.toHaveBeenCalled();
    jest.useRealTimers();
  });
});
