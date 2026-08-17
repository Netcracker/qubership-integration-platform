/**
 * @jest-environment jsdom
 */
import React from "react";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom";
import {
  Session,
  TestCaseRunView,
  TestingFilterCondition,
  TestingSortOrder,
  TestRunStatus,
  TestsRunSource,
} from "../../../src/api/apiTypes.ts";
import { api } from "../../../src/api/api.ts";
import { UserPermissionsContext } from "../../../src/permissions/UserPermissionsContext.tsx";
import type { UserPermissions } from "../../../src/permissions/types.ts";
import { useTestingFilter } from "../../../src/hooks/filter/useTestingFilter.ts";
import type { EntityFilterModel } from "../../../src/components/table/filter/filterTypes.ts";
import { FilterCondition } from "../../../src/components/table/filter/filterTypes.ts";
import { getLastTableOnChange } from "../../__mocks__/LightweightTable.tsx";
import { TestCaseRuns } from "../../../src/pages/testing/TestCaseRuns.tsx";
import { ChainHeaderTestRoot } from "../../helpers/renderWithChainHeader.tsx";

let capturedConfirm:
  | { title: React.ReactNode; content?: React.ReactNode; onOk: () => unknown }
  | undefined;

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

beforeEach(() => {
  capturedConfirm = undefined;
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

  it("should swap the test run column for the chain column between variants", async () => {
    const { unmount } = await renderWithRuns([testCaseRun()]);
    expect(screen.getByText("Test Run")).toBeInTheDocument();
    expect(screen.queryByText("Chain")).not.toBeInTheDocument();
    unmount();

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
      resetFilters: jest.fn(),
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

  it("should send the sort field the column carries", async () => {
    await renderWithRuns([testCaseRun()]);
    mockGetTestCaseRuns.mockClear();

    getLastTableOnChange()?.(
      {},
      {},
      { columnKey: "test_case_name", order: "ascend" },
    );

    await waitFor(() =>
      expect(mockGetTestCaseRuns).toHaveBeenCalledWith(expect.anything(), {
        offset: 0,
        sortBy: "test_case_name",
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
    expect(mockCancelTestCaseRuns).toHaveBeenCalledWith([
      "run-1",
      "run-2",
      "run-99",
    ]);
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
});
