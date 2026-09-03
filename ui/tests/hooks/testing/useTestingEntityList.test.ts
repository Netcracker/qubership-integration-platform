/**
 * @jest-environment jsdom
 */

import { describe, expect, it, beforeEach } from "@jest/globals";
import { act, renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  TestingFilterCondition,
  TestingSortOrder,
} from "../../../src/api/apiTypes";
import { createElement } from "react";
import type { Key, ReactNode } from "react";
import type {
  TestCaseView,
  TestingFilter,
  TestingListOptions,
  TestingSelectionSpecification,
} from "../../../src/api/apiTypes";
import type { SelectionItem } from "antd/es/table/interface";
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
  NAMES_STALE_TIME_MS,
  testCasesListSource,
  useTestingEntityList,
} from "../../../src/hooks/testing/useTestingEntityList";

const noFilters: EntityFilterModel[] = [];

/** As the runs lists are inside a chain: they name no element. */
const runsLikeSource = { ...testCasesListSource, usesElementNames: false };

/** The client the app mounts at its root; the name lookups are cached in it. */
function wrapperFor(queryClient: QueryClient) {
  return ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: queryClient }, children);
}

/** As the app configures its client: mount refetching is left to the query. */
function appQueryClient() {
  return new QueryClient({
    defaultOptions: { queries: { refetchOnMount: false } },
  });
}

/** Ages the cached names past the window, as a tab left open long enough does. */
function expireNames(queryClient: QueryClient) {
  const staleAt = Date.now() - NAMES_STALE_TIME_MS - 1;
  for (const query of queryClient.getQueryCache().getAll()) {
    queryClient.setQueryData(query.queryKey, (names) => names, {
      updatedAt: staleAt,
    });
  }
}

/** Waits for a failed name request to reach the hook; the cache hears of it first. */
async function awaitNamesFailure(queryClient: QueryClient) {
  await waitFor(() =>
    expect(
      queryClient
        .getQueryCache()
        .getAll()
        .some((query) => query.state.status === "error"),
    ).toBe(true),
  );
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 0));
  });
}

/** As much of an element as the name lookup reads. */
type StubElement = { id: string; name: string; children: [] };

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
  queryClient: QueryClient = new QueryClient(),
) {
  return renderHook(
    () =>
      useTestingEntityList<TestCaseView>({
        source: testCasesListSource,
        filters: noFilters,
        ...options,
      }),
    { wrapper: wrapperFor(queryClient) },
  );
}

type ScopeProps = {
  chainId?: string;
  scopeFilters?: TestingFilter[];
  filters?: EntityFilterModel[];
};

/** Renders the list under a scope the test can move to another chain or run. */
function renderScopedList(initialProps: ScopeProps) {
  return renderHook(
    ({ chainId, scopeFilters, filters }: ScopeProps) =>
      useTestingEntityList<TestCaseView>({
        source: testCasesListSource,
        filters: filters ?? noFilters,
        chainId,
        scopeFilters,
      }),
    { initialProps, wrapper: wrapperFor(new QueryClient()) },
  );
}

/** Picks rows the way the checkbox column of the table does. */
function select(list: TestingEntityList<TestCaseView>, keys: Key[]): void {
  list.rowSelection.onChange?.(keys, [], { type: "multiple" });
}

/** Picks the selection reaching past the loaded page, as the table's dropdown does. */
function selectAllMatching(list: TestingEntityList<TestCaseView>): void {
  const selections = list.rowSelection.selections;
  const option = (Array.isArray(selections) ? selections : []).find(
    (selection): selection is SelectionItem =>
      typeof selection === "object" && selection.key === "all-matching",
  );
  expect(option).toBeDefined();
  option?.onSelect?.([]);
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
    {
      initialProps: { searchString: search },
      wrapper: wrapperFor(new QueryClient()),
    },
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

  it("should not read the chain names again when the list is mounted again", async () => {
    const queryClient = new QueryClient();
    const first = renderList({}, queryClient);
    await waitFor(() =>
      expect(first.result.current.getChainName("chain-1")).toBe("Order Intake"),
    );
    first.unmount();

    const second = renderList({}, queryClient);

    await waitFor(() =>
      expect(second.result.current.getChainName("chain-1")).toBe(
        "Order Intake",
      ),
    );
    expect(mockApi.getChains).toHaveBeenCalledTimes(1);
  });

  // The app's client refetches on neither mount, focus nor reconnect, so the
  // finite window only means something while the query asks for the mount one.
  it("should read the chain names again when the list is mounted past the stale window", async () => {
    const queryClient = appQueryClient();
    const first = renderList({}, queryClient);
    await waitFor(() =>
      expect(first.result.current.getChainName("chain-1")).toBe("Order Intake"),
    );
    first.unmount();
    expireNames(queryClient);
    mockApi.getChains.mockResolvedValue([
      { id: "chain-1", name: "Order Export" },
    ]);

    const second = renderList({}, queryClient);

    await waitFor(() =>
      expect(second.result.current.getChainName("chain-1")).toBe(
        "Order Export",
      ),
    );
    expect(mockApi.getChains).toHaveBeenCalledTimes(2);
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

    act(() => select(result.current, ["case-1"]));
    await expect(result.current.collectTargetIds()).resolves.toEqual([
      "case-1",
    ]);
    expect(mockApi.getTestCaseIds).not.toHaveBeenCalled();

    act(() => selectAllMatching(result.current));
    await expect(result.current.collectTargetIds()).resolves.toEqual([
      "case-1",
      "case-2",
    ]);
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
    await expect(result.current.collectTargetIds()).resolves.toEqual([]);
  });

  // The dropdown is offered while the list is still loading, so a bulk action can
  // be aimed at filters no specification has been built for yet. Resolving them
  // would ask the service for every row of the installation.
  it("should resolve to no id when the selection reaches past filters that are still waiting for names", async () => {
    const pending = deferred<{ id: string; name: string }[]>();
    mockApi.getChains.mockReturnValueOnce(pending.promise);
    const filters: EntityFilterModel[] = [
      {
        column: "chain_name",
        condition: FilterCondition.IS.id,
        value: "Order Intake",
      },
    ];
    const { result } = renderList({ filters });

    await waitFor(() => expect(mockApi.getChains).toHaveBeenCalled());
    expect(mockApi.getTestCases).not.toHaveBeenCalled();

    act(() => selectAllMatching(result.current));
    expect(result.current.selectAllMatching).toBe(true);

    await expect(result.current.collectTargetIds()).resolves.toEqual([]);
    expect(mockApi.getTestCaseIds).not.toHaveBeenCalled();

    await settle(pending, () =>
      pending.resolve([{ id: "chain-1", name: "Order Intake" }]),
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

  it("should ask once for a burst of keystrokes", async () => {
    const { rerender } = renderSearchingList("");
    await waitFor(() => expect(mockApi.getTestCases).toHaveBeenCalledTimes(1));

    rerender({ searchString: "o" });
    rerender({ searchString: "or" });
    rerender({ searchString: "ord" });

    await waitFor(() => expect(lastListCall()[0].searchText).toBe("ord"));
    expect(mockApi.getTestCases).toHaveBeenCalledTimes(2);
  });

  it("should ask straight away when the search is confirmed", async () => {
    const { result, rerender } = renderSearchingList("");
    await waitFor(() => expect(mockApi.getTestCases).toHaveBeenCalledTimes(1));

    rerender({ searchString: "ord" });
    act(() => result.current.confirmSearch());

    // Asserted without waiting: a debounce still running would not have asked.
    expect(mockApi.getTestCases).toHaveBeenCalledTimes(2);
    expect(lastListCall()[0].searchText).toBe("ord");

    // The timer left over settles on the same text rather than asking again.
    await act(() => new Promise((resolve) => setTimeout(resolve, 400)));
    expect(mockApi.getTestCases).toHaveBeenCalledTimes(2);
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

  // Element names belong to the chain they were read for. Resolving a filter
  // written against them under another chain sends the ids of the chain left
  // behind: a negated filter then excludes nothing, and every row of the chain
  // now on screen answers a bulk delete, cancel or export.
  const notTheTrigger: EntityFilterModel[] = [
    {
      column: "element_name",
      condition: FilterCondition.IS_NOT.id,
      value: "HTTP Trigger",
    },
  ];

  /** The element filter of a request, or undefined when it carries none. */
  function elementFilterOf(
    specification: TestingSelectionSpecification,
  ): TestingFilter | undefined {
    return specification.filters?.find(
      (filter) => filter.feature === "element_id",
    );
  }

  it("should hold a name filter until the elements of the chain in context arrive", async () => {
    const { result, rerender } = renderScopedList({
      chainId: "chain-1",
      filters: notTheTrigger,
    });
    await waitFor(() => expect(mockApi.getTestCases).toHaveBeenCalledTimes(1));
    expect(elementFilterOf(lastListCall()[0])).toEqual({
      feature: "element_id",
      condition: TestingFilterCondition.NOT_IN,
      values: ["element-1"],
    });

    const pending = deferred<StubElement[]>();
    mockApi.getElements.mockReturnValueOnce(pending.promise);
    mockApi.getTestCases.mockClear();
    rerender({ chainId: "chain-2", filters: notTheTrigger });

    await waitFor(() => expect(result.current.isLoading).toBe(true));
    expect(mockApi.getTestCases).not.toHaveBeenCalled();
    expect(result.current.items).toEqual([]);

    await settle(pending, () =>
      pending.resolve([
        { id: "element-2", name: "HTTP Trigger", children: [] },
      ]),
    );

    await waitFor(() => expect(mockApi.getTestCases).toHaveBeenCalledTimes(1));
    expect(elementFilterOf(lastListCall()[0])).toEqual({
      feature: "element_id",
      condition: TestingFilterCondition.NOT_IN,
      values: ["element-2"],
    });
  });

  it("should ask for no rows while a name filter has no elements to resolve against", async () => {
    const { result, rerender } = renderScopedList({
      chainId: "chain-1",
      filters: notTheTrigger,
    });
    await waitFor(() => expect(mockApi.getTestCases).toHaveBeenCalledTimes(1));

    mockApi.getElements.mockRejectedValueOnce(new Error("service is down"));
    mockApi.getTestCases.mockClear();
    rerender({ chainId: "chain-2", filters: notTheTrigger });

    await waitFor(() =>
      expect(mockRequestFailed).toHaveBeenCalledWith(
        "Failed to resolve names",
        expect.any(Error),
      ),
    );
    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(mockApi.getTestCases).not.toHaveBeenCalled();
    expect(result.current.items).toEqual([]);
    await expect(result.current.collectTargetIds()).resolves.toEqual([]);
  });

  // A refresh past the stale window fails over names the cache still holds, and
  // those name the same rows they named a minute ago: the list keeps its filter
  // rather than emptying itself beside cells that spell the names out.
  it("should keep a name filter resolved when a refresh of the names fails", async () => {
    const filters: EntityFilterModel[] = [
      {
        column: "chain_name",
        condition: FilterCondition.IS.id,
        value: "Order Intake",
      },
    ];
    const queryClient = appQueryClient();
    const first = renderList({ filters }, queryClient);
    await waitFor(() => expect(mockApi.getTestCases).toHaveBeenCalledTimes(1));
    first.unmount();
    expireNames(queryClient);
    mockApi.getTestCases.mockClear();
    const refresh = deferred<{ id: string; name: string }[]>();
    mockApi.getChains.mockReturnValueOnce(refresh.promise);

    const second = renderList({ filters }, queryClient);
    await waitFor(() => expect(mockApi.getChains).toHaveBeenCalledTimes(2));
    await settle(refresh, () => refresh.reject(new Error("service is down")));
    await awaitNamesFailure(queryClient);

    expect(second.result.current.items).toHaveLength(1);
    expect(second.result.current.allLoaded).toBe(false);
    expect(second.result.current.getChainName("chain-1")).toBe("Order Intake");
    expect(mockApi.getTestCases).toHaveBeenCalledTimes(1);
    expect(lastListCall()[0].filters).toEqual([
      {
        feature: "chain_id",
        condition: TestingFilterCondition.IN,
        values: ["chain-1"],
      },
    ]);
    expect(mockRequestFailed).not.toHaveBeenCalled();
  });

  // The tabs of one chain share the cache entry, so a failure of the tab that
  // reads names must not surface on the tab beside it, which issues no request.
  it("should report no name failure on a list that names no element", async () => {
    mockApi.getElements.mockRejectedValue(new Error("service is down"));
    const queryClient = new QueryClient();
    const named = renderList({ chainId: "chain-1" }, queryClient);
    await waitFor(() =>
      expect(mockRequestFailed).toHaveBeenCalledWith(
        "Failed to resolve names",
        expect.any(Error),
      ),
    );
    named.unmount();
    mockRequestFailed.mockClear();
    mockApi.getElements.mockClear();
    mockApi.getTestCases.mockClear();

    renderList({ chainId: "chain-1", source: runsLikeSource }, queryClient);

    await waitFor(() => expect(mockApi.getTestCases).toHaveBeenCalled());
    expect(mockApi.getElements).not.toHaveBeenCalled();
    expect(mockRequestFailed).not.toHaveBeenCalled();
  });

  it("should stop naming the elements of the chain left behind", async () => {
    const { result, rerender } = renderScopedList({ chainId: "chain-1" });
    await waitFor(() =>
      expect(result.current.getElementName("element-1")).toBe("HTTP Trigger"),
    );

    const pending = deferred<StubElement[]>();
    mockApi.getElements.mockReturnValueOnce(pending.promise);
    rerender({ chainId: "chain-2" });

    expect(result.current.getElementName("element-1")).toBe("element-1");

    await settle(pending, () =>
      pending.resolve([{ id: "element-2", name: "Sender", children: [] }]),
    );
    expect(result.current.getElementName("element-2")).toBe("Sender");
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
