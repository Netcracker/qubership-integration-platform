/**
 * @jest-environment jsdom
 */

import { describe, expect, it, afterEach, beforeEach } from "@jest/globals";
import { act, renderHook } from "@testing-library/react";
import { useDebouncedValue } from "../../src/hooks/useDebouncedValue";

const DELAY = 300;

/** Renders the hook over a value the test types into, holding every value it returned. */
function renderDebounced(value: string) {
  const seen: string[] = [];
  const rendered = renderHook(
    ({ value }: { value: string }) => {
      const [debounced, flush] = useDebouncedValue(value, DELAY);
      seen.push(debounced);
      return { debounced, flush };
    },
    { initialProps: { value } },
  );
  return { ...rendered, seen };
}

describe("useDebouncedValue", () => {
  beforeEach(() => {
    jest.useFakeTimers();
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  it("should settle on the last value once when the changes stop", () => {
    const { result, rerender, seen } = renderDebounced("o");

    rerender({ value: "or" });
    rerender({ value: "ord" });
    act(() => jest.advanceTimersByTime(DELAY - 1));
    expect(result.current.debounced).toBe("o");

    act(() => jest.advanceTimersByTime(1));

    expect(result.current.debounced).toBe("ord");
    // The values in between were never handed out, so a caller acts once.
    expect([...new Set(seen)]).toEqual(["o", "ord"]);
  });

  it("should drop the pending value when the component goes", () => {
    const { rerender, unmount } = renderDebounced("o");

    rerender({ value: "or" });
    expect(jest.getTimerCount()).toBe(1);

    unmount();

    expect(jest.getTimerCount()).toBe(0);
  });

  it("should hand out the current value without the wait when it is flushed", () => {
    const { result, rerender, seen } = renderDebounced("o");

    rerender({ value: "ord" });
    act(() => result.current.flush());

    expect(result.current.debounced).toBe("ord");
    // The timer left over settles on the same value rather than a later one.
    act(() => jest.advanceTimersByTime(DELAY));
    expect([...new Set(seen)]).toEqual(["o", "ord"]);
  });
});
