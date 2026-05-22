/**
 * @jest-environment jsdom
 */
import { describe, it, expect, jest, beforeEach } from "@jest/globals";
import { renderHook, waitFor, act } from "@testing-library/react";
import type { Chain } from "../../../../src/api/apiTypes";
import type { Change } from "../../../../src/components/chains/diff/compare/types";
import type { ComparableItem } from "../../../../src/components/chains/diff/useChainDiff";

const mockGetChain = jest.fn();
const mockRequestFailed = jest.fn();
const mockErrorWithDetails = jest.fn();
const mockCompareChains = jest.fn();
const stableNotificationService = {
  requestFailed: mockRequestFailed,
  errorWithDetails: mockErrorWithDetails,
};

jest.mock("../../../../src/api/api", () => ({
  api: {
    getChain: (id: string) => mockGetChain(id),
  },
}));

jest.mock("../../../../src/hooks/useNotificationService", () => ({
  useNotificationService: () => stableNotificationService,
}));

jest.mock("../../../../src/components/chains/diff/compare/compare", () => ({
  compareChains: (...args: unknown[]) => mockCompareChains(...args),
}));

import { useChainDiff } from "../../../../src/components/chains/diff/useChainDiff";

const chain1 = { id: "chain-1", name: "Alpha" } as unknown as Chain;
const chain2 = { id: "chain-2", name: "Beta" } as unknown as Chain;

const item1: ComparableItem = { kind: "chain", id: "chain-1" };
const item2: ComparableItem = { kind: "chain", id: "chain-2" };

function resolveById(id: string): Promise<Chain> {
  if (id === "chain-1") return Promise.resolve(chain1);
  if (id === "chain-2") return Promise.resolve(chain2);
  return Promise.reject(new Error(`Unknown chain: ${id}`));
}

describe("useChainDiff", () => {
  beforeEach(() => {
    mockCompareChains.mockReturnValue([]);
  });

  it("should return undefined chains, empty changes, and undefined selectedChangeId when first rendered", () => {
    mockGetChain.mockImplementation(resolveById);

    const { result } = renderHook(() => useChainDiff(item1, item2));

    expect(result.current.chain1).toBeUndefined();
    expect(result.current.chain2).toBeUndefined();
    expect(result.current.changes).toEqual([]);
    expect(result.current.selectedChangeId).toBeUndefined();
  });

  it("should call api.getChain for chainId1 and chainId2 when rendered", async () => {
    mockGetChain.mockImplementation(resolveById);

    renderHook(() => useChainDiff(item1, item2));

    await waitFor(() => {
      expect(mockGetChain).toHaveBeenCalledWith("chain-1");
      expect(mockGetChain).toHaveBeenCalledWith("chain-2");
    });
  });

  it("should set chain1 to the resolved value when the api call for chainId1 succeeds", async () => {
    mockGetChain.mockImplementation(resolveById);

    const { result } = renderHook(() => useChainDiff(item1, item2));

    await waitFor(() => {
      expect(result.current.chain1).toEqual(chain1);
    });
  });

  it("should set chain2 to the resolved value when the api call for chainId2 succeeds", async () => {
    mockGetChain.mockImplementation(resolveById);

    const { result } = renderHook(() => useChainDiff(item1, item2));

    await waitFor(() => {
      expect(result.current.chain2).toEqual(chain2);
    });
  });

  it("should set isLoading to true while chains are being fetched", () => {
    mockGetChain.mockReturnValue(new Promise<Chain>(() => {}));

    const { result } = renderHook(() => useChainDiff(item1, item2));

    expect(result.current.isLoading).toBe(true);
  });

  it("should set isLoading to false after both chains finish loading", async () => {
    mockGetChain.mockImplementation(resolveById);

    const { result } = renderHook(() => useChainDiff(item1, item2));

    await waitFor(() => {
      expect(result.current.isLoading).toBe(false);
    });
  });

  it("should call compareChains with chain1 and chain2 when both chains have loaded", async () => {
    mockGetChain.mockImplementation(resolveById);

    renderHook(() => useChainDiff(item1, item2));

    await waitFor(() => {
      expect(mockCompareChains).toHaveBeenCalledWith(chain1, chain2);
    });
  });

  it("should set changes to the result returned by compareChains when comparison succeeds", async () => {
    const changes: Change[] = [{ id: "c1", kind: "element" } as Change];
    mockGetChain.mockImplementation(resolveById);
    mockCompareChains.mockReturnValue(changes);

    const { result } = renderHook(() => useChainDiff(item1, item2));

    await waitFor(() => {
      expect(result.current.changes).toEqual(changes);
    });
  });

  it("should not call compareChains when only one chain has loaded", async () => {
    mockGetChain.mockImplementation((id: string) =>
      id === "chain-1"
        ? Promise.resolve(chain1)
        : Promise.reject(new Error("Not found")),
    );

    renderHook(() => useChainDiff(item1, item2));

    await waitFor(() => {
      expect(mockRequestFailed).toHaveBeenCalled();
    });

    expect(mockCompareChains).not.toHaveBeenCalled();
  });

  it("should call notificationService.requestFailed when fetching chain1 throws", async () => {
    const error = new Error("Chain 1 load failed");
    mockGetChain.mockImplementation((id: string) =>
      id === "chain-1" ? Promise.reject(error) : Promise.resolve(chain2),
    );

    renderHook(() => useChainDiff(item1, item2));

    await waitFor(() => {
      expect(mockRequestFailed).toHaveBeenCalledWith(
        "Failed to load chain",
        error,
      );
    });
  });

  it("should call notificationService.requestFailed when fetching chain2 throws", async () => {
    const error = new Error("Chain 2 load failed");
    mockGetChain.mockImplementation((id: string) =>
      id === "chain-1" ? Promise.resolve(chain1) : Promise.reject(error),
    );

    renderHook(() => useChainDiff(item1, item2));

    await waitFor(() => {
      expect(mockRequestFailed).toHaveBeenCalledWith(
        "Failed to load chain",
        error,
      );
    });
  });

  it("should call notificationService.errorWithDetails when compareChains throws", async () => {
    const error = new Error("Compare failed");
    mockGetChain.mockImplementation(resolveById);
    mockCompareChains.mockImplementation(() => {
      throw error;
    });

    renderHook(() => useChainDiff(item1, item2));

    await waitFor(() => {
      expect(mockErrorWithDetails).toHaveBeenCalledWith(
        "Failed to compare chains",
        "",
        error,
      );
    });
  });

  it("should keep changes as an empty array when compareChains throws", async () => {
    mockGetChain.mockImplementation(resolveById);
    mockCompareChains.mockImplementation(() => {
      throw new Error("Compare failed");
    });

    const { result } = renderHook(() => useChainDiff(item1, item2));

    await waitFor(() => {
      expect(mockErrorWithDetails).toHaveBeenCalled();
    });

    expect(result.current.changes).toEqual([]);
  });

  it("should reload chain1 and re-run comparison when chainId1 changes", async () => {
    const chain3 = { id: "chain-3", name: "Gamma" } as unknown as Chain;
    mockGetChain.mockImplementation((id: string) => {
      if (id === "chain-1") return Promise.resolve(chain1);
      if (id === "chain-2") return Promise.resolve(chain2);
      if (id === "chain-3") return Promise.resolve(chain3);
      return Promise.reject(new Error(`Unknown: ${id}`));
    });

    const { result, rerender } = renderHook(
      ({ i1, i2 }: { i1: ComparableItem; i2: ComparableItem }) =>
        useChainDiff(i1, i2),
      {
        initialProps: {
          i1: { kind: "chain" as const, id: "chain-1" },
          i2: { kind: "chain" as const, id: "chain-2" },
        },
      },
    );

    await waitFor(() => {
      expect(result.current.chain1).toEqual(chain1);
    });

    rerender({
      i1: { kind: "chain" as const, id: "chain-3" },
      i2: { kind: "chain" as const, id: "chain-2" },
    });

    await waitFor(() => {
      expect(result.current.chain1).toEqual(chain3);
    });
    expect(mockGetChain).toHaveBeenCalledWith("chain-3");
  });

  it("should reload chain2 and re-run comparison when chainId2 changes", async () => {
    const chain4 = { id: "chain-4", name: "Delta" } as unknown as Chain;
    mockGetChain.mockImplementation((id: string) => {
      if (id === "chain-1") return Promise.resolve(chain1);
      if (id === "chain-2") return Promise.resolve(chain2);
      if (id === "chain-4") return Promise.resolve(chain4);
      return Promise.reject(new Error(`Unknown: ${id}`));
    });

    const { result, rerender } = renderHook(
      ({ i1, i2 }: { i1: ComparableItem; i2: ComparableItem }) =>
        useChainDiff(i1, i2),
      {
        initialProps: {
          i1: { kind: "chain" as const, id: "chain-1" },
          i2: { kind: "chain" as const, id: "chain-2" },
        },
      },
    );

    await waitFor(() => {
      expect(result.current.chain2).toEqual(chain2);
    });

    rerender({
      i1: { kind: "chain" as const, id: "chain-1" },
      i2: { kind: "chain" as const, id: "chain-4" },
    });

    await waitFor(() => {
      expect(result.current.chain2).toEqual(chain4);
    });
    expect(mockGetChain).toHaveBeenCalledWith("chain-4");
  });

  it("should update selectedChangeId when setSelectedChangeId is called", () => {
    mockGetChain.mockImplementation(resolveById);

    const { result } = renderHook(() => useChainDiff(item1, item2));

    expect(result.current.selectedChangeId).toBeUndefined();

    act(() => {
      result.current.setSelectedChangeId("change-1");
    });

    expect(result.current.selectedChangeId).toBe("change-1");
  });
});
