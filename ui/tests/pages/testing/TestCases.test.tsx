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
  MatcherEntityType,
  MatcherType,
  TestCase,
  TestCaseView,
  TestingFilterCondition,
  TestingSelectionSpecification,
  TestingSortOrder,
} from "../../../src/api/apiTypes.ts";
import { api } from "../../../src/api/api.ts";
import {
  CreateTestCaseModal,
  type CreateTestCaseModalProps,
} from "../../../src/components/modal/testing/CreateTestCaseModal.tsx";
import {
  TestingImportModal,
  type TestingImportModalProps,
} from "../../../src/components/modal/testing/TestingImportModal.tsx";
import { UserPermissionsContext } from "../../../src/permissions/UserPermissionsContext.tsx";
import type { UserPermissions } from "../../../src/permissions/types.ts";
import {
  TEST_CASES_SORT_FIELDS,
  useTestingFilter,
} from "../../../src/hooks/filter/useTestingFilter.ts";
import type { EntityFilterModel } from "../../../src/components/table/filter/filterTypes.ts";
import { FilterCondition } from "../../../src/components/table/filter/filterTypes.ts";
import {
  getLastTableOnChange,
  LightweightTable as mockLightweightTable,
} from "../../__mocks__/LightweightTable.tsx";
import { TestCases } from "../../../src/pages/testing/TestCases.tsx";
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
    getTestCases: jest.fn(),
    getTestCaseIds: jest.fn(),
    deleteTestCases: jest.fn(),
    exportTestCases: jest.fn(),
    startTestsRun: jest.fn(),
    getChains: jest.fn(),
    getElements: jest.fn(),
    createTestCase: jest.fn(),
    importTestCases: jest.fn(),
  },
}));

const mockGetTestCases = jest.spyOn(api, "getTestCases");
const mockGetTestCaseIds = jest.spyOn(api, "getTestCaseIds");
const mockDeleteTestCases = jest.spyOn(api, "deleteTestCases");
const mockExportTestCases = jest.spyOn(api, "exportTestCases");
const mockStartTestsRun = jest.spyOn(api, "startTestsRun");
const mockGetChains = jest.spyOn(api, "getChains");
const mockGetElements = jest.spyOn(api, "getElements");
const mockImportTestCases = jest.spyOn(api, "importTestCases");

const mockNavigate = jest.fn();
const mockUseParams: jest.Mock<{ chainId?: string }> = jest.fn(() => ({
  chainId: "chain-1",
}));

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

const mockShowModal = jest.fn();

jest.mock("../../../src/Modals.tsx", () => ({
  Modals: ({ children }: { children: React.ReactNode }) => children,
  useModalsContext: () => ({ showModal: mockShowModal, closeModal: jest.fn() }),
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

function testCase(overrides: Partial<TestCaseView> = {}): TestCaseView {
  return {
    id: "case-1",
    name: "First case",
    description: "First description",
    enabled: true,
    triggerReference: { chainId: "chain-1", elementId: "element-1" },
    requestSettings: {
      queryParameters: null,
      pathParameters: null,
      message: null,
      method: "GET",
      timeout: 120000,
    },
    responseValidationRules: [
      {
        name: "rule",
        description: "",
        enabled: true,
        type: MatcherType.EXIST,
        entityType: MatcherEntityType.BODY,
        entityName: null,
        parameters: null,
      },
    ],
    validationRuleCount: 1,
    enabledRuleCount: 1,
    createdBy: "author",
    createdAt: "2026-08-13T10:00:00.000Z",
    updatedBy: null,
    updatedAt: null,
    ...overrides,
  };
}

type RenderOptions = {
  /** Render the cross-chain list reached from admin tools. */
  global?: boolean;
  permissions?: UserPermissions;
};

function renderTestCases({
  global = false,
  permissions = ALL_PERMISSIONS,
}: RenderOptions = {}) {
  mockUseParams.mockReturnValue({ chainId: global ? undefined : "chain-1" });
  return render(
    // The provider wraps the header root: the chain-tab toolbar renders into the
    // header slot, which is a sibling of the page rather than a descendant.
    <UserPermissionsContext.Provider value={permissions}>
      <ChainHeaderTestRoot>
        <TestCases variant={global ? "admin-page" : "chain-tab"} />
      </ChainHeaderTestRoot>
    </UserPermissionsContext.Provider>,
  );
}

async function renderWithCases(
  cases: TestCaseView[],
  options: RenderOptions = {},
) {
  mockGetTestCases.mockResolvedValueOnce(cases).mockResolvedValue([]);
  const result = renderTestCases(options);
  await waitFor(() => expect(mockGetTestCases).toHaveBeenCalled());
  if (cases.length > 0) {
    await screen.findByText(cases[0].name);
  }
  return result;
}

/** Selection of the newest list request, which the id resolver has to repeat. */
function lastListSpecification(): TestingSelectionSpecification {
  const calls = mockGetTestCases.mock.calls;
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
  mockShowModal.mockClear();
  mockUseParams.mockReturnValue({ chainId: "chain-1" });
  mockGetChains.mockResolvedValue([]);
  mockGetElements.mockResolvedValue([]);
  mockGetTestCases.mockResolvedValue([]);
  mockGetTestCaseIds.mockResolvedValue([]);
  mockUseTestingFilter.mockImplementation((kind, chainId) =>
    jest
      .requireActual<
        typeof import("../../../src/hooks/filter/useTestingFilter.ts")
      >("../../../src/hooks/filter/useTestingFilter.ts")
      .useTestingFilter(kind, chainId),
  );
});

describe("TestCases list variants", () => {
  it("should scope the request to the chain when opened from a chain", async () => {
    await renderWithCases([testCase()]);

    expect(mockGetTestCases).toHaveBeenCalledWith(
      {
        filters: [
          {
            feature: "chain_id",
            condition: TestingFilterCondition.IS,
            values: ["chain-1"],
          },
        ],
      },
      { offset: 0 },
    );
  });

  it("should send no chain filter when opened outside a chain", async () => {
    await renderWithCases([testCase()], { global: true });

    expect(mockGetTestCases).toHaveBeenCalledWith({}, { offset: 0 });
  });

  it("should offer Create but not Import inside a chain", async () => {
    await renderWithCases([testCase()]);

    expect(screen.getByTestId("test-cases-create")).toBeInTheDocument();
    expect(screen.queryByTestId("test-cases-import")).not.toBeInTheDocument();
  });

  it("should offer Import but not Create outside a chain", async () => {
    await renderWithCases([testCase()], { global: true });

    expect(screen.getByTestId("test-cases-import")).toBeInTheDocument();
    expect(screen.queryByTestId("test-cases-create")).not.toBeInTheDocument();
  });

  it("should hide the chain column when opened from a chain", async () => {
    await renderWithCases([testCase()]);

    expect(screen.queryByText("Chain")).not.toBeInTheDocument();
    expect(screen.getByText("Element")).toBeInTheDocument();
  });

  it("should show the chain column when opened outside a chain", async () => {
    await renderWithCases([testCase()], { global: true });

    expect(screen.getByText("Chain")).toBeInTheDocument();
    expect(screen.getByText("Element")).toBeInTheDocument();
  });
});

describe("TestCases rows", () => {
  it("should read the chain and the element off the trigger reference", async () => {
    mockGetChains.mockResolvedValue([
      { id: "chain-9", name: "Ninth chain" },
    ] as never);
    await renderWithCases(
      [
        testCase({
          triggerReference: { chainId: "chain-9", elementId: "element-9" },
        }),
      ],
      { global: true },
    );

    await screen.findByText("Ninth chain");
    fireEvent.click(screen.getByText("Ninth chain"));
    expect(mockNavigate).toHaveBeenCalledWith("/chains/chain-9");

    fireEvent.click(screen.getByText("element-9"));
    expect(mockNavigate).toHaveBeenCalledWith(
      "/chains/chain-9/graph/element-9",
    );
  });

  it("should mark a case without enabled rules as incomplete", async () => {
    await renderWithCases([
      testCase({
        id: "case-2",
        name: "Bare case",
        responseValidationRules: [],
      }),
    ]);

    expect(screen.getByText("Incomplete")).toBeInTheDocument();
    expect(screen.queryByText("Ready")).not.toBeInTheDocument();
  });

  it("should mark a fully configured case as ready", async () => {
    await renderWithCases([testCase()]);

    expect(screen.getByText("Ready")).toBeInTheDocument();
  });

  it("should open the details drawer when a row is clicked", async () => {
    await renderWithCases([testCase()]);

    fireEvent.click(screen.getByText("First description"));

    expect(await screen.findByText("Test Case Details")).toBeInTheDocument();
  });

  it("should navigate to the editor when the name is clicked", async () => {
    await renderWithCases([testCase()]);

    fireEvent.click(screen.getByText("First case"));

    expect(mockNavigate).toHaveBeenCalledWith(
      "/chains/chain-1/testing/test-cases/case-1",
    );
  });
});

describe("TestCases filters and sorting", () => {
  it("should map a filter onto the selection the service takes", async () => {
    const filters: EntityFilterModel[] = [
      { column: "name", condition: FilterCondition.CONTAINS.id, value: "pay" },
    ];
    mockUseTestingFilter.mockReturnValue({
      filters,
      filterButton: null,
    });

    await renderWithCases([testCase()]);

    expect(mockGetTestCases).toHaveBeenCalledWith(
      expect.objectContaining({
        filters: expect.arrayContaining([
          {
            feature: "name",
            condition: TestingFilterCondition.CONTAINS,
            values: ["pay"],
          },
        ]),
      }),
      { offset: 0 },
    );
  });

  it("should key every sortable column on a field the service takes when opened from a chain", async () => {
    await renderWithCases([testCase()]);

    const keys = sortableColumnKeys();
    expect(keys.length).toBeGreaterThan(0);
    for (const key of keys) {
      expect(TEST_CASES_SORT_FIELDS).toContain(key);
    }
  });

  it("should key every sortable column on a field the service takes when opened outside a chain", async () => {
    await renderWithCases([testCase()], { global: true });

    const keys = sortableColumnKeys();
    expect(keys).toContain("chain_id");
    for (const key of keys) {
      expect(TEST_CASES_SORT_FIELDS).toContain(key);
    }
  });

  it("should send the sort field the column carries", async () => {
    await renderWithCases([testCase()]);
    const columnKey = sortKeyOfColumn("Active Rules");
    mockGetTestCases.mockClear();

    getLastTableOnChange()?.({}, {}, { columnKey, order: "descend" });

    await waitFor(() =>
      expect(mockGetTestCases).toHaveBeenCalledWith(expect.anything(), {
        offset: 0,
        sortBy: columnKey,
        sortOrder: TestingSortOrder.DESC,
      }),
    );
  });

  it("should drop the sort when the column is reset", async () => {
    await renderWithCases([testCase()]);
    const columnKey = sortKeyOfColumn("Name");

    getLastTableOnChange()?.({}, {}, { columnKey, order: "ascend" });
    await waitFor(() =>
      expect(mockGetTestCases).toHaveBeenCalledWith(expect.anything(), {
        offset: 0,
        sortBy: columnKey,
        sortOrder: TestingSortOrder.ASC,
      }),
    );
    mockGetTestCases.mockClear();

    getLastTableOnChange()?.({}, {}, { columnKey, order: undefined });

    await waitFor(() =>
      expect(mockGetTestCases).toHaveBeenCalledWith(expect.anything(), {
        offset: 0,
      }),
    );
  });
});

describe("TestCases bulk actions", () => {
  async function selectAllMatching() {
    fireEvent.click(screen.getByTestId("table-selection-all-matching"));
  }

  it("should delete the selected rows", async () => {
    await renderWithCases([
      testCase(),
      testCase({ id: "case-2", name: "Second case" }),
    ]);

    fireEvent.click(screen.getAllByRole("checkbox")[1]);
    fireEvent.click(screen.getByTestId("test-cases-delete"));

    expect(capturedConfirm?.content).toBe(
      "Delete 1 test case? This cannot be undone.",
    );
    await capturedConfirm?.onOk();
    expect(mockDeleteTestCases).toHaveBeenCalledWith(["case-1"]);
    expect(mockGetTestCaseIds).not.toHaveBeenCalled();
  });

  it("should resolve targets server-side when everything matching is selected", async () => {
    mockGetTestCaseIds.mockResolvedValue(["case-1", "case-2", "case-99"]);
    await renderWithCases([
      testCase(),
      testCase({ id: "case-2", name: "Second case" }),
    ]);

    await selectAllMatching();
    fireEvent.click(screen.getByTestId("test-cases-delete"));

    expect(capturedConfirm?.content).toBe(
      "Delete all test cases that match the filters? This cannot be undone.",
    );
    await capturedConfirm?.onOk();
    // The resolver has to repeat the selection of the list, or the delete would
    // reach past the chain the list is scoped to.
    expect(mockGetTestCaseIds).toHaveBeenCalledWith(lastListSpecification());
    expect(mockGetTestCaseIds).toHaveBeenCalledWith({
      filters: [
        {
          feature: "chain_id",
          condition: TestingFilterCondition.IS,
          values: ["chain-1"],
        },
      ],
    });
    expect(mockDeleteTestCases).toHaveBeenCalledWith([
      "case-1",
      "case-2",
      "case-99",
    ]);
  });

  it("should resolve targets under the filter when everything matching is selected", async () => {
    const filters: EntityFilterModel[] = [
      { column: "name", condition: FilterCondition.CONTAINS.id, value: "pay" },
    ];
    mockUseTestingFilter.mockReturnValue({ filters, filterButton: null });
    mockGetTestCaseIds.mockResolvedValue(["case-1"]);
    await renderWithCases([testCase()]);

    await selectAllMatching();
    fireEvent.click(screen.getByTestId("test-cases-delete"));
    await capturedConfirm?.onOk();

    expect(mockGetTestCaseIds).toHaveBeenCalledWith(lastListSpecification());
    expect(mockGetTestCaseIds).toHaveBeenCalledWith({
      filters: [
        {
          feature: "chain_id",
          condition: TestingFilterCondition.IS,
          values: ["chain-1"],
        },
        {
          feature: "name",
          condition: TestingFilterCondition.CONTAINS,
          values: ["pay"],
        },
      ],
    });
    expect(mockDeleteTestCases).toHaveBeenCalledWith(["case-1"]);
  });

  it("should export the selected rows", async () => {
    mockExportTestCases.mockResolvedValue(new File([], "cases.zip"));
    await renderWithCases([testCase()]);

    fireEvent.click(screen.getAllByRole("checkbox")[1]);
    fireEvent.click(screen.getByTestId("test-cases-export"));

    await waitFor(() =>
      expect(mockExportTestCases).toHaveBeenCalledWith(["case-1"]),
    );
  });

  it("should start a run and report it with a link", async () => {
    mockStartTestsRun.mockResolvedValue("run-7");
    await renderWithCases([testCase()]);

    fireEvent.click(screen.getAllByRole("checkbox")[1]);
    fireEvent.click(screen.getByTestId("test-cases-run"));

    await waitFor(() =>
      expect(mockStartTestsRun).toHaveBeenCalledWith(["case-1"]),
    );
    expect(mockNotificationService.info).toHaveBeenCalledWith(
      "Test run started",
      expect.anything(),
    );
  });

  it("should do nothing when no row is selected", async () => {
    await renderWithCases([testCase()]);

    fireEvent.click(screen.getByTestId("test-cases-run"));
    fireEvent.click(screen.getByTestId("test-cases-delete"));

    expect(mockStartTestsRun).not.toHaveBeenCalled();
    expect(capturedConfirm).toBeUndefined();
  });

  it("should report a failed run instead of throwing", async () => {
    mockStartTestsRun.mockRejectedValue(new Error("service is down"));
    await renderWithCases([testCase()]);

    fireEvent.click(screen.getAllByRole("checkbox")[1]);
    fireEvent.click(screen.getByTestId("test-cases-run"));

    await waitFor(() =>
      expect(mockNotificationService.requestFailed).toHaveBeenCalledWith(
        "Failed to start a test run",
        expect.anything(),
      ),
    );
  });
});

describe("TestCases selection lifetime", () => {
  /** Checked state of the row boxes, the header box aside. */
  function rowCheckedState(): boolean[] {
    return screen
      .getAllByRole("checkbox")
      .slice(1)
      .map((checkbox) => (checkbox as HTMLInputElement).checked);
  }

  const twoCases = [
    testCase(),
    testCase({ id: "case-2", name: "Second case" }),
  ];

  /** A fresh element per call: React skips a rerender of the very same one. */
  function chainTab() {
    return (
      <UserPermissionsContext.Provider value={ALL_PERMISSIONS}>
        <ChainHeaderTestRoot>
          <TestCases variant="chain-tab" />
        </ChainHeaderTestRoot>
      </UserPermissionsContext.Provider>
    );
  }

  it("should drop the selection when the search or the sort changes", async () => {
    mockGetTestCases.mockResolvedValue(twoCases);
    renderTestCases();
    await screen.findByText("Second case");

    fireEvent.click(screen.getAllByRole("checkbox")[1]);
    expect(rowCheckedState()).toEqual([true, false]);

    fireEvent.change(screen.getByTestId("search-input"), {
      target: { value: "second" },
    });
    await waitFor(() => expect(rowCheckedState()).toEqual([false, false]));

    // Rows the search has hidden are rows a delete must not reach.
    fireEvent.click(screen.getByTestId("test-cases-delete"));
    expect(capturedConfirm).toBeUndefined();

    fireEvent.click(screen.getAllByRole("checkbox")[1]);
    expect(rowCheckedState()).toEqual([true, false]);

    getLastTableOnChange()?.(
      {},
      {},
      { columnKey: sortKeyOfColumn("Name"), order: "descend" },
    );
    await waitFor(() => expect(rowCheckedState()).toEqual([false, false]));
  });

  it("should drop the selection when the filters change", async () => {
    mockUseTestingFilter.mockReturnValue({ filters: [], filterButton: null });
    mockGetTestCases.mockResolvedValue(twoCases);
    const { rerender } = render(chainTab());
    await screen.findByText("Second case");

    fireEvent.click(screen.getAllByRole("checkbox")[1]);
    expect(rowCheckedState()).toEqual([true, false]);

    mockUseTestingFilter.mockReturnValue({
      filters: [
        {
          column: "name",
          condition: FilterCondition.CONTAINS.id,
          value: "pay",
        },
      ],
      filterButton: null,
    });
    rerender(chainTab());

    await waitFor(() => expect(rowCheckedState()).toEqual([false, false]));
  });

  it("should extend a select-all-matching selection over the next page", async () => {
    mockGetTestCases
      .mockResolvedValueOnce([testCase()])
      .mockResolvedValueOnce([testCase({ id: "case-2", name: "Second case" })])
      .mockResolvedValue([]);
    renderTestCases();
    await screen.findByText("First case");
    await waitFor(() =>
      expect(screen.queryByTestId("table-loading")).not.toBeInTheDocument(),
    );

    fireEvent.click(screen.getByTestId("table-selection-all-matching"));
    await waitFor(() => expect(rowCheckedState()).toEqual([true]));

    act(() => triggerIntersection());
    await screen.findByText("Second case");

    expect(rowCheckedState()).toEqual([true, true]);
  });
});

describe("TestCases permission gating", () => {
  it("should hide every write action without chain rights", async () => {
    await renderWithCases([testCase()], { permissions: { chain: ["read"] } });

    expect(screen.getByTestId("test-cases-refresh")).toBeInTheDocument();
    expect(screen.queryByTestId("test-cases-create")).not.toBeInTheDocument();
    expect(screen.queryByTestId("test-cases-delete")).not.toBeInTheDocument();
    expect(screen.queryByTestId("test-cases-run")).not.toBeInTheDocument();
    expect(screen.queryByTestId("test-cases-export")).not.toBeInTheDocument();
  });

  it("should gate the global list on admin tools rights", async () => {
    await renderWithCases([testCase()], {
      global: true,
      permissions: { chain: ["read", "update", "execute", "export", "import"] },
    });

    expect(screen.queryByTestId("test-cases-refresh")).not.toBeInTheDocument();
    expect(screen.queryByTestId("test-cases-import")).not.toBeInTheDocument();
  });

  it("should offer Run alone when the execute right is the only one granted", async () => {
    await renderWithCases([testCase()], {
      permissions: { chain: ["execute"] },
    });

    expect(screen.getByTestId("test-cases-run")).toBeInTheDocument();
    expect(screen.queryByTestId("test-cases-refresh")).not.toBeInTheDocument();
    expect(screen.queryByTestId("test-cases-export")).not.toBeInTheDocument();
    expect(screen.queryByTestId("test-cases-delete")).not.toBeInTheDocument();
    expect(screen.queryByTestId("test-cases-create")).not.toBeInTheDocument();
  });

  it("should offer Export alone when the export right is the only one granted", async () => {
    await renderWithCases([testCase()], {
      permissions: { chain: ["export"] },
    });

    expect(screen.getByTestId("test-cases-export")).toBeInTheDocument();
    expect(screen.queryByTestId("test-cases-refresh")).not.toBeInTheDocument();
    expect(screen.queryByTestId("test-cases-run")).not.toBeInTheDocument();
    expect(screen.queryByTestId("test-cases-delete")).not.toBeInTheDocument();
  });

  it("should offer Create and Delete when the update right is the only one granted", async () => {
    await renderWithCases([testCase()], {
      permissions: { chain: ["update"] },
    });

    expect(screen.getByTestId("test-cases-create")).toBeInTheDocument();
    expect(screen.getByTestId("test-cases-delete")).toBeInTheDocument();
    expect(screen.queryByTestId("test-cases-refresh")).not.toBeInTheDocument();
    expect(screen.queryByTestId("test-cases-run")).not.toBeInTheDocument();
    expect(screen.queryByTestId("test-cases-export")).not.toBeInTheDocument();
  });

  it("should offer Import alone when the admin import right is the only one granted", async () => {
    await renderWithCases([testCase()], {
      global: true,
      permissions: { adminTools: ["import"] },
    });

    expect(screen.getByTestId("test-cases-import")).toBeInTheDocument();
    expect(screen.queryByTestId("test-cases-refresh")).not.toBeInTheDocument();
    expect(screen.queryByTestId("test-cases-run")).not.toBeInTheDocument();
    expect(screen.queryByTestId("test-cases-export")).not.toBeInTheDocument();
    expect(screen.queryByTestId("test-cases-delete")).not.toBeInTheDocument();
  });
});

describe("TestCases create and import", () => {
  function shownModal<P>(): React.ReactElement<P> {
    expect(mockShowModal).toHaveBeenCalledTimes(1);
    const { component } = mockShowModal.mock.calls[0][0] as {
      component: React.ReactElement<P>;
    };
    return component;
  }

  it("should open the create modal for the chain and follow the new case", async () => {
    await renderWithCases([testCase()]);

    fireEvent.click(screen.getByTestId("test-cases-create"));

    const modal = shownModal<CreateTestCaseModalProps>();
    expect(modal.type).toBe(CreateTestCaseModal);
    expect(modal.props.chainId).toBe("chain-1");

    modal.props.onCreated({ id: "case-9" } as TestCase);
    expect(mockNavigate).toHaveBeenCalledWith(
      "/chains/chain-1/testing/test-cases/case-9",
    );
  });

  it("should open the import modal and reload the list once it imported", async () => {
    await renderWithCases([testCase()], { global: true });
    mockGetTestCases.mockClear();

    fireEvent.click(screen.getByTestId("test-cases-import"));

    const modal = shownModal<TestingImportModalProps>();
    expect(modal.type).toBe(TestingImportModal);
    expect(modal.props.title).toBe("Import Test Cases");

    // The binding is what a copy-paste would get wrong, so it is exercised.
    await modal.props.importFiles([new File([""], "cases.zip")]);
    expect(mockImportTestCases).toHaveBeenCalledWith([
      expect.objectContaining({ name: "cases.zip" }),
    ]);

    modal.props.onImported();
    await waitFor(() => expect(mockGetTestCases).toHaveBeenCalled());
  });
});
