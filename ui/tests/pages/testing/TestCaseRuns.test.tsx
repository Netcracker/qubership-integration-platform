/**
 * @jest-environment jsdom
 */
import React from "react";
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import "@testing-library/jest-dom";
import {
  Session,
  TestCaseRunView,
  TestingFilterCondition,
  TestingSelectionSpecification,
  TestingSortOrder,
  TestRunStatus,
  TestsRunSource,
} from "../../../src/api/apiTypes.ts";
import { api } from "../../../src/api/api.ts";
import { UserPermissionsContext } from "../../../src/permissions/UserPermissionsContext.tsx";
import type { UserPermissions } from "../../../src/permissions/types.ts";
import {
  TEST_CASE_RUNS_SORT_FIELDS,
  useTestingFilter,
} from "../../../src/hooks/filter/useTestingFilter.ts";
import type { EntityFilterModel } from "../../../src/components/table/filter/filterTypes.ts";
import { FilterCondition } from "../../../src/components/table/filter/filterTypes.ts";
import {
  getLastTableOnChange,
  LightweightTable as mockLightweightTable,
} from "../../__mocks__/LightweightTable.tsx";
import { TestCaseRuns } from "../../../src/pages/testing/TestCaseRuns.tsx";
import { ChainHeaderTestRoot } from "../../helpers/renderWithChainHeader.tsx";
import { triggerIntersection } from "../../setup/intersection-observer.ts";

let capturedConfirm:
  | { title: React.ReactNode; content?: React.ReactNode; onOk: () => unknown }
  | undefined;

/** A column as the page hands it to the table, before the table reads it. */
type RenderedColumn = {
  key?: React.Key;
  title?: React.ReactNode;
  sorter?: unknown;
};

let mockRenderedColumns: RenderedColumn[] = [];

function mockRecordColumns(columns: unknown): void {
  mockRenderedColumns = (columns ?? []) as RenderedColumn[];
}

jest.mock("../../../src/api/api.ts", () => ({
  api: {
    getTestCaseRuns: jest.fn(),
    getTestCaseRunIds: jest.fn(),
    cancelTestCaseRuns: jest.fn(),
    exportTestCaseRuns: jest.fn(),
    startTestsRun: jest.fn(),
    getSessionByExternalId: jest.fn(),
    getChains: jest.fn(),
    getElements: jest.fn(),
  },
}));

const mockGetTestCaseRuns = jest.spyOn(api, "getTestCaseRuns");
const mockGetTestCaseRunIds = jest.spyOn(api, "getTestCaseRunIds");
const mockCancelTestCaseRuns = jest.spyOn(api, "cancelTestCaseRuns");
const mockExportTestCaseRuns = jest.spyOn(api, "exportTestCaseRuns");
const mockStartTestsRun = jest.spyOn(api, "startTestsRun");
const mockGetSessionByExternalId = jest.spyOn(api, "getSessionByExternalId");
const mockGetChains = jest.spyOn(api, "getChains");
const mockGetElements = jest.spyOn(api, "getElements");

const mockNavigate = jest.fn();
const mockUseParams: jest.Mock<{ chainId?: string; runId?: string }> = jest.fn(
  () => ({ chainId: "chain-1" }),
);

jest.mock("react-router", () => ({
  useNavigate: () => mockNavigate,
  useParams: () => mockUseParams(),
}));

jest.mock("antd", () => {
  const react = jest.requireActual<typeof import("react")>("react");
  const { createChainPageAntdMock } = jest.requireActual<{
    createChainPageAntdMock: (
      extraOverrides?: Record<string, unknown>,
    ) => Record<string, unknown>;
  }>("tests/helpers/chainPageAntdJestMock");
  return createChainPageAntdMock({
    Table: (props: Parameters<typeof mockLightweightTable>[0]) => {
      mockRecordColumns(props.columns);
      return react.createElement(mockLightweightTable, props);
    },
  });
});

jest.mock("antd/lib/table", () => ({}));
jest.mock("antd/lib/table/interface", () => ({}));

jest.mock("../../../src/components/table/CompactSearch.tsx", () => ({
  CompactSearch: (props: {
    value: string;
    onChange: (value: string) => void;
    placeholder: string;
  }) => (
    <input
      data-testid="search-input"
      value={props.value}
      placeholder={props.placeholder}
      onChange={(event) => props.onChange(event.target.value)}
    />
  ),
}));

jest.mock("../../../src/icons/IconProvider.tsx", () => ({
  OverridableIcon: ({ name }: { name: string }) => (
    <span data-testid={`icon-${name}`} />
  ),
}));

jest.mock("../../../src/Modals.tsx", () => ({
  Modals: ({ children }: { children: React.ReactNode }) => children,
  useModalsContext: () => ({ showModal: jest.fn(), closeModal: jest.fn() }),
}));

const mockNotificationService = {
  requestFailed: jest.fn(),
  errorWithDetails: jest.fn(),
  info: jest.fn(),
  warning: jest.fn(),
};

jest.mock("../../../src/hooks/useNotificationService.tsx", () => ({
  useNotificationService: () => mockNotificationService,
}));

jest.mock("../../../src/misc/confirm-utils.ts", () => ({
  confirmAndRun: (options: {
    title: React.ReactNode;
    content?: React.ReactNode;
    onOk: () => unknown;
  }) => {
    capturedConfirm = options;
  },
}));

jest.mock("../../../src/hooks/filter/useTestingFilter.ts", () => {
  const actual = jest.requireActual<
    typeof import("../../../src/hooks/filter/useTestingFilter.ts")
  >("../../../src/hooks/filter/useTestingFilter.ts");
  return { ...actual, useTestingFilter: jest.fn(actual.useTestingFilter) };
});

const mockUseTestingFilter = jest.mocked(useTestingFilter);

const ALL_PERMISSIONS: UserPermissions = {
  chain: ["read", "update", "execute", "import", "export"],
  adminTools: ["read", "update", "execute", "import", "export"],
};

function testCaseRun(
  overrides: Partial<TestCaseRunView> = {},
): TestCaseRunView {
  return {
    id: "run-1",
    testsRunId: "tests-run-1",
    testCaseId: "case-1",
    testCaseName: "First case",
    testCaseDescription: "First description",
    chainId: "chain-1",
    start: "2026-08-13T10:00:00.000Z",
    finish: "2026-08-13T10:00:05.000Z",
    status: TestRunStatus.FINISHED,
    sessionId: null,
    ordinal: 0,
    errors: 0,
    ...overrides,
  };
}

type RenderOptions = {
  /** Render the drill-down into the case runs of one test run. */
  runScoped?: boolean;
  permissions?: UserPermissions;
};

function renderTestCaseRuns({
  runScoped = false,
  permissions = ALL_PERMISSIONS,
}: RenderOptions = {}) {
  mockUseParams.mockReturnValue(
    runScoped ? { runId: "tests-run-1" } : { chainId: "chain-1" },
  );
  return render(
    <UserPermissionsContext.Provider value={permissions}>
      <ChainHeaderTestRoot>
        <TestCaseRuns variant={runScoped ? "run-page" : "chain-tab"} />
      </ChainHeaderTestRoot>
    </UserPermissionsContext.Provider>,
  );
}

async function renderWithRuns(
  runs: TestCaseRunView[],
  options: RenderOptions = {},
) {
  mockGetTestCaseRuns.mockResolvedValueOnce(runs).mockResolvedValue([]);
  const result = renderTestCaseRuns(options);
  await waitFor(() => expect(mockGetTestCaseRuns).toHaveBeenCalled());
  if (runs.length > 0) {
    await screen.findByText(runs[0].id);
  }
  return result;
}

/** Selection of the newest list request, which the id resolver has to repeat. */
function lastListSpecification(): TestingSelectionSpecification {
  const calls = mockGetTestCaseRuns.mock.calls;
  expect(calls.length).toBeGreaterThan(0);
  return calls[calls.length - 1][0];
}

function sortableColumnKeys(): string[] {
  return mockRenderedColumns
    .filter((column) => column.sorter)
    .map((column) => String(column.key));
}

/** Sort key the named column carries, so no test types a key of its own. */
function sortKeyOfColumn(title: string): string {
  const column = mockRenderedColumns.find((entry) => entry.title === title);
  expect(column?.sorter).toBe(true);
  return String(column?.key);
}

beforeEach(() => {
  capturedConfirm = undefined;
  mockRenderedColumns = [];
  mockUseParams.mockReturnValue({ chainId: "chain-1" });
  mockGetChains.mockResolvedValue([]);
  mockGetElements.mockResolvedValue([]);
  mockGetTestCaseRuns.mockResolvedValue([]);
  mockGetTestCaseRunIds.mockResolvedValue([]);
  mockGetSessionByExternalId.mockReset();
  mockUseTestingFilter.mockImplementation((kind, chainId) =>
    jest
      .requireActual<
        typeof import("../../../src/hooks/filter/useTestingFilter.ts")
      >("../../../src/hooks/filter/useTestingFilter.ts")
      .useTestingFilter(kind, chainId),
  );
});

describe("TestCaseRuns list variants", () => {
  it("should scope the request to the chain and sort by start descending", async () => {
    await renderWithRuns([testCaseRun()]);

    expect(mockGetTestCaseRuns).toHaveBeenCalledWith(
      {
        filters: [
          {
            feature: "chain_id",
            condition: TestingFilterCondition.IS,
            values: ["chain-1"],
          },
        ],
      },
      { offset: 0, sortBy: "start", sortOrder: TestingSortOrder.DESC },
    );
  });

  it("should scope the request to the run when opened as a drill-down", async () => {
    await renderWithRuns([testCaseRun()], { runScoped: true });

    expect(mockGetTestCaseRuns).toHaveBeenCalledWith(
      {
        filters: [
          {
            feature: "tests_run_id",
            condition: TestingFilterCondition.IS,
            values: ["tests-run-1"],
          },
        ],
      },
      { offset: 0, sortBy: "start", sortOrder: TestingSortOrder.DESC },
    );
  });

  it("should show the test run column when opened from a chain", async () => {
    await renderWithRuns([testCaseRun()]);

    expect(screen.getByText("Test Run")).toBeInTheDocument();
    expect(screen.queryByText("Chain")).not.toBeInTheDocument();
  });

  it("should show the chain column when opened as a drill-down", async () => {
    await renderWithRuns([testCaseRun()], { runScoped: true });

    expect(screen.getByText("Chain")).toBeInTheDocument();
    expect(screen.queryByText("Test Run")).not.toBeInTheDocument();
  });

  it("should ask for no chain elements, which no column of this list names", async () => {
    await renderWithRuns([testCaseRun()]);

    expect(mockGetElements).not.toHaveBeenCalled();
  });
});

describe("TestCaseRuns rows", () => {
  it("should render the run status", async () => {
    await renderWithRuns([
      testCaseRun({ status: TestRunStatus.RUNNING }),
      testCaseRun({ id: "run-2", status: null }),
    ]);

    expect(screen.getByText("Running")).toBeInTheDocument();
    expect(screen.getAllByText("—").length).toBeGreaterThan(0);
  });

  it("should link the run to its errors from the chain route", async () => {
    await renderWithRuns([testCaseRun()]);

    fireEvent.click(screen.getByText("run-1"));

    expect(mockNavigate).toHaveBeenCalledWith(
      "/chains/chain-1/testing/test-case-runs/run-1",
    );
  });

  it("should link the run to its errors from the admin drill-down", async () => {
    await renderWithRuns([testCaseRun()], { runScoped: true });

    fireEvent.click(screen.getByText("run-1"));

    expect(mockNavigate).toHaveBeenCalledWith(
      "/admintools/testing/test-runs/tests-run-1/run-1",
    );
  });

  it("should link a non-zero errors count to the validation errors", async () => {
    await renderWithRuns([testCaseRun({ errors: 3 })]);

    fireEvent.click(screen.getByText("3"));

    expect(mockNavigate).toHaveBeenCalledWith(
      "/chains/chain-1/testing/test-case-runs/run-1",
    );
  });

  // A run with nothing to show would open an empty page, and its Id cell already
  // leads there.
  it("should leave a zero errors count as plain text", async () => {
    await renderWithRuns([testCaseRun({ errors: 0 })]);

    fireEvent.click(screen.getByText("0"));

    expect(mockNavigate).not.toHaveBeenCalled();
  });

  it("should link the test case name to its editor", async () => {
    await renderWithRuns([testCaseRun()]);

    fireEvent.click(screen.getByText("First case"));

    expect(mockNavigate).toHaveBeenCalledWith(
      "/chains/chain-1/testing/test-cases/case-1",
    );
  });

  it("should link the test run cell to the run it belongs to", async () => {
    await renderWithRuns([testCaseRun()]);

    fireEvent.click(screen.getByText("tests-run-1"));

    expect(mockNavigate).toHaveBeenCalledWith(
      "/admintools/testing/test-runs/tests-run-1",
    );
  });

  it("should open the details drawer when a row is clicked", async () => {
    await renderWithRuns([testCaseRun()]);

    fireEvent.click(screen.getByText("Finished"));

    expect(
      await screen.findByText("Test Case Run Details"),
    ).toBeInTheDocument();
    expect(screen.getByText("First description")).toBeInTheDocument();
  });
});

describe("TestCaseRuns session lookup", () => {
  it("should link the session it resolved from the external id", async () => {
    mockGetSessionByExternalId.mockResolvedValue({
      id: "session-1",
      chainId: "chain-7",
    } as Session);
    await renderWithRuns([testCaseRun({ sessionId: "external-1" })]);

    await waitFor(() =>
      expect(mockGetSessionByExternalId).toHaveBeenCalledWith("external-1"),
    );

    fireEvent.click(await screen.findByText("external-1"));
    expect(mockNavigate).toHaveBeenCalledWith(
      "/chains/chain-7/sessions/session-1",
    );
  });

  it("should render the external id without a link when no session is found", async () => {
    mockGetSessionByExternalId.mockRejectedValue(new Error("not found"));
    await renderWithRuns([testCaseRun({ sessionId: "external-1" })]);

    await waitFor(() => expect(mockGetSessionByExternalId).toHaveBeenCalled());

    fireEvent.click(screen.getByText("external-1"));
    expect(mockNavigate).not.toHaveBeenCalled();
  });

  it("should look an external id up once, however many rows carry it", async () => {
    mockGetSessionByExternalId.mockResolvedValue({
      id: "session-1",
      chainId: "chain-7",
    } as Session);
    await renderWithRuns([
      testCaseRun({ sessionId: "external-1" }),
      testCaseRun({ id: "run-2", sessionId: "external-1" }),
    ]);

    await waitFor(() => expect(mockGetSessionByExternalId).toHaveBeenCalled());
    expect(mockGetSessionByExternalId).toHaveBeenCalledTimes(1);
  });

  it("should look nothing up for a run that recorded no session", async () => {
    await renderWithRuns([testCaseRun()]);

    expect(mockGetSessionByExternalId).not.toHaveBeenCalled();
  });
});

describe("TestCaseRuns filters and sorting", () => {
  it("should map a filter onto the selection the service takes", async () => {
    const filters: EntityFilterModel[] = [
      {
        column: "errors",
        condition: FilterCondition.GREATER_THAN.id,
        value: "0",
      },
    ];
    mockUseTestingFilter.mockReturnValue({
      filters,
      filterButton: null,
    });

    await renderWithRuns([testCaseRun()]);

    expect(mockGetTestCaseRuns).toHaveBeenCalledWith(
      expect.objectContaining({
        filters: expect.arrayContaining([
          {
            feature: "errors",
            condition: TestingFilterCondition.GREATER_THAN,
            values: ["0"],
          },
        ]),
      }),
      expect.anything(),
    );
  });

  it("should key every sortable column on a field the service takes when opened from a chain", async () => {
    await renderWithRuns([testCaseRun()]);

    const keys = sortableColumnKeys();
    expect(keys.length).toBeGreaterThan(0);
    for (const key of keys) {
      expect(TEST_CASE_RUNS_SORT_FIELDS).toContain(key);
    }
  });

  it("should key every sortable column on a field the service takes in the drill-down", async () => {
    await renderWithRuns([testCaseRun()], { runScoped: true });

    const keys = sortableColumnKeys();
    expect(keys).toContain("chain_id");
    for (const key of keys) {
      expect(TEST_CASE_RUNS_SORT_FIELDS).toContain(key);
    }
  });

  it("should send the sort field the column carries", async () => {
    await renderWithRuns([testCaseRun()]);
    const columnKey = sortKeyOfColumn("Test Case");
    mockGetTestCaseRuns.mockClear();

    getLastTableOnChange()?.({}, {}, { columnKey, order: "ascend" });

    await waitFor(() =>
      expect(mockGetTestCaseRuns).toHaveBeenCalledWith(expect.anything(), {
        offset: 0,
        sortBy: columnKey,
        sortOrder: TestingSortOrder.ASC,
      }),
    );
  });
});

describe("TestCaseRuns actions", () => {
  it("should cancel the selected runs after a confirmation", async () => {
    await renderWithRuns([
      testCaseRun(),
      testCaseRun({ id: "run-2", testCaseName: "Second case" }),
    ]);

    fireEvent.click(screen.getAllByRole("checkbox")[1]);
    fireEvent.click(screen.getByTestId("test-case-runs-cancel"));

    expect(capturedConfirm?.content).toBe(
      "Cancel 1 test case run? A case that already started keeps running.",
    );
    await capturedConfirm?.onOk();
    expect(mockCancelTestCaseRuns).toHaveBeenCalledWith(["run-1"]);
  });

  it("should restart the selected runs from the test case runs they name", async () => {
    mockStartTestsRun.mockResolvedValue("tests-run-9");
    await renderWithRuns([testCaseRun()]);

    fireEvent.click(screen.getAllByRole("checkbox")[1]);
    fireEvent.click(screen.getByTestId("test-case-runs-restart"));

    await waitFor(() =>
      expect(mockStartTestsRun).toHaveBeenCalledWith(
        ["run-1"],
        TestsRunSource.TEST_CASE_RUNS,
      ),
    );
    expect(mockNotificationService.info).toHaveBeenCalled();
  });

  it("should resolve targets server-side when everything matching is selected", async () => {
    mockGetTestCaseRunIds.mockResolvedValue(["run-1", "run-2", "run-99"]);
    await renderWithRuns([
      testCaseRun(),
      testCaseRun({ id: "run-2", testCaseName: "Second case" }),
    ]);

    fireEvent.click(screen.getByTestId("table-selection-all-matching"));
    fireEvent.click(screen.getByTestId("test-case-runs-cancel"));

    expect(capturedConfirm?.content).toBe(
      "Cancel all test case runs that match the filters? A case that already started keeps running.",
    );
    await capturedConfirm?.onOk();
    // The resolver has to repeat the selection of the list, or the cancel would
    // reach past the chain the list is scoped to.
    expect(mockGetTestCaseRunIds).toHaveBeenCalledWith(lastListSpecification());
    expect(mockGetTestCaseRunIds).toHaveBeenCalledWith({
      filters: [
        {
          feature: "chain_id",
          condition: TestingFilterCondition.IS,
          values: ["chain-1"],
        },
      ],
    });
    expect(mockCancelTestCaseRuns).toHaveBeenCalledWith([
      "run-1",
      "run-2",
      "run-99",
    ]);
  });

  it("should resolve targets under the filter when everything matching is selected", async () => {
    const filters: EntityFilterModel[] = [
      {
        column: "errors",
        condition: FilterCondition.GREATER_THAN.id,
        value: "0",
      },
    ];
    mockUseTestingFilter.mockReturnValue({ filters, filterButton: null });
    mockGetTestCaseRunIds.mockResolvedValue(["run-1"]);
    await renderWithRuns([testCaseRun()]);

    fireEvent.click(screen.getByTestId("table-selection-all-matching"));
    fireEvent.click(screen.getByTestId("test-case-runs-cancel"));
    await capturedConfirm?.onOk();

    expect(mockGetTestCaseRunIds).toHaveBeenCalledWith(lastListSpecification());
    expect(mockGetTestCaseRunIds).toHaveBeenCalledWith({
      filters: [
        {
          feature: "chain_id",
          condition: TestingFilterCondition.IS,
          values: ["chain-1"],
        },
        {
          feature: "errors",
          condition: TestingFilterCondition.GREATER_THAN,
          values: ["0"],
        },
      ],
    });
    expect(mockCancelTestCaseRuns).toHaveBeenCalledWith(["run-1"]);
  });

  it("should resolve targets under the run scope when everything matching is selected", async () => {
    mockGetTestCaseRunIds.mockResolvedValue(["run-1"]);
    await renderWithRuns([testCaseRun()], { runScoped: true });

    fireEvent.click(screen.getByTestId("table-selection-all-matching"));
    fireEvent.click(screen.getByTestId("test-case-runs-cancel"));
    await capturedConfirm?.onOk();

    expect(mockGetTestCaseRunIds).toHaveBeenCalledWith(lastListSpecification());
    expect(mockGetTestCaseRunIds).toHaveBeenCalledWith({
      filters: [
        {
          feature: "tests_run_id",
          condition: TestingFilterCondition.IS,
          values: ["tests-run-1"],
        },
      ],
    });
    expect(mockCancelTestCaseRuns).toHaveBeenCalledWith(["run-1"]);
  });

  it("should export the selected runs", async () => {
    mockExportTestCaseRuns.mockResolvedValue(new File([], "runs.zip"));
    await renderWithRuns([testCaseRun()]);

    fireEvent.click(screen.getAllByRole("checkbox")[1]);
    fireEvent.click(screen.getByTestId("test-case-runs-export"));

    await waitFor(() =>
      expect(mockExportTestCaseRuns).toHaveBeenCalledWith(["run-1"]),
    );
  });

  it("should do nothing when no row is selected", async () => {
    await renderWithRuns([testCaseRun()]);

    fireEvent.click(screen.getByTestId("test-case-runs-cancel"));
    fireEvent.click(screen.getByTestId("test-case-runs-restart"));
    fireEvent.click(screen.getByTestId("test-case-runs-export"));

    expect(capturedConfirm).toBeUndefined();
    expect(mockStartTestsRun).not.toHaveBeenCalled();
    expect(mockExportTestCaseRuns).not.toHaveBeenCalled();
  });

  it("should offer no delete action, which case runs have none of", async () => {
    await renderWithRuns([testCaseRun()]);

    expect(
      screen.queryByTestId("test-case-runs-delete"),
    ).not.toBeInTheDocument();
  });
});

describe("TestCaseRuns selection lifetime", () => {
  /** Checked state of the row boxes, the header box aside. */
  function rowCheckedState(): boolean[] {
    return screen
      .getAllByRole("checkbox")
      .slice(1)
      .map((checkbox) => (checkbox as HTMLInputElement).checked);
  }

  const twoRuns = [
    testCaseRun(),
    testCaseRun({ id: "run-2", testCaseName: "Second case" }),
  ];

  /** A fresh element per call: React skips a rerender of the very same one. */
  function chainTab() {
    return (
      <UserPermissionsContext.Provider value={ALL_PERMISSIONS}>
        <ChainHeaderTestRoot>
          <TestCaseRuns variant="chain-tab" />
        </ChainHeaderTestRoot>
      </UserPermissionsContext.Provider>
    );
  }

  it("should drop the selection when the search or the sort changes", async () => {
    mockGetTestCaseRuns.mockResolvedValue(twoRuns);
    renderTestCaseRuns();
    await screen.findByText("run-2");

    fireEvent.click(screen.getAllByRole("checkbox")[1]);
    expect(rowCheckedState()).toEqual([true, false]);

    fireEvent.change(screen.getByTestId("search-input"), {
      target: { value: "second" },
    });
    await waitFor(() => expect(rowCheckedState()).toEqual([false, false]));

    // Rows the search has hidden are rows a cancel must not reach.
    fireEvent.click(screen.getByTestId("test-case-runs-cancel"));
    expect(capturedConfirm).toBeUndefined();

    fireEvent.click(screen.getAllByRole("checkbox")[1]);
    expect(rowCheckedState()).toEqual([true, false]);

    getLastTableOnChange()?.(
      {},
      {},
      { columnKey: sortKeyOfColumn("Test Case"), order: "ascend" },
    );
    await waitFor(() => expect(rowCheckedState()).toEqual([false, false]));
  });

  it("should drop the selection when the filters change", async () => {
    mockUseTestingFilter.mockReturnValue({ filters: [], filterButton: null });
    mockGetTestCaseRuns.mockResolvedValue(twoRuns);
    const { rerender } = render(chainTab());
    await screen.findByText("run-2");

    fireEvent.click(screen.getAllByRole("checkbox")[1]);
    expect(rowCheckedState()).toEqual([true, false]);

    mockUseTestingFilter.mockReturnValue({
      filters: [
        {
          column: "errors",
          condition: FilterCondition.GREATER_THAN.id,
          value: "0",
        },
      ],
      filterButton: null,
    });
    rerender(chainTab());

    await waitFor(() => expect(rowCheckedState()).toEqual([false, false]));
  });

  it("should extend a select-all-matching selection over the next page", async () => {
    mockGetTestCaseRuns
      .mockResolvedValueOnce([testCaseRun()])
      .mockResolvedValueOnce([
        testCaseRun({ id: "run-2", testCaseName: "Second case" }),
      ])
      .mockResolvedValue([]);
    renderTestCaseRuns();
    await screen.findByText("run-1");
    await waitFor(() =>
      expect(screen.queryByTestId("table-loading")).not.toBeInTheDocument(),
    );

    fireEvent.click(screen.getByTestId("table-selection-all-matching"));
    await waitFor(() => expect(rowCheckedState()).toEqual([true]));

    act(() => triggerIntersection());
    await screen.findByText("run-2");

    expect(rowCheckedState()).toEqual([true, true]);
  });
});

describe("TestCaseRuns permission gating", () => {
  it("should hide the execute actions without chain rights", async () => {
    await renderWithRuns([testCaseRun()], {
      permissions: { chain: ["read"] },
    });

    expect(screen.getByTestId("test-case-runs-refresh")).toBeInTheDocument();
    expect(
      screen.queryByTestId("test-case-runs-cancel"),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("test-case-runs-restart"),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("test-case-runs-export"),
    ).not.toBeInTheDocument();
  });

  it("should gate the drill-down on admin tools rights", async () => {
    await renderWithRuns([testCaseRun()], {
      runScoped: true,
      permissions: { chain: ["read", "update", "execute", "export", "import"] },
    });

    expect(
      screen.queryByTestId("test-case-runs-refresh"),
    ).not.toBeInTheDocument();
  });

  it("should offer Restart and Cancel when the execute right is the only one granted", async () => {
    await renderWithRuns([testCaseRun()], {
      permissions: { chain: ["execute"] },
    });

    expect(screen.getByTestId("test-case-runs-restart")).toBeInTheDocument();
    expect(screen.getByTestId("test-case-runs-cancel")).toBeInTheDocument();
    expect(
      screen.queryByTestId("test-case-runs-refresh"),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("test-case-runs-export"),
    ).not.toBeInTheDocument();
  });

  it("should offer Export alone when the export right is the only one granted", async () => {
    await renderWithRuns([testCaseRun()], {
      permissions: { chain: ["export"] },
    });

    expect(screen.getByTestId("test-case-runs-export")).toBeInTheDocument();
    expect(
      screen.queryByTestId("test-case-runs-refresh"),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("test-case-runs-restart"),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("test-case-runs-cancel"),
    ).not.toBeInTheDocument();
  });
});
