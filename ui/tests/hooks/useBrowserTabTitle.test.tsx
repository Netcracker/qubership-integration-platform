/**
 * @jest-environment jsdom
 */
import { renderHook, waitFor } from "@testing-library/react";
import { useBrowserTabTitle } from "../../src/hooks/useBrowserTabTitle";

let mockPathname = "/chains/chain-1/graph";
const mockGetChain = jest.fn();

jest.mock("react-router", () => ({
  useLocation: () => ({ pathname: mockPathname, hash: "" }),
}));

jest.mock("../../src/api/api.ts", () => ({
  api: {
    getChain: (...args: unknown[]) => mockGetChain(...args),
  },
}));

describe("useBrowserTabTitle", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockPathname = "/chains/chain-1/graph";
    mockGetChain.mockResolvedValue({ name: "Test Chain" });
  });

  it("does not reload a chain when switching between its tabs", async () => {
    const { rerender } = renderHook(() => useBrowserTabTitle());

    await waitFor(() => {
      expect(document.title).toBe("Test Chain");
    });
    expect(mockGetChain).toHaveBeenCalledTimes(1);

    mockPathname = "/chains/chain-1/snapshots";
    rerender();

    await waitFor(() => {
      expect(mockGetChain).toHaveBeenCalledTimes(1);
    });
  });
});
