/**
 * @jest-environment jsdom
 */

import { act, renderHook } from "@testing-library/react";
import { useVSCodeTheme } from "../../src/hooks/useVSCodeTheme.ts";

jest.mock("../../src/utils/vscodeApi.ts", () => ({
  __esModule: true,
  default: {
    getInstance: () => ({
      getApi: () => undefined,
      isAvailable: () => false,
      sendMessage: jest.fn(),
    }),
  },
}));

function postTheme(isDark: boolean, origin = window.origin): void {
  act(() => {
    window.dispatchEvent(
      new MessageEvent("message", {
        data: {
          type: "theme-update",
          payload: { kind: isDark ? 2 : 1, isDark, colors: {} },
        },
        origin,
      }),
    );
  });
}

describe("useVSCodeTheme", () => {
  it("should ignore a theme when the message comes from another origin", () => {
    const { result } = renderHook(() => useVSCodeTheme());
    postTheme(true);
    expect(result.current.isDark).toBe(true);

    postTheme(false, "https://example.com");

    expect(result.current.isDark).toBe(true);
  });
});
