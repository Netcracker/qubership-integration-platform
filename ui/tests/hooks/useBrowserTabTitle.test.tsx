/**
 * @jest-environment jsdom
 */
import { renderHook, waitFor } from "@testing-library/react";
import { useBrowserTabTitle } from "../../src/hooks/useBrowserTabTitle";

let mockPathname = "/chains/chain-1/graph";
const mockGetChain = jest.fn();
const mockGetService = jest.fn();
const mockGetContextService = jest.fn();
const mockGetMcpSystem = jest.fn();

jest.mock("react-router", () => ({
  useLocation: () => ({ pathname: mockPathname, hash: "" }),
}));

jest.mock("../../src/api/api.ts", () => ({
  api: {
    getChain: (...args: unknown[]) => mockGetChain(...args),
    getService: (...args: unknown[]) => mockGetService(...args),
    getContextService: (...args: unknown[]) => mockGetContextService(...args),
    getMcpSystem: (...args: unknown[]) => mockGetMcpSystem(...args),
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

  it.each([
    [
      "context",
      "/services/context/ctx-1/parameters",
      mockGetContextService,
      "ctx-1",
    ],
    ["mcp", "/services/mcp/mcp-1/parameters", mockGetMcpSystem, "mcp-1"],
  ])(
    "should name the tab after the %s service from its own endpoint",
    async (_, pathname, getter, id) => {
      mockPathname = pathname;
      getter.mockResolvedValue({ name: "Named Service" });

      renderHook(() => useBrowserTabTitle());

      await waitFor(() => {
        expect(document.title).toBe("Named Service");
      });
      expect(getter).toHaveBeenCalledWith(id);
      expect(mockGetService).not.toHaveBeenCalled();
    },
  );
});
