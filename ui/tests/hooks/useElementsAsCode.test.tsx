/**
 * @jest-environment jsdom
 */
import { describe, it, expect, jest, beforeEach } from "@jest/globals";
import { renderHook, waitFor } from "@testing-library/react";
import { useElementsAsCode } from "../../src/hooks/useElementsAsCode";

const mockGetElementsAsCode = jest.fn<
  (chainId: string) => Promise<{ code: string }>
>();
const mockRequestFailed = jest.fn();

jest.mock("../../src/api/api", () => ({
  api: {
    getElementsAsCode: (chainId: string) => mockGetElementsAsCode(chainId),
  },
}));

jest.mock("../../src/hooks/useNotificationService", () => ({
  useNotificationService: () => ({
    requestFailed: mockRequestFailed,
  }),
}));

describe("useElementsAsCode", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("returns undefined initially", () => {
    mockGetElementsAsCode.mockResolvedValue({ code: "test" });
    const { result } = renderHook(() => useElementsAsCode("chain-1", "sig-1"));
    expect(result.current.elementAsCode).toBeUndefined();
  });

  it("fetches and returns element code when chainId and timestamp provided", async () => {
    const mockCode = { code: "element code content" };
    mockGetElementsAsCode.mockResolvedValue(mockCode);

    const { result } = renderHook(() => useElementsAsCode("chain-1", "sig-1"));

    await waitFor(() => {
      expect(result.current.elementAsCode).toEqual(mockCode);
    });

    expect(mockGetElementsAsCode).toHaveBeenCalledWith("chain-1");
  });

  it("does not fetch when chainId is empty", async () => {
    const { result } = renderHook(() => useElementsAsCode("", "sig-1"));

    await waitFor(() => {
      expect(mockGetElementsAsCode).not.toHaveBeenCalled();
    });

    expect(result.current.elementAsCode).toBeUndefined();
  });

  it("does not fetch when cacheKey is empty", async () => {
    const { result } = renderHook(() => useElementsAsCode("chain-1"));

    await waitFor(() => {
      expect(mockGetElementsAsCode).not.toHaveBeenCalled();
    });

    expect(result.current.elementAsCode).toBeUndefined();
  });

  it("does not refetch when cacheKey is unchanged between renders", async () => {
    mockGetElementsAsCode.mockResolvedValue({ code: "v" });
    const { rerender } = renderHook(
      ({ key }: { key: string }) => useElementsAsCode("chain-1", key),
      { initialProps: { key: "sig-1" } },
    );
    await waitFor(() => expect(mockGetElementsAsCode).toHaveBeenCalledTimes(1));
    rerender({ key: "sig-1" });
    expect(mockGetElementsAsCode).toHaveBeenCalledTimes(1);
  });

  it("refetches when cacheKey changes", async () => {
    mockGetElementsAsCode.mockResolvedValue({ code: "v" });
    const { rerender } = renderHook(
      ({ key }: { key: string }) => useElementsAsCode("chain-1", key),
      { initialProps: { key: "sig-1" } },
    );
    await waitFor(() => expect(mockGetElementsAsCode).toHaveBeenCalledTimes(1));
    rerender({ key: "sig-2" });
    await waitFor(() => expect(mockGetElementsAsCode).toHaveBeenCalledTimes(2));
  });

  it("calls requestFailed on error", async () => {
    const error = new Error("API error");
    mockGetElementsAsCode.mockRejectedValue(error);

    renderHook(() => useElementsAsCode("chain-1", "sig-1"));

    await waitFor(() => {
      expect(mockRequestFailed).toHaveBeenCalledWith(
        "Failed to get elements as code",
        error,
      );
    });
  });

  it("refresh does nothing when chainId is empty", async () => {
    const { result } = renderHook(() => useElementsAsCode(""));
    await result.current.refresh();
    expect(mockGetElementsAsCode).not.toHaveBeenCalled();
  });

  it("refresh returns element code when called", async () => {
    const mockCode = { code: "refreshed code" };
    mockGetElementsAsCode.mockResolvedValue(mockCode);

    const { result } = renderHook(() => useElementsAsCode("chain-1", "sig-1"));

    await waitFor(() => {
      expect(result.current.elementAsCode).toEqual(mockCode);
    });

    mockGetElementsAsCode.mockResolvedValue({ code: "new code" });
    await result.current.refresh();

    await waitFor(() => {
      expect(result.current.elementAsCode).toEqual({ code: "new code" });
    });
  });
});
