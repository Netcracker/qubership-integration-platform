/**
 * @jest-environment jsdom
 */
import { act, renderHook } from "@testing-library/react";
import { useElkDirection } from "../../../src/hooks/graph/useElkDirection";

describe("useElkDirection", () => {
  it("has correct default direction", () => {
    const { result } = renderHook(() => useElkDirection());

    expect(result.current.direction).toBe("RIGHT");
  });

  it("toggleDirection switches between RIGHT and DOWN", () => {
    const { result } = renderHook(() => useElkDirection());

    act(() => result.current.toggleDirection());
    expect(result.current.direction).toBe("DOWN");

    act(() => result.current.toggleDirection());
    expect(result.current.direction).toBe("RIGHT");
  });
});
