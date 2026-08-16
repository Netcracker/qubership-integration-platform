/**
 * @jest-environment jsdom
 */
import React from "react";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom";
import {
  MatcherEntityType,
  MatcherType,
  TestCase,
  TestCaseView,
  TestingFilterCondition,
  TestingSortOrder,
} from "../../../src/api/apiTypes.ts";
import { api } from "../../../src/api/api.ts";
import {
  CreateTestCaseModal,
  type CreateTestCaseModalProps,
} from "../../../src/components/modal/testing/CreateTestCaseModal.tsx";
import {
  ImportTestCasesModal,
  type ImportTestCasesModalProps,
} from "../../../src/components/modal/testing/ImportTestCasesModal.tsx";
import { UserPermissionsContext } from "../../../src/permissions/UserPermissionsContext.tsx";
import type { UserPermissions } from "../../../src/permissions/types.ts";
import { useTestingFilter } from "../../../src/hooks/filter/useTestingFilter.ts";
import type { EntityFilterModel } from "../../../src/components/table/filter/filterTypes.ts";
import { FilterCondition } from "../../../src/components/table/filter/filterTypes.ts";
import { getLastTableOnChange } from "../../__mocks__/LightweightTable.tsx";
import { TestCases } from "../../../src/pages/testing/TestCases.tsx";
import { ChainHeaderTestRoot } from "../../helpers/renderWithChainHeader.tsx";

let capturedConfirm:
  | { title: React.ReactNode; content?: React.ReactNode; onOk: () => unknown }
  | undefined;

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

const mockNavigate = jest.fn();
const mockUseParams: jest.Mock<{ chainId?: string }> = jest.fn(() => ({
  chainId: "chain-1",
}));

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

beforeEach(() => {
  capturedConfirm = undefined;
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

  it("should show the chain column only outside a chain", async () => {
    const { unmount } = await renderWithCases([testCase()]);
    expect(screen.queryByText("Chain")).not.toBeInTheDocument();
    expect(screen.getByText("Element")).toBeInTheDocument();
    unmount();

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
      resetFilters: jest.fn(),
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

  it("should send the sort field the column carries", async () => {
    await renderWithCases([testCase()]);
    mockGetTestCases.mockClear();

    getLastTableOnChange()?.(
      {},
      {},
      {
        columnKey: "enabled_rule_count",
        order: "descend",
      },
    );

    await waitFor(() =>
      expect(mockGetTestCases).toHaveBeenCalledWith(expect.anything(), {
        offset: 0,
        sortBy: "enabled_rule_count",
        sortOrder: TestingSortOrder.DESC,
      }),
    );
  });

  it("should drop the sort when the column is reset", async () => {
    await renderWithCases([testCase()]);

    getLastTableOnChange()?.({}, {}, { columnKey: "name", order: "ascend" });
    await waitFor(() =>
      expect(mockGetTestCases).toHaveBeenCalledWith(expect.anything(), {
        offset: 0,
        sortBy: "name",
        sortOrder: TestingSortOrder.ASC,
      }),
    );
    mockGetTestCases.mockClear();

    getLastTableOnChange()?.({}, {}, { columnKey: "name", order: undefined });

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
    expect(mockGetTestCaseIds).toHaveBeenCalled();
    expect(mockDeleteTestCases).toHaveBeenCalledWith([
      "case-1",
      "case-2",
      "case-99",
    ]);
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

    const modal = shownModal<ImportTestCasesModalProps>();
    expect(modal.type).toBe(ImportTestCasesModal);

    modal.props.onImported();
    await waitFor(() => expect(mockGetTestCases).toHaveBeenCalled());
  });
});
