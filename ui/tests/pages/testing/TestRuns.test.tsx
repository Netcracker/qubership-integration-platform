/**
 * @jest-environment jsdom
 */
import React from "react";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom";
import {
  TestingFilterCondition,
  TestingSortOrder,
  TestRunStatus,
  TestsRunSource,
  TestsRunView,
} from "../../../src/api/apiTypes.ts";
import { api } from "../../../src/api/api.ts";
import { UserPermissionsContext } from "../../../src/permissions/UserPermissionsContext.tsx";
import type { UserPermissions } from "../../../src/permissions/types.ts";
import { useTestingFilter } from "../../../src/hooks/filter/useTestingFilter.ts";
import type { EntityFilterModel } from "../../../src/components/table/filter/filterTypes.ts";
import { FilterCondition } from "../../../src/components/table/filter/filterTypes.ts";
import { getLastTableOnChange } from "../../__mocks__/LightweightTable.tsx";
import { TestRuns } from "../../../src/pages/testing/TestRuns.tsx";

let capturedConfirm:
  | { title: React.ReactNode; content?: React.ReactNode; onOk: () => unknown }
  | undefined;

jest.mock("../../../src/api/api.ts", () => ({
  api: {
    getTestsRuns: jest.fn(),
    getTestsRunIds: jest.fn(),
    deleteTestsRuns: jest.fn(),
    cancelTestsRuns: jest.fn(),
    exportTestsRuns: jest.fn(),
    startTestsRun: jest.fn(),
    getChains: jest.fn(),
    getElements: jest.fn(),
  },
}));

const mockGetTestsRuns = jest.spyOn(api, "getTestsRuns");
const mockGetTestsRunIds = jest.spyOn(api, "getTestsRunIds");
const mockDeleteTestsRuns = jest.spyOn(api, "deleteTestsRuns");
const mockCancelTestsRuns = jest.spyOn(api, "cancelTestsRuns");
const mockExportTestsRuns = jest.spyOn(api, "exportTestsRuns");
const mockStartTestsRun = jest.spyOn(api, "startTestsRun");
const mockGetChains = jest.spyOn(api, "getChains");
const mockGetElements = jest.spyOn(api, "getElements");

const mockNavigate = jest.fn();

jest.mock("react-router", () => ({
  useNavigate: () => mockNavigate,
}));

jest.mock("antd", () => {
  const { createChainPageAntdMock } = jest.requireActual<{
    createChainPageAntdMock: () => Record<string, unknown>;
  }>("tests/helpers/chainPageAntdJestMock");
  return createChainPageAntdMock();
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

function testsRun(overrides: Partial<TestsRunView> = {}): TestsRunView {
  return {
    id: "tests-run-1",
    start: "2026-08-13T10:00:00.000Z",
    finish: "2026-08-13T10:00:05.000Z",
    status: TestRunStatus.FINISHED,
    errors: 2,
    testCases: 7,
    createdBy: "Alice",
    createdAt: "2026-08-13T09:59:00.000Z",
    updatedBy: "Alice",
    updatedAt: "2026-08-13T09:59:30.000Z",
    ...overrides,
  };
}

function renderTestRuns(permissions: UserPermissions = ALL_PERMISSIONS) {
  return render(
    <UserPermissionsContext.Provider value={permissions}>
      <TestRuns />
    </UserPermissionsContext.Provider>,
  );
}

async function renderWithRuns(
  runs: TestsRunView[],
  permissions: UserPermissions = ALL_PERMISSIONS,
) {
  mockGetTestsRuns.mockResolvedValueOnce(runs).mockResolvedValue([]);
  const result = renderTestRuns(permissions);
  await waitFor(() => expect(mockGetTestsRuns).toHaveBeenCalled());
  if (runs.length > 0) {
    await screen.findByText(runs[0].id);
  }
  return result;
}

beforeEach(() => {
  capturedConfirm = undefined;
  mockGetChains.mockResolvedValue([]);
  mockGetElements.mockResolvedValue([]);
  mockGetTestsRuns.mockResolvedValue([]);
  mockGetTestsRunIds.mockResolvedValue([]);
  mockUseTestingFilter.mockImplementation((kind, chainId) =>
    jest
      .requireActual<
        typeof import("../../../src/hooks/filter/useTestingFilter.ts")
      >("../../../src/hooks/filter/useTestingFilter.ts")
      .useTestingFilter(kind, chainId),
  );
});

describe("TestRuns list", () => {
  it("should read the unscoped list sorted by start descending", async () => {
    await renderWithRuns([testsRun()]);

    expect(mockGetTestsRuns).toHaveBeenCalledWith(
      {},
      { offset: 0, sortBy: "start", sortOrder: TestingSortOrder.DESC },
    );
  });

  it("should ask for no chain elements, which no column of this list names", async () => {
    await renderWithRuns([testsRun()]);

    expect(mockGetElements).not.toHaveBeenCalled();
  });

  it("should show the run set counts and its timings", async () => {
    await renderWithRuns([testsRun()]);

    expect(screen.getByText("Test Cases")).toBeInTheDocument();
    expect(screen.getByText("Test Cases With Errors")).toBeInTheDocument();
    expect(screen.getByText("7")).toBeInTheDocument();
    expect(screen.getByText("2")).toBeInTheDocument();
  });

  it("should render the aggregate status of a run set", async () => {
    await renderWithRuns([
      testsRun({ status: TestRunStatus.RUNNING }),
      testsRun({ id: "tests-run-2", status: null }),
    ]);

    expect(screen.getByText("Running")).toBeInTheDocument();
    expect(screen.getAllByText("—").length).toBeGreaterThan(0);
  });

  it("should hide the audit columns until the settings show them", async () => {
    await renderWithRuns([testsRun()]);

    expect(screen.queryByText("Updated At")).not.toBeInTheDocument();
    expect(screen.queryByText("Created At")).not.toBeInTheDocument();
  });
});

describe("TestRuns drill-down", () => {
  it("should open the case runs of the run when its id is clicked", async () => {
    await renderWithRuns([testsRun()]);

    fireEvent.click(screen.getByText("tests-run-1"));

    expect(mockNavigate).toHaveBeenCalledWith(
      "/admintools/testing/test-runs/tests-run-1",
    );
  });

  it("should open the details drawer when a row is clicked", async () => {
    await renderWithRuns([testsRun()]);

    fireEvent.click(screen.getByText("Finished"));

    expect(await screen.findByText("Test Run Details")).toBeInTheDocument();
    expect(screen.getByText("Test cases with errors")).toBeInTheDocument();
  });

  it("should reach the case runs from the drawer", async () => {
    await renderWithRuns([testsRun()]);

    fireEvent.click(screen.getByText("Finished"));
    await screen.findByText("Test Run Details");
    mockNavigate.mockClear();

    // The count shows in the table cell as well; the drawer renders after it.
    const counts = screen.getAllByText("7");
    fireEvent.click(counts[counts.length - 1]);

    expect(mockNavigate).toHaveBeenCalledWith(
      "/admintools/testing/test-runs/tests-run-1",
    );
  });
});

describe("TestRuns filters and sorting", () => {
  it("should map a filter onto the selection the service takes", async () => {
    const filters: EntityFilterModel[] = [
      {
        column: "test_cases",
        condition: FilterCondition.GREATER_THAN.id,
        value: "1",
      },
    ];
    mockUseTestingFilter.mockReturnValue({
      filters,
      filterButton: null,
      resetFilters: jest.fn(),
    });

    await renderWithRuns([testsRun()]);

    expect(mockGetTestsRuns).toHaveBeenCalledWith(
      {
        filters: [
          {
            feature: "test_cases",
            condition: TestingFilterCondition.GREATER_THAN,
            values: ["1"],
          },
        ],
      },
      expect.anything(),
    );
  });

  it("should send the sort field the column carries", async () => {
    await renderWithRuns([testsRun()]);
    mockGetTestsRuns.mockClear();

    getLastTableOnChange()?.({}, {}, { columnKey: "errors", order: "ascend" });

    await waitFor(() =>
      expect(mockGetTestsRuns).toHaveBeenCalledWith(expect.anything(), {
        offset: 0,
        sortBy: "errors",
        sortOrder: TestingSortOrder.ASC,
      }),
    );
  });

  it("should drop a sort field outside the set the service validates", async () => {
    await renderWithRuns([testsRun()]);
    mockGetTestsRuns.mockClear();

    getLastTableOnChange()?.(
      {},
      {},
      { columnKey: "updated_at", order: "ascend" },
    );

    await waitFor(() =>
      expect(mockGetTestsRuns).toHaveBeenCalledWith(expect.anything(), {
        offset: 0,
      }),
    );
  });
});

describe("TestRuns actions", () => {
  it("should restart the selected runs from the runs they name", async () => {
    mockStartTestsRun.mockResolvedValue("tests-run-9");
    await renderWithRuns([testsRun()]);

    fireEvent.click(screen.getAllByRole("checkbox")[1]);
    fireEvent.click(screen.getByTestId("test-runs-restart"));

    await waitFor(() =>
      expect(mockStartTestsRun).toHaveBeenCalledWith(
        ["tests-run-1"],
        TestsRunSource.TESTS_RUNS,
      ),
    );
    expect(mockNotificationService.info).toHaveBeenCalled();
  });

  it("should cancel the selected runs after a confirmation", async () => {
    await renderWithRuns([testsRun()]);

    fireEvent.click(screen.getAllByRole("checkbox")[1]);
    fireEvent.click(screen.getByTestId("test-runs-cancel"));

    expect(capturedConfirm?.content).toBe(
      "Cancel 1 test run? A case that already started keeps running.",
    );
    await capturedConfirm?.onOk();
    expect(mockCancelTestsRuns).toHaveBeenCalledWith(["tests-run-1"]);
  });

  it("should delete the selected runs after a confirmation", async () => {
    await renderWithRuns([testsRun()]);

    fireEvent.click(screen.getAllByRole("checkbox")[1]);
    fireEvent.click(screen.getByTestId("test-runs-delete"));

    expect(capturedConfirm?.content).toBe(
      "Delete 1 test run with their case runs? This cannot be undone.",
    );
    await capturedConfirm?.onOk();
    expect(mockDeleteTestsRuns).toHaveBeenCalledWith(["tests-run-1"]);
  });

  it("should export the selected runs", async () => {
    mockExportTestsRuns.mockResolvedValue(new File([], "runs.zip"));
    await renderWithRuns([testsRun()]);

    fireEvent.click(screen.getAllByRole("checkbox")[1]);
    fireEvent.click(screen.getByTestId("test-runs-export"));

    await waitFor(() =>
      expect(mockExportTestsRuns).toHaveBeenCalledWith(["tests-run-1"]),
    );
  });

  it("should resolve targets server-side when everything matching is selected", async () => {
    mockGetTestsRunIds.mockResolvedValue([
      "tests-run-1",
      "tests-run-2",
      "tests-run-99",
    ]);
    await renderWithRuns([
      testsRun(),
      testsRun({ id: "tests-run-2", testCases: 3 }),
    ]);

    fireEvent.click(screen.getByTestId("table-selection-all-matching"));
    fireEvent.click(screen.getByTestId("test-runs-delete"));

    expect(capturedConfirm?.content).toBe(
      "Delete all test runs that match the filters with their case runs? This cannot be undone.",
    );
    await capturedConfirm?.onOk();
    expect(mockDeleteTestsRuns).toHaveBeenCalledWith([
      "tests-run-1",
      "tests-run-2",
      "tests-run-99",
    ]);
  });

  it("should do nothing when no row is selected", async () => {
    await renderWithRuns([testsRun()]);

    fireEvent.click(screen.getByTestId("test-runs-cancel"));
    fireEvent.click(screen.getByTestId("test-runs-restart"));
    fireEvent.click(screen.getByTestId("test-runs-delete"));
    fireEvent.click(screen.getByTestId("test-runs-export"));

    expect(capturedConfirm).toBeUndefined();
    expect(mockStartTestsRun).not.toHaveBeenCalled();
    expect(mockExportTestsRuns).not.toHaveBeenCalled();
  });
});

describe("TestRuns permission gating", () => {
  it("should keep refresh alone with admin read rights", async () => {
    await renderWithRuns([testsRun()], { adminTools: ["read"] });

    expect(screen.getByTestId("test-runs-refresh")).toBeInTheDocument();
    expect(screen.queryByTestId("test-runs-restart")).not.toBeInTheDocument();
    expect(screen.queryByTestId("test-runs-cancel")).not.toBeInTheDocument();
    expect(screen.queryByTestId("test-runs-export")).not.toBeInTheDocument();
    expect(screen.queryByTestId("test-runs-delete")).not.toBeInTheDocument();
  });

  it("should gate the list on admin tools rather than chain rights", async () => {
    await renderWithRuns([testsRun()], {
      chain: ["read", "update", "execute", "export", "import"],
    });

    expect(screen.queryByTestId("test-runs-refresh")).not.toBeInTheDocument();
  });
});
