/**
 * @jest-environment jsdom
 */

import { describe, expect, it, beforeEach } from "@jest/globals";
import { act, renderHook, waitFor } from "@testing-library/react";
import {
  TestingFilterCondition,
  TestingSortOrder,
} from "../../../src/api/apiTypes";
import type { Key } from "react";
import type {
  TestCaseView,
  TestingFilter,
  TestingListOptions,
  TestingSelectionSpecification,
} from "../../../src/api/apiTypes";
import {
  EntityFilterModel,
  FilterCondition,
} from "../../../src/components/table/filter/filterTypes";

const mockApi = {
  getChains: jest.fn(),
  getElements: jest.fn(),
  getTestCases: jest.fn(),
  getTestCaseIds: jest.fn(),
  exportTestCases: jest.fn(),
};

const mockRequestFailed = jest.fn();
const mockDownloadFile = jest.fn();

// The hook keeps the service in its dependency lists, so the stub has to be as
// stable as the memoized original; a fresh object per render would refetch forever.
const mockNotificationService = {
  requestFailed: mockRequestFailed,
  errorWithDetails: jest.fn(),
  info: jest.fn(),
  warning: jest.fn(),
};

jest.mock("../../../src/api/api", () => ({
  get api() {
    return mockApi;
  },
}));

jest.mock("../../../src/hooks/useNotificationService", () => ({
  useNotificationService: () => mockNotificationService,
}));

jest.mock("../../../src/misc/download-utils", () => ({
  downloadFile: (file: File) => mockDownloadFile(file),
}));

import type {
  TestingEntityList,
  UseTestingEntityListOptions,
} from "../../../src/hooks/testing/useTestingEntityList";
import {
  testCasesListSource,
  useTestingEntityList,
} from "../../../src/hooks/testing/useTestingEntityList";

const noFilters: EntityFilterModel[] = [];

function testCase(id: string): TestCaseView {
  return { id } as TestCaseView;
}

function lastListCall(): [TestingSelectionSpecification, TestingListOptions] {
  const calls = mockApi.getTestCases.mock.calls;
  return calls[calls.length - 1] as [
    TestingSelectionSpecification,
    TestingListOptions,
  ];
}

function renderList(
  options: Partial<UseTestingEntityListOptions<TestCaseView>> = {},
) {
  return renderHook(() =>
    useTestingEntityList<TestCaseView>({
      source: testCasesListSource,
      filters: noFilters,
      ...options,
    }),
  );
}

type ScopeProps = { chainId?: string; scopeFilters?: TestingFilter[] };

/** Renders the list under a scope the test can move to another chain or run. */
function renderScopedList(initialProps: ScopeProps) {
  return renderHook(
    ({ chainId, scopeFilters }: ScopeProps) =>
      useTestingEntityList<TestCaseView>({
        source: testCasesListSource,
        filters: noFilters,
        chainId,
        scopeFilters,
      }),
    { initialProps },
  );
}

/** Picks rows the way the checkbox column of the table does. */
function select(list: TestingEntityList<TestCaseView>, keys: Key[]): void {
  list.rowSelection.onChange?.(keys, [], { type: "multiple" });
}

/** The scope a run drill-down fixes, memoized on the run in the route. */
function runScope(runId: string): TestingFilter[] {
  return [
    {
      feature: "tests_run_id",
      condition: TestingFilterCondition.IS,
      values: [runId],
    },
  ];
}

/** Renders the list under a search term the test can change to start a request. */
function renderSearchingList(search: string) {
  return renderHook(
    ({ searchString }: { searchString: string }) =>
      useTestingEntityList<TestCaseView>({
        source: testCasesListSource,
        filters: noFilters,
        searchString,
      }),
    { initialProps: { searchString: search } },
  );
}

type Deferred<T> = {
  promise: Promise<T>;
  resolve: (value: T) => void;
  reject: (error: unknown) => void;
};

/** A request the test decides the outcome of, so two can be in flight at once. */
function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((resolveIt, rejectIt) => {
    resolve = resolveIt;
    reject = rejectIt;
  });
  return { promise, resolve, reject };
}

/** Settles a deferred request inside `act`, swallowing the rejection it may carry. */
async function settle<T>(request: Deferred<T>, settleIt: () => void) {
  await act(async () => {
    settleIt();
    await request.promise.catch(() => undefined);
  });
}

describe("useTestingEntityList", () => {
  beforeEach(() => {
    mockApi.getChains.mockResolvedValue([
      { id: "chain-1", name: "Order Intake" },
    ]);
    mockApi.getElements.mockResolvedValue([
      { id: "element-1", name: "HTTP Trigger", children: [] },
    ]);
    mockApi.getTestCases.mockResolvedValue([testCase("case-1")]);
    mockApi.getTestCaseIds.mockResolvedValue(["case-1", "case-2"]);
    mockApi.exportTestCases.mockResolvedValue(new File([""], "cases.zip"));
  });

  it("should send the search text and the mapped filters with the offset alone", async () => {
    const filters: EntityFilterModel[] = [
      {
        column: "name",
        condition: FilterCondition.CONTAINS.id,
        value: "order",
      },
    ];
    const { result } = renderList({ filters, searchString: "intake" });

    await waitFor(() => expect(result.current.items).toHaveLength(1));

    const [specification, options] = lastListCall();
    expect(specification).toEqual({
      searchText: "intake",
      filters: [
        {
          feature: "name",
          condition: TestingFilterCondition.CONTAINS,
          values: ["order"],
        },
      ],
    });
    expect(options).toEqual({ offset: 0 });
  });

  it("should scope the selection to the chain when one is in context", async () => {
    const { result } = renderList({ chainId: "chain-1" });

    await waitFor(() => expect(result.current.items).toHaveLength(1));

    expect(lastListCall()[0].filters).toEqual([
      {
        feature: "chain_id",
        condition: TestingFilterCondition.IS,
        values: ["chain-1"],
      },
    ]);
    expect(mockApi.getElements).toHaveBeenCalledWith("chain-1");
    expect(mockApi.getChains).not.toHaveBeenCalled();
  });

  it("should resolve names from one chains request outside a chain", async () => {
    const { result } = renderList();

    await waitFor(() =>
      expect(result.current.getChainName("chain-1")).toBe("Order Intake"),
    );

    expect(mockApi.getChains).toHaveBeenCalledTimes(1);
    expect(mockApi.getElements).not.toHaveBeenCalled();
    expect(lastListCall()[0]).toEqual({});
    expect(result.current.getChainName("chain-1")).toBe("Order Intake");
    expect(result.current.getChainName("chain-9")).toBe("chain-9");
    expect(result.current.getElementName("element-1")).toBe("element-1");
  });

  it("should not refetch the list when the name caches arrive", async () => {
    const { result } = renderList();

    await waitFor(() =>
      expect(result.current.getChainName("chain-1")).toBe("Order Intake"),
    );
    await waitFor(() => expect(result.current.items).toHaveLength(1));

    expect(mockApi.getTestCases).toHaveBeenCalledTimes(1);
  });

  it("should resolve element names from the chain in context", async () => {
    const { result } = renderList({ chainId: "chain-1" });

    await waitFor(() =>
      expect(result.current.getElementName("element-1")).toBe("HTTP Trigger"),
    );

    expect(result.current.getElementName("element-1")).toBe("HTTP Trigger");
  });

  it("should sort by a field the service accepts", async () => {
    const { result } = renderList({
      initialSortBy: "name",
      initialSortOrder: TestingSortOrder.DESC,
    });

    await waitFor(() => expect(result.current.items).toHaveLength(1));

    expect(lastListCall()[1]).toEqual({
      offset: 0,
      sortBy: "name",
      sortOrder: TestingSortOrder.DESC,
    });
  });

  it("should drop a sort field the service does not accept", async () => {
    const { result } = renderList({ initialSortBy: "trigger_reference" });

    await waitFor(() => expect(result.current.items).toHaveLength(1));

    expect(lastListCall()[1]).toEqual({ offset: 0 });
  });

  it("should append the next page and stop when a page comes back empty", async () => {
    const { result } = renderList();

    await waitFor(() => expect(result.current.items).toHaveLength(1));

    mockApi.getTestCases.mockResolvedValueOnce([testCase("case-2")]);
    act(() => result.current.loadMore());
    await waitFor(() => expect(result.current.items).toHaveLength(2));
    expect(lastListCall()[1]).toEqual({ offset: 1 });
    expect(result.current.allLoaded).toBe(false);

    mockApi.getTestCases.mockResolvedValueOnce([]);
    act(() => result.current.loadMore());
    await waitFor(() => expect(result.current.allLoaded).toBe(true));
    expect(result.current.items).toHaveLength(2);
  });

  it("should reload from the first page on refresh", async () => {
    const { result } = renderList();

    await waitFor(() => expect(result.current.items).toHaveLength(1));

    mockApi.getTestCases.mockClear();
    act(() => result.current.refresh());
    await waitFor(() => expect(mockApi.getTestCases).toHaveBeenCalledTimes(1));
    expect(lastListCall()[1]).toEqual({ offset: 0 });
  });

  it("should resolve the targets of a selection reaching past the loaded page", async () => {
    const { result } = renderList();

    await waitFor(() => expect(result.current.items).toHaveLength(1));

    await expect(
      result.current.resolveTargetIds(["case-1"], false),
    ).resolves.toEqual(["case-1"]);
    expect(mockApi.getTestCaseIds).not.toHaveBeenCalled();

    await expect(
      result.current.resolveTargetIds(["case-1"], true),
    ).resolves.toEqual(["case-1", "case-2"]);
    expect(mockApi.getTestCaseIds).toHaveBeenCalledWith({});
  });

  it("should skip the request when a name filter matches nothing", async () => {
    const filters: EntityFilterModel[] = [
      {
        column: "chain_name",
        condition: FilterCondition.IS.id,
        value: "Missing",
      },
    ];
    const { result } = renderList({ filters });

    await waitFor(() => expect(result.current.allLoaded).toBe(true));

    expect(result.current.items).toEqual([]);
    expect(mockApi.getTestCases).not.toHaveBeenCalled();
    await expect(result.current.resolveTargetIds([], true)).resolves.toEqual(
      [],
    );
  });

  it("should download the exported file", async () => {
    const { result } = renderList();

    await waitFor(() => expect(result.current.items).toHaveLength(1));

    await act(async () => {
      await result.current.exportEntities(["case-1"]);
    });

    expect(mockApi.exportTestCases).toHaveBeenCalledWith(["case-1"]);
    expect(mockDownloadFile).toHaveBeenCalled();
  });

  it("should report a failed page and stop asking for more", async () => {
    mockApi.getTestCases.mockRejectedValueOnce(new Error("service is down"));
    const { result } = renderList();

    await waitFor(() => expect(result.current.allLoaded).toBe(true));

    expect(mockRequestFailed).toHaveBeenCalledWith(
      "Failed to load test cases",
      expect.any(Error),
    );
    expect(result.current.items).toEqual([]);
  });

  it("should let only the newest request write the list", async () => {
    const stale = deferred<TestCaseView[]>();
    mockApi.getTestCases.mockReturnValueOnce(stale.promise);
    const { result, rerender } = renderSearchingList("first");
    await waitFor(() => expect(mockApi.getTestCases).toHaveBeenCalledTimes(1));

    mockApi.getTestCases.mockResolvedValue([testCase("case-newest")]);
    rerender({ searchString: "second" });
    await waitFor(() =>
      expect(result.current.items).toEqual([testCase("case-newest")]),
    );

    await settle(stale, () => stale.resolve([testCase("case-stale")]));

    expect(result.current.items).toEqual([testCase("case-newest")]);
  });

  it("should stay loading when an older request resolves first", async () => {
    const stale = deferred<TestCaseView[]>();
    const newest = deferred<TestCaseView[]>();
    mockApi.getTestCases
      .mockReturnValueOnce(stale.promise)
      .mockReturnValueOnce(newest.promise);
    const { result, rerender } = renderSearchingList("first");
    await waitFor(() => expect(mockApi.getTestCases).toHaveBeenCalledTimes(1));

    rerender({ searchString: "second" });
    await waitFor(() => expect(mockApi.getTestCases).toHaveBeenCalledTimes(2));

    await settle(stale, () => stale.resolve([testCase("case-stale")]));
    expect(result.current.isLoading).toBe(true);

    await settle(newest, () => newest.resolve([testCase("case-newest")]));
    expect(result.current.isLoading).toBe(false);
  });

  it("should not report a request that failed after a newer one replaced it", async () => {
    const stale = deferred<TestCaseView[]>();
    mockApi.getTestCases.mockReturnValueOnce(stale.promise);
    const { result, rerender } = renderSearchingList("first");
    await waitFor(() => expect(mockApi.getTestCases).toHaveBeenCalledTimes(1));

    mockApi.getTestCases.mockResolvedValue([testCase("case-newest")]);
    rerender({ searchString: "second" });
    await waitFor(() =>
      expect(result.current.items).toEqual([testCase("case-newest")]),
    );

    await settle(stale, () => stale.reject(new Error("service is down")));

    expect(mockRequestFailed).not.toHaveBeenCalled();
    expect(result.current.allLoaded).toBe(false);
  });

  it("should not report a request that failed after the screen was gone", async () => {
    const pending = deferred<TestCaseView[]>();
    mockApi.getTestCases.mockReturnValueOnce(pending.promise);
    const { unmount } = renderSearchingList("first");
    await waitFor(() => expect(mockApi.getTestCases).toHaveBeenCalledTimes(1));

    unmount();
    await settle(pending, () => pending.reject(new Error("service is down")));

    expect(mockRequestFailed).not.toHaveBeenCalled();
  });

  // A selection that outlives its scope would hand rows of the chain or the run
  // left behind to a delete, a cancel or an export made in the one now on screen.
  it("should drop the selection when the chain in context changes", async () => {
    const { result, rerender } = renderScopedList({ chainId: "chain-1" });
    await waitFor(() => expect(result.current.items).toHaveLength(1));
    act(() => select(result.current, ["case-1"]));
    expect(result.current.selectedRowKeys).toEqual(["case-1"]);

    rerender({ chainId: "chain-2" });

    await waitFor(() => expect(result.current.selectedRowKeys).toEqual([]));
  });

  it("should drop the selection when the run the route fixes changes", async () => {
    const { result, rerender } = renderScopedList({
      scopeFilters: runScope("run-1"),
    });
    await waitFor(() => expect(result.current.items).toHaveLength(1));
    act(() => select(result.current, ["case-1"]));
    expect(result.current.selectedRowKeys).toEqual(["case-1"]);

    rerender({ scopeFilters: runScope("run-2") });

    await waitFor(() => expect(result.current.selectedRowKeys).toEqual([]));
    expect(result.current.selectAllMatching).toBe(false);
  });

  it("should report a failed export", async () => {
    mockApi.exportTestCases.mockRejectedValueOnce(new Error("export failed"));
    const { result } = renderList();

    await waitFor(() => expect(result.current.items).toHaveLength(1));

    await act(async () => {
      await result.current.exportEntities(["case-1"]);
    });

    expect(mockRequestFailed).toHaveBeenCalledWith(
      "Failed to export test cases",
      expect.any(Error),
    );
    expect(mockDownloadFile).not.toHaveBeenCalled();
  });
});
