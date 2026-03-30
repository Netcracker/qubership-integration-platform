/**
 * @jest-environment jsdom
 */
import { act, renderHook } from "@testing-library/react";
import { useElkDirection } from "../../../src/hooks/graph/useElkDirection";

describe("useElkDirection", () => {
  it("has correct default values", () => {
    const { result } = renderHook(() => useElkDirection());

    expect(result.current.direction).toBe("RIGHT");
    expect(result.current.leftPanel).toBe(true);
    expect(result.current.rightPanel).toBe(false);
  });

  it("toggleDirection switches between RIGHT and DOWN", () => {
    const { result } = renderHook(() => useElkDirection());

    act(() => result.current.toggleDirection());
    expect(result.current.direction).toBe("DOWN");

    act(() => result.current.toggleDirection());
    expect(result.current.direction).toBe("RIGHT");
  });

  it("toggleLeftPanel toggles leftPanel state", () => {
    const { result } = renderHook(() => useElkDirection());

    expect(result.current.leftPanel).toBe(true);

    act(() => result.current.toggleLeftPanel());
    expect(result.current.leftPanel).toBe(false);

    act(() => result.current.toggleLeftPanel());
    expect(result.current.leftPanel).toBe(true);
  });

  it("toggleRightPanel toggles rightPanel state", () => {
    const { result } = renderHook(() => useElkDirection());

    expect(result.current.rightPanel).toBe(false);

    act(() => result.current.toggleRightPanel());
    expect(result.current.rightPanel).toBe(true);

    act(() => result.current.toggleRightPanel());
    expect(result.current.rightPanel).toBe(false);
  });
});
