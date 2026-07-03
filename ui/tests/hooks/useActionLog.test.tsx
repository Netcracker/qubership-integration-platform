/**
 * @jest-environment jsdom
 */

import React from "react";
import { describe, it, expect, beforeEach } from "@jest/globals";
import { renderHook, waitFor, act } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { EntityType, LogOperation } from "../../src/api/apiTypes.ts";

const mockLoadCatalogActionsLogV2 = jest.fn();
const mockRequestFailed = jest.fn();

jest.mock("../../src/api/api.ts", () => ({
  api: {
    loadCatalogActionsLogV2: (...args: unknown[]) =>
      mockLoadCatalogActionsLogV2(...args),
  },
}));

jest.mock("../../src/hooks/useNotificationService.tsx", () => ({
  useNotificationService: () => ({
    requestFailed: mockRequestFailed,
  }),
}));

import { useActionLog } from "../../src/hooks/useActionLog.tsx";

const makeLog = (id: string, actionTime: number) => ({
  id,
  actionTime,
  entityType: EntityType.CHAIN,
  operation: LogOperation.CREATE,
});

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
    },
  });
  return ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
}

describe("useActionLog", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("loads action logs on mount via v2", async () => {
    mockLoadCatalogActionsLogV2.mockResolvedValue({
      offset: 1,
      actionLogs: [makeLog("1", 100)],
    });

    const { result } = renderHook(() => useActionLog(), {
      wrapper: createWrapper(),
    });

    await waitFor(() => expect(result.current.isLoading).toBe(false));

    expect(result.current.logsData).toHaveLength(1);
    expect(mockLoadCatalogActionsLogV2).toHaveBeenCalledWith({
      offset: 0,
      limit: 20,
      filters: [],
    });
  });

  it("sorts logs by actionTime descending across pages", async () => {
    mockLoadCatalogActionsLogV2.mockResolvedValue({
      offset: 2,
      actionLogs: [makeLog("older", 100), makeLog("newer", 200)],
    });

    const { result } = renderHook(() => useActionLog(), {
      wrapper: createWrapper(),
    });

    await waitFor(() => expect(result.current.isLoading).toBe(false));

    expect(result.current.logsData.map((log) => log.id)).toEqual([
      "newer",
      "older",
    ]);
  });

  it("sets hasNextPage when a full page is returned", async () => {
    const fullPage = Array.from({ length: 20 }, (_, index) =>
      makeLog(`log-${index}`, index),
    );
    mockLoadCatalogActionsLogV2.mockResolvedValue({
      offset: 20,
      actionLogs: fullPage,
    });

    const { result } = renderHook(() => useActionLog(), {
      wrapper: createWrapper(),
    });

    await waitFor(() => expect(result.current.isLoading).toBe(false));

    expect(result.current.hasNextPage).toBe(true);
  });

  it("fetches the next page with the same filters", async () => {
    const filters = [
      { column: "INITIATOR", condition: "CONTAINS", value: "alice" },
    ];
    const fullPage = Array.from({ length: 20 }, (_, index) =>
      makeLog(`log-${index}`, index),
    );
    mockLoadCatalogActionsLogV2
      .mockResolvedValueOnce({
        offset: 20,
        actionLogs: fullPage,
      })
      .mockResolvedValueOnce({
        offset: 21,
        actionLogs: [makeLog("log-20", 100)],
      });

    const { result } = renderHook(() => useActionLog(filters), {
      wrapper: createWrapper(),
    });

    await waitFor(() => expect(result.current.isLoading).toBe(false));

    await act(async () => {
      await result.current.fetchNextPage();
    });

    await waitFor(() => expect(result.current.logsData).toHaveLength(21));

    expect(mockLoadCatalogActionsLogV2).toHaveBeenNthCalledWith(1, {
      offset: 0,
      limit: 20,
      filters,
    });
    expect(mockLoadCatalogActionsLogV2).toHaveBeenNthCalledWith(2, {
      offset: 20,
      limit: 20,
      filters,
    });
  });

  it("reports API errors and stops pagination", async () => {
    const error = new Error("catalog unavailable");
    mockLoadCatalogActionsLogV2.mockRejectedValue(error);

    const { result } = renderHook(() => useActionLog(), {
      wrapper: createWrapper(),
    });

    await waitFor(() => expect(result.current.isLoading).toBe(false));

    expect(mockRequestFailed).toHaveBeenCalledWith(
      "Failed to retrieve action logs from Catalog",
      error,
    );
    expect(result.current.hasNextPage).toBe(false);
    expect(result.current.logsData).toEqual([]);
  });

  it("refresh reloads from offset 0", async () => {
    mockLoadCatalogActionsLogV2.mockResolvedValue({
      offset: 1,
      actionLogs: [makeLog("1", 100)],
    });

    const { result } = renderHook(() => useActionLog(), {
      wrapper: createWrapper(),
    });

    await waitFor(() => expect(result.current.isLoading).toBe(false));

    await act(async () => {
      await result.current.refresh();
    });

    await waitFor(() =>
      expect(mockLoadCatalogActionsLogV2).toHaveBeenCalledTimes(2),
    );

    expect(mockLoadCatalogActionsLogV2).toHaveBeenLastCalledWith({
      offset: 0,
      limit: 20,
      filters: [],
    });
  });
});
