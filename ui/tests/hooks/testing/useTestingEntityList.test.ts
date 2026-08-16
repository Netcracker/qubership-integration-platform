/**
 * @jest-environment jsdom
 */

import { describe, expect, it, beforeEach } from "@jest/globals";
import { act, renderHook, waitFor } from "@testing-library/react";
import {
  TestingFilterCondition,
  TestingSortOrder,
} from "../../../src/api/apiTypes";
import type {
  TestCaseView,
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

import type { UseTestingEntityListOptions } from "../../../src/hooks/testing/useTestingEntityList";
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

    await waitFor(() => expect(result.current.chains).toHaveLength(1));

    expect(mockApi.getChains).toHaveBeenCalledTimes(1);
    expect(mockApi.getElements).not.toHaveBeenCalled();
    expect(lastListCall()[0]).toEqual({});
    expect(result.current.getChainName("chain-1")).toBe("Order Intake");
    expect(result.current.getChainName("chain-9")).toBe("chain-9");
    expect(result.current.getElementName("element-1")).toBe("element-1");
  });

  it("should not refetch the list when the name caches arrive", async () => {
    const { result } = renderList();

    await waitFor(() => expect(result.current.chains).toHaveLength(1));
    await waitFor(() => expect(result.current.items).toHaveLength(1));

    expect(mockApi.getTestCases).toHaveBeenCalledTimes(1);
  });

  it("should resolve element names from the chain in context", async () => {
    const { result } = renderList({ chainId: "chain-1" });

    await waitFor(() => expect(result.current.elements).toHaveLength(1));

    expect(result.current.getElementName("element-1")).toBe("HTTP Trigger");
  });

  it("should sort by a field the service accepts", async () => {
    const { result } = renderList({
      sortBy: "name",
      sortOrder: TestingSortOrder.DESC,
    });

    await waitFor(() => expect(result.current.items).toHaveLength(1));

    expect(lastListCall()[1]).toEqual({
      offset: 0,
      sortBy: "name",
      sortOrder: TestingSortOrder.DESC,
    });
  });

  it("should drop a sort field the service does not accept", async () => {
    const { result } = renderList({ sortBy: "trigger_reference" });

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
