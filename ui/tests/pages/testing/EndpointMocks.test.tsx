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
  EndpointMock,
  TestingFilterCondition,
  TestingSelectionSpecification,
  TestingSortOrder,
} from "../../../src/api/apiTypes.ts";
import { api } from "../../../src/api/api.ts";
import {
  CreateEndpointMockModal,
  type CreateEndpointMockModalProps,
} from "../../../src/components/modal/testing/CreateEndpointMockModal.tsx";
import {
  TestingImportModal,
  type TestingImportModalProps,
} from "../../../src/components/modal/testing/TestingImportModal.tsx";
import { UserPermissionsContext } from "../../../src/permissions/UserPermissionsContext.tsx";
import type { UserPermissions } from "../../../src/permissions/types.ts";
import {
  ENDPOINT_MOCKS_SORT_FIELDS,
  useTestingFilter,
} from "../../../src/hooks/filter/useTestingFilter.ts";
import type { EntityFilterModel } from "../../../src/components/table/filter/filterTypes.ts";
import { FilterCondition } from "../../../src/components/table/filter/filterTypes.ts";
import {
  getLastTableOnChange,
  LightweightTable as mockLightweightTable,
} from "../../__mocks__/LightweightTable.tsx";
import { EndpointMocks } from "../../../src/pages/testing/EndpointMocks.tsx";
import { ChainHeaderTestRoot } from "../../helpers/renderWithChainHeader.tsx";
import { triggerIntersection } from "../../setup/intersection-observer.ts";
import {
  type CapturedConfirm,
  mockListScaffolding,
} from "../../helpers/testingListScaffolding.ts";

jest.mock("../../../src/api/api.ts", () => ({
  api: {
    getEndpointMocks: jest.fn(),
    getEndpointMockIds: jest.fn(),
    deleteEndpointMocks: jest.fn(),
    exportEndpointMocks: jest.fn(),
    getChains: jest.fn(),
    getElements: jest.fn(),
    createEndpointMock: jest.fn(),
    importEndpointMocks: jest.fn(),
  },
}));

const mockGetEndpointMocks = jest.spyOn(api, "getEndpointMocks");
const mockGetEndpointMockIds = jest.spyOn(api, "getEndpointMockIds");
const mockDeleteEndpointMocks = jest.spyOn(api, "deleteEndpointMocks");
const mockExportEndpointMocks = jest.spyOn(api, "exportEndpointMocks");
const mockGetChains = jest.spyOn(api, "getChains");
const mockGetElements = jest.spyOn(api, "getElements");
const mockImportEndpointMocks = jest.spyOn(api, "importEndpointMocks");

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
      mockListScaffolding.recordColumns(props.columns);
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
  // Called, not referenced: the factory runs while the page module loads, before
  // this suite's own imports are initialized.
  confirmAndRun: (options: CapturedConfirm) =>
    mockListScaffolding.captureConfirm(options),
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

function endpointMock(overrides: Partial<EndpointMock> = {}): EndpointMock {
  return {
    id: "mock-1",
    name: "First mock",
    description: "First description",
    enabled: true,
    endpointReference: { chainId: "chain-1", elementId: "element-1" },
    responseSettings: {
      message: null,
      status: 200,
      delay: 0,
    },
    requestMatchers: [],
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

function renderEndpointMocks({
  global = false,
  permissions = ALL_PERMISSIONS,
}: RenderOptions = {}) {
  mockUseParams.mockReturnValue({ chainId: global ? undefined : "chain-1" });
  return render(
    <UserPermissionsContext.Provider value={permissions}>
      <ChainHeaderTestRoot>
        <EndpointMocks variant={global ? "admin-page" : "chain-tab"} />
      </ChainHeaderTestRoot>
    </UserPermissionsContext.Provider>,
  );
}

async function renderWithMocks(
  mocks: EndpointMock[],
  options: RenderOptions = {},
) {
  mockGetEndpointMocks.mockResolvedValueOnce(mocks).mockResolvedValue([]);
  const result = renderEndpointMocks(options);
  await waitFor(() => expect(mockGetEndpointMocks).toHaveBeenCalled());
  if (mocks.length > 0) {
    await screen.findByText(mocks[0].name);
  }
  return result;
}

/** Selection of the newest list request, which the id resolver has to repeat. */
function lastListSpecification(): TestingSelectionSpecification {
  const calls = mockGetEndpointMocks.mock.calls;
  expect(calls.length).toBeGreaterThan(0);
  return calls[calls.length - 1][0];
}

function sortableColumnKeys(): string[] {
  return mockListScaffolding.columns
    .filter((column) => column.sorter)
    .map((column) => String(column.key));
}

/** Sort key the named column carries, so no test types a key of its own. */
function sortKeyOfColumn(title: string): string {
  const column = mockListScaffolding.columns.find(
    (entry) => entry.title === title,
  );
  expect(column?.sorter).toBe(true);
  return String(column?.key);
}

beforeEach(() => {
  mockListScaffolding.reset();
  mockShowModal.mockClear();
  mockUseParams.mockReturnValue({ chainId: "chain-1" });
  mockGetChains.mockResolvedValue([]);
  mockGetElements.mockResolvedValue([]);
  mockGetEndpointMocks.mockResolvedValue([]);
  mockGetEndpointMockIds.mockResolvedValue([]);
  mockUseTestingFilter.mockImplementation((kind, chainId) =>
    jest
      .requireActual<
        typeof import("../../../src/hooks/filter/useTestingFilter.ts")
      >("../../../src/hooks/filter/useTestingFilter.ts")
      .useTestingFilter(kind, chainId),
  );
});

describe("EndpointMocks list variants", () => {
  it("should scope the request to the chain when opened from a chain", async () => {
    await renderWithMocks([endpointMock()]);

    expect(mockGetEndpointMocks).toHaveBeenCalledWith(
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
    await renderWithMocks([endpointMock()], { global: true });

    expect(mockGetEndpointMocks).toHaveBeenCalledWith({}, { offset: 0 });
  });

  it("should offer Create but not Import inside a chain", async () => {
    await renderWithMocks([endpointMock()]);

    expect(screen.getByTestId("endpoint-mocks-create")).toBeInTheDocument();
    expect(
      screen.queryByTestId("endpoint-mocks-import"),
    ).not.toBeInTheDocument();
  });

  it("should offer Import but not Create outside a chain", async () => {
    await renderWithMocks([endpointMock()], { global: true });

    expect(screen.getByTestId("endpoint-mocks-import")).toBeInTheDocument();
    expect(
      screen.queryByTestId("endpoint-mocks-create"),
    ).not.toBeInTheDocument();
  });

  it("should hide the chain column when opened from a chain", async () => {
    await renderWithMocks([endpointMock()]);

    expect(screen.queryByText("Chain")).not.toBeInTheDocument();
    expect(screen.getByText("Element")).toBeInTheDocument();
  });

  it("should show the chain column when opened outside a chain", async () => {
    await renderWithMocks([endpointMock()], { global: true });

    expect(screen.getByText("Chain")).toBeInTheDocument();
    expect(screen.getByText("Element")).toBeInTheDocument();
  });
});

describe("EndpointMocks rows", () => {
  it("should show the response status and the delay, keeping a delay of zero", async () => {
    await renderWithMocks([
      endpointMock({
        responseSettings: { message: null, status: 503, delay: 0 },
      }),
    ]);

    expect(screen.getByText("Response Status")).toBeInTheDocument();
    expect(screen.getByText("Response Delay")).toBeInTheDocument();
    expect(screen.getByText("503")).toBeInTheDocument();
    expect(screen.getByText("0")).toBeInTheDocument();
  });

  it("should show a placeholder when a mock carries no response settings", async () => {
    await renderWithMocks([endpointMock({ responseSettings: null })]);

    expect(screen.getAllByText("—").length).toBeGreaterThan(0);
  });

  it("should read the chain and the element off the endpoint reference", async () => {
    mockGetChains.mockResolvedValue([
      { id: "chain-9", name: "Ninth chain" },
    ] as never);
    await renderWithMocks(
      [
        endpointMock({
          endpointReference: { chainId: "chain-9", elementId: "element-9" },
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

  it("should open the details drawer when a row is clicked", async () => {
    await renderWithMocks([endpointMock()]);

    fireEvent.click(screen.getByText("First description"));

    expect(
      await screen.findByText("Endpoint Mock Details"),
    ).toBeInTheDocument();
  });

  it("should navigate to the editor when the name is clicked", async () => {
    await renderWithMocks([endpointMock()]);

    fireEvent.click(screen.getByText("First mock"));

    expect(mockNavigate).toHaveBeenCalledWith(
      "/chains/chain-1/testing/endpoint-mocks/mock-1",
    );
  });
});

describe("EndpointMocks filters and sorting", () => {
  it("should map a filter onto the selection the service takes", async () => {
    const filters: EntityFilterModel[] = [
      {
        column: "delay",
        condition: FilterCondition.GREATER_THAN.id,
        value: "100",
      },
    ];
    mockUseTestingFilter.mockReturnValue({
      filters,
      filterButton: null,
    });

    await renderWithMocks([endpointMock()]);

    expect(mockGetEndpointMocks).toHaveBeenCalledWith(
      expect.objectContaining({
        filters: expect.arrayContaining([
          {
            feature: "delay",
            condition: TestingFilterCondition.GREATER_THAN,
            values: ["100"],
          },
        ]),
      }),
      { offset: 0 },
    );
  });

  it("should key every sortable column on a field the service takes when opened from a chain", async () => {
    await renderWithMocks([endpointMock()]);

    const keys = sortableColumnKeys();
    expect(keys.length).toBeGreaterThan(0);
    for (const key of keys) {
      expect(ENDPOINT_MOCKS_SORT_FIELDS).toContain(key);
    }
  });

  it("should key every sortable column on a field the service takes when opened outside a chain", async () => {
    await renderWithMocks([endpointMock()], { global: true });

    const keys = sortableColumnKeys();
    expect(keys).toContain("chain_id");
    for (const key of keys) {
      expect(ENDPOINT_MOCKS_SORT_FIELDS).toContain(key);
    }
  });

  it("should send the sort field the column carries", async () => {
    await renderWithMocks([endpointMock()]);
    const columnKey = sortKeyOfColumn("Response Delay");
    mockGetEndpointMocks.mockClear();

    getLastTableOnChange()?.({}, {}, { columnKey, order: "descend" });

    await waitFor(() =>
      expect(mockGetEndpointMocks).toHaveBeenCalledWith(expect.anything(), {
        offset: 0,
        sortBy: columnKey,
        sortOrder: TestingSortOrder.DESC,
      }),
    );
  });
});

describe("EndpointMocks bulk actions", () => {
  it("should delete the selected rows", async () => {
    await renderWithMocks([
      endpointMock(),
      endpointMock({ id: "mock-2", name: "Second mock" }),
    ]);

    fireEvent.click(screen.getAllByRole("checkbox")[1]);
    fireEvent.click(screen.getByTestId("endpoint-mocks-delete"));

    expect(mockListScaffolding.confirm?.content).toBe(
      "Delete 1 endpoint mock? This cannot be undone.",
    );
    await mockListScaffolding.confirm?.onOk();
    expect(mockDeleteEndpointMocks).toHaveBeenCalledWith(["mock-1"]);
    expect(mockGetEndpointMockIds).not.toHaveBeenCalled();
  });

  it("should resolve targets server-side when everything matching is selected", async () => {
    mockGetEndpointMockIds.mockResolvedValue(["mock-1", "mock-2", "mock-99"]);
    await renderWithMocks([
      endpointMock(),
      endpointMock({ id: "mock-2", name: "Second mock" }),
    ]);

    fireEvent.click(screen.getByTestId("table-selection-all-matching"));
    fireEvent.click(screen.getByTestId("endpoint-mocks-delete"));

    expect(mockListScaffolding.confirm?.content).toBe(
      "Delete all endpoint mocks that match the filters? This cannot be undone.",
    );
    await mockListScaffolding.confirm?.onOk();
    // The resolver has to repeat the selection of the list, or the delete would
    // reach past the chain the list is scoped to.
    expect(mockGetEndpointMockIds).toHaveBeenCalledWith(
      lastListSpecification(),
    );
    expect(mockGetEndpointMockIds).toHaveBeenCalledWith({
      filters: [
        {
          feature: "chain_id",
          condition: TestingFilterCondition.IS,
          values: ["chain-1"],
        },
      ],
    });
    expect(mockDeleteEndpointMocks).toHaveBeenCalledWith([
      "mock-1",
      "mock-2",
      "mock-99",
    ]);
  });

  it("should resolve targets under the filter when everything matching is selected", async () => {
    const filters: EntityFilterModel[] = [
      {
        column: "delay",
        condition: FilterCondition.GREATER_THAN.id,
        value: "100",
      },
    ];
    mockUseTestingFilter.mockReturnValue({ filters, filterButton: null });
    mockGetEndpointMockIds.mockResolvedValue(["mock-1"]);
    await renderWithMocks([endpointMock()]);

    fireEvent.click(screen.getByTestId("table-selection-all-matching"));
    fireEvent.click(screen.getByTestId("endpoint-mocks-delete"));
    await mockListScaffolding.confirm?.onOk();

    expect(mockGetEndpointMockIds).toHaveBeenCalledWith(
      lastListSpecification(),
    );
    expect(mockGetEndpointMockIds).toHaveBeenCalledWith({
      filters: [
        {
          feature: "chain_id",
          condition: TestingFilterCondition.IS,
          values: ["chain-1"],
        },
        {
          feature: "delay",
          condition: TestingFilterCondition.GREATER_THAN,
          values: ["100"],
        },
      ],
    });
    expect(mockDeleteEndpointMocks).toHaveBeenCalledWith(["mock-1"]);
  });

  it("should export the selected rows", async () => {
    mockExportEndpointMocks.mockResolvedValue(new File([], "mocks.zip"));
    await renderWithMocks([endpointMock()]);

    fireEvent.click(screen.getAllByRole("checkbox")[1]);
    fireEvent.click(screen.getByTestId("endpoint-mocks-export"));

    await waitFor(() =>
      expect(mockExportEndpointMocks).toHaveBeenCalledWith(["mock-1"]),
    );
  });

  it("should do nothing when no row is selected", async () => {
    await renderWithMocks([endpointMock()]);

    fireEvent.click(screen.getByTestId("endpoint-mocks-delete"));
    fireEvent.click(screen.getByTestId("endpoint-mocks-export"));

    expect(mockListScaffolding.confirm).toBeUndefined();
    expect(mockExportEndpointMocks).not.toHaveBeenCalled();
  });

  it("should offer no run action, which mocks have none of", async () => {
    await renderWithMocks([endpointMock()]);

    expect(screen.queryByTestId("endpoint-mocks-run")).not.toBeInTheDocument();
  });
});

describe("EndpointMocks selection lifetime", () => {
  /** Checked state of the row boxes, the header box aside. */
  function rowCheckedState(): boolean[] {
    return screen
      .getAllByRole("checkbox")
      .slice(1)
      .map((checkbox) => (checkbox as HTMLInputElement).checked);
  }

  const twoMocks = [
    endpointMock(),
    endpointMock({ id: "mock-2", name: "Second mock" }),
  ];

  /** A fresh element per call: React skips a rerender of the very same one. */
  function chainTab() {
    return (
      <UserPermissionsContext.Provider value={ALL_PERMISSIONS}>
        <ChainHeaderTestRoot>
          <EndpointMocks variant="chain-tab" />
        </ChainHeaderTestRoot>
      </UserPermissionsContext.Provider>
    );
  }

  it("should drop the selection when the search or the sort changes", async () => {
    mockGetEndpointMocks.mockResolvedValue(twoMocks);
    renderEndpointMocks();
    await screen.findByText("Second mock");

    fireEvent.click(screen.getAllByRole("checkbox")[1]);
    expect(rowCheckedState()).toEqual([true, false]);

    fireEvent.change(screen.getByTestId("search-input"), {
      target: { value: "second" },
    });
    await waitFor(() => expect(rowCheckedState()).toEqual([false, false]));

    // Rows the search has hidden are rows a delete must not reach.
    fireEvent.click(screen.getByTestId("endpoint-mocks-delete"));
    expect(mockListScaffolding.confirm).toBeUndefined();

    fireEvent.click(screen.getAllByRole("checkbox")[1]);
    expect(rowCheckedState()).toEqual([true, false]);

    getLastTableOnChange()?.(
      {},
      {},
      { columnKey: sortKeyOfColumn("Response Delay"), order: "descend" },
    );
    await waitFor(() => expect(rowCheckedState()).toEqual([false, false]));
  });

  it("should drop the selection when the filters change", async () => {
    mockUseTestingFilter.mockReturnValue({ filters: [], filterButton: null });
    mockGetEndpointMocks.mockResolvedValue(twoMocks);
    const { rerender } = render(chainTab());
    await screen.findByText("Second mock");

    fireEvent.click(screen.getAllByRole("checkbox")[1]);
    expect(rowCheckedState()).toEqual([true, false]);

    mockUseTestingFilter.mockReturnValue({
      filters: [
        {
          column: "delay",
          condition: FilterCondition.GREATER_THAN.id,
          value: "100",
        },
      ],
      filterButton: null,
    });
    rerender(chainTab());

    await waitFor(() => expect(rowCheckedState()).toEqual([false, false]));
  });

  it("should extend a select-all-matching selection over the next page", async () => {
    mockGetEndpointMocks
      .mockResolvedValueOnce([endpointMock()])
      .mockResolvedValueOnce([
        endpointMock({ id: "mock-2", name: "Second mock" }),
      ])
      .mockResolvedValue([]);
    renderEndpointMocks();
    await screen.findByText("First mock");
    await waitFor(() =>
      expect(screen.queryByTestId("table-loading")).not.toBeInTheDocument(),
    );

    fireEvent.click(screen.getByTestId("table-selection-all-matching"));
    await waitFor(() => expect(rowCheckedState()).toEqual([true]));

    act(() => triggerIntersection());
    await screen.findByText("Second mock");

    expect(rowCheckedState()).toEqual([true, true]);
  });
});

describe("EndpointMocks actions without a selection", () => {
  const SELECTION_ACTIONS = ["endpoint-mocks-export", "endpoint-mocks-delete"];

  it("should offer neither bulk action while nothing is selected", async () => {
    await renderWithMocks([endpointMock()]);

    for (const action of SELECTION_ACTIONS) {
      expect(screen.getByTestId(action)).toBeDisabled();
    }
  });

  it("should offer them both once a row is selected", async () => {
    await renderWithMocks([endpointMock()]);

    fireEvent.click(screen.getAllByRole("checkbox")[1]);

    for (const action of SELECTION_ACTIONS) {
      expect(screen.getByTestId(action)).not.toBeDisabled();
    }
  });

  it("should offer them both when everything matching is selected", async () => {
    await renderWithMocks([endpointMock()]);

    fireEvent.click(screen.getByTestId("table-selection-all-matching"));

    for (const action of SELECTION_ACTIONS) {
      expect(screen.getByTestId(action)).not.toBeDisabled();
    }
  });
});

describe("EndpointMocks permission gating", () => {
  it("should hide every write action without chain rights", async () => {
    await renderWithMocks([endpointMock()], {
      permissions: { chain: ["read"] },
    });

    expect(screen.getByTestId("endpoint-mocks-refresh")).toBeInTheDocument();
    expect(
      screen.queryByTestId("endpoint-mocks-create"),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("endpoint-mocks-delete"),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("endpoint-mocks-export"),
    ).not.toBeInTheDocument();
  });

  it("should gate the global list on admin tools rights", async () => {
    await renderWithMocks([endpointMock()], {
      global: true,
      permissions: { chain: ["read", "update", "execute", "export", "import"] },
    });

    expect(
      screen.queryByTestId("endpoint-mocks-refresh"),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("endpoint-mocks-import"),
    ).not.toBeInTheDocument();
  });

  it("should offer Export alone when the export right is the only one granted", async () => {
    await renderWithMocks([endpointMock()], {
      permissions: { chain: ["export"] },
    });

    expect(screen.getByTestId("endpoint-mocks-export")).toBeInTheDocument();
    expect(
      screen.queryByTestId("endpoint-mocks-refresh"),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("endpoint-mocks-delete"),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("endpoint-mocks-create"),
    ).not.toBeInTheDocument();
  });

  it("should offer Create and Delete when the update right is the only one granted", async () => {
    await renderWithMocks([endpointMock()], {
      permissions: { chain: ["update"] },
    });

    expect(screen.getByTestId("endpoint-mocks-create")).toBeInTheDocument();
    expect(screen.getByTestId("endpoint-mocks-delete")).toBeInTheDocument();
    expect(
      screen.queryByTestId("endpoint-mocks-refresh"),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("endpoint-mocks-export"),
    ).not.toBeInTheDocument();
  });

  it("should offer Import alone when the admin import right is the only one granted", async () => {
    await renderWithMocks([endpointMock()], {
      global: true,
      permissions: { adminTools: ["import"] },
    });

    expect(screen.getByTestId("endpoint-mocks-import")).toBeInTheDocument();
    expect(
      screen.queryByTestId("endpoint-mocks-refresh"),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("endpoint-mocks-export"),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("endpoint-mocks-delete"),
    ).not.toBeInTheDocument();
  });
});

describe("EndpointMocks create and import", () => {
  function shownModal<P>(): React.ReactElement<P> {
    expect(mockShowModal).toHaveBeenCalledTimes(1);
    const { component } = mockShowModal.mock.calls[0][0] as {
      component: React.ReactElement<P>;
    };
    return component;
  }

  it("should open the create modal for the chain and follow the new mock", async () => {
    await renderWithMocks([endpointMock()]);

    fireEvent.click(screen.getByTestId("endpoint-mocks-create"));

    const modal = shownModal<CreateEndpointMockModalProps>();
    expect(modal.type).toBe(CreateEndpointMockModal);
    expect(modal.props.chainId).toBe("chain-1");

    modal.props.onCreated({ id: "mock-9" } as EndpointMock);
    expect(mockNavigate).toHaveBeenCalledWith(
      "/chains/chain-1/testing/endpoint-mocks/mock-9",
    );
  });

  it("should open the import modal and reload the list once it imported", async () => {
    await renderWithMocks([endpointMock()], { global: true });
    mockGetEndpointMocks.mockClear();

    fireEvent.click(screen.getByTestId("endpoint-mocks-import"));

    const modal = shownModal<TestingImportModalProps>();
    expect(modal.type).toBe(TestingImportModal);
    expect(modal.props.title).toBe("Import Endpoint Mocks");

    // The binding is what a copy-paste would get wrong, so it is exercised.
    await modal.props.importFiles([new File([""], "mocks.zip")]);
    expect(mockImportEndpointMocks).toHaveBeenCalledWith([
      expect.objectContaining({ name: "mocks.zip" }),
    ]);

    modal.props.onImported();
    await waitFor(() => expect(mockGetEndpointMocks).toHaveBeenCalled());
  });
});
