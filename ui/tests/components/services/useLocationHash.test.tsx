/**
 * @jest-environment jsdom
 */
import { act, renderHook } from "@testing-library/react";
import { useLocationHash } from "../../../src/components/services/useLocationHash";

describe("useLocationHash", () => {
  afterEach(() => {
    window.location.hash = "";
  });

  it("TC-1: returns defaultValue when hash is empty on mount", () => {
    window.location.hash = "";

    const { result } = renderHook(() => useLocationHash("external"));

    expect(result.current[0]).toBe("external");
  });

  it("TC-2: returns hash fragment without # when hash is set on mount", () => {
    window.location.hash = "#internal";

    const { result } = renderHook(() => useLocationHash("external"));

    expect(result.current[0]).toBe("internal");
  });

  it("TC-3: returns defaultValue when hash is bare # on mount", () => {
    window.location.hash = "#";

    const { result } = renderHook(() => useLocationHash("external"));

    expect(result.current[0]).toBe("external");
  });

  it("TC-4: updates value when hashchange fires with a new hash", () => {
    window.location.hash = "";
    const { result } = renderHook(() => useLocationHash("external"));
    expect(result.current[0]).toBe("external");

    act(() => {
      window.location.hash = "#implemented";
      window.dispatchEvent(new HashChangeEvent("hashchange"));
    });

    expect(result.current[0]).toBe("implemented");
  });

  it("TC-5: falls back to defaultValue when hashchange fires with empty hash", () => {
    window.location.hash = "#internal";
    const { result } = renderHook(() => useLocationHash("external"));
    expect(result.current[0]).toBe("internal");

    act(() => {
      window.location.hash = "";
      window.dispatchEvent(new HashChangeEvent("hashchange"));
    });

    expect(result.current[0]).toBe("external");
  });

  it("TC-6: navigate sets window.location.hash to the given value", () => {
    window.location.hash = "";
    const { result } = renderHook(() => useLocationHash("external"));
    const navigate = result.current[1];

    act(() => {
      navigate("context");
    });

    // window.location.hash always includes the leading # when read back
    expect(window.location.hash).toBe("#context");
  });

  it("TC-7: removes hashchange listener on unmount", () => {
    const removeSpy = jest.spyOn(window, "removeEventListener");

    const { unmount } = renderHook(() => useLocationHash("external"));
    unmount();

    expect(removeSpy).toHaveBeenCalledWith(
      "hashchange",
      expect.any(Function),
    );

    removeSpy.mockRestore();
  });

  it("TC-8: uses updated defaultValue after re-render when hash is empty", () => {
    window.location.hash = "";
    const { result, rerender } = renderHook(
      ({ defaultValue }: { defaultValue: string }) =>
        useLocationHash(defaultValue),
      { initialProps: { defaultValue: "external" } },
    );
    expect(result.current[0]).toBe("external");

    rerender({ defaultValue: "internal" });

    act(() => {
      window.location.hash = "";
      window.dispatchEvent(new HashChangeEvent("hashchange"));
    });

    expect(result.current[0]).toBe("internal");
  });

  it("TC-9: navigate does not update state without a hashchange event", () => {
    // navigate() only writes window.location.hash; it does NOT call setValue.
    // State changes only when the browser fires a hashchange event, which JSDOM
    // does not fire automatically on hash assignment.
    window.location.hash = "";
    const { result } = renderHook(() => useLocationHash("external"));
    const navigate = result.current[1];

    act(() => {
      navigate("context");
    });

    // The hash was written to the window…
    expect(window.location.hash).toBe("#context");
    // …but the hook's internal state has NOT changed because no hashchange fired.
    expect(result.current[0]).toBe("external");
  });

  it("TC-10: old hashchange listener is removed when defaultValue changes", () => {
    // When defaultValue prop changes, React re-runs the effect: the cleanup must
    // call removeEventListener before the new listener is added. Without this,
    // two listeners would coexist with stale closures.
    window.location.hash = "";
    const removeSpy = jest.spyOn(window, "removeEventListener");

    const { rerender } = renderHook(
      ({ defaultValue }: { defaultValue: string }) =>
        useLocationHash(defaultValue),
      { initialProps: { defaultValue: "external" } },
    );

    rerender({ defaultValue: "internal" });

    expect(removeSpy).toHaveBeenCalledWith("hashchange", expect.any(Function));

    removeSpy.mockRestore();
  });

  it("TC-11: multiple sequential hashchange events each update state correctly", () => {
    // Verifies the listener stays active across repeated hash changes — no
    // single-fire or accidental self-removal after the first event.
    window.location.hash = "";
    const { result } = renderHook(() => useLocationHash("external"));

    act(() => {
      window.location.hash = "#mcp";
      window.dispatchEvent(new HashChangeEvent("hashchange"));
    });
    expect(result.current[0]).toBe("mcp");

    act(() => {
      window.location.hash = "#implemented";
      window.dispatchEvent(new HashChangeEvent("hashchange"));
    });
    expect(result.current[0]).toBe("implemented");

    act(() => {
      window.location.hash = "#context";
      window.dispatchEvent(new HashChangeEvent("hashchange"));
    });
    expect(result.current[0]).toBe("context");
  });

  it("TC-12: navigate produces a new function reference on every render (no useCallback)", () => {
    // navigate is re-created on every render because there is no useCallback.
    // This test documents the current contract so a future memoisation change
    // is caught and reviewed intentionally.
    window.location.hash = "";
    const { result } = renderHook(() => useLocationHash("external"));
    const navigateBefore = result.current[1];

    act(() => {
      window.location.hash = "#internal";
      window.dispatchEvent(new HashChangeEvent("hashchange"));
    });

    const navigateAfter = result.current[1];
    expect(navigateBefore).not.toBe(navigateAfter);
  });

  it("TC-13: hash containing an encoded # (%23) is preserved without decoding", () => {
    // slice(1) is a plain string operation — the hook does not call
    // decodeURIComponent. A %23 in the hash must reach state as-is.
    window.location.hash = "#foo%23bar";

    const { result } = renderHook(() => useLocationHash("external"));

    expect(result.current[0]).toBe("foo%23bar");
  });

  it("TC-14: URL-encoded spaces (%20) in the hash are preserved without decoding", () => {
    // Same no-decode contract as TC-13, with a different encoded character.
    window.location.hash = "#hello%20world";

    const { result } = renderHook(() => useLocationHash("external"));

    expect(result.current[0]).toBe("hello%20world");
  });

  it("TC-15: changing defaultValue does not reset state when hash is already set", () => {
    // Effect re-registration (due to defaultValue change) must not disturb the
    // current value derived from the live hash. The new defaultValue is only
    // applied the next time a hashchange fires with an empty hash.
    window.location.hash = "#internal";
    const { result, rerender } = renderHook(
      ({ defaultValue }: { defaultValue: string }) =>
        useLocationHash(defaultValue),
      { initialProps: { defaultValue: "external" } },
    );
    expect(result.current[0]).toBe("internal");

    rerender({ defaultValue: "mcp" });

    expect(result.current[0]).toBe("internal");
  });
});
