/**
 * @jest-environment jsdom
 */

import { describe, it, expect, beforeEach, jest } from "@jest/globals";
import { render, waitFor, screen, fireEvent } from "@testing-library/react";
import "@testing-library/jest-dom";
jest.mock("antd", () => {
  const {
    antdMockWithLightweightTable,
  } = require("tests/helpers/antdMockWithLightweightTable");
  const React = require("react");
  return antdMockWithLightweightTable({
    message: {
      info: (...args: unknown[]) => mockMessageInfo(...args),
      success: (...args: unknown[]) => mockMessageSuccess(...args),
    },
  });
});

// eslint-disable-next-line prefer-const
let mockMessageInfo = jest.fn();
// eslint-disable-next-line prefer-const
let mockMessageSuccess = jest.fn();

import { ContextServiceList } from "../../../../src/components/services/context/ContextServiceList";
import type { Chain, ContextSystem } from "../../../../src/api/apiTypes";
import { message } from "antd";

// Mock matchMedia for Ant Design components
Object.defineProperty(window, "matchMedia", {
  writable: true,
  value: jest.fn().mockImplementation((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: jest.fn(),
    removeListener: jest.fn(),
    addEventListener: jest.fn(),
    removeEventListener: jest.fn(),
    dispatchEvent: jest.fn(),
  })),
});

// Mock API
const mockGetContextServices = jest.fn<() => Promise<ContextSystem[]>>();
const mockSearchContextServices =
  jest.fn<(query: string) => Promise<ContextSystem[]>>();
const mockFilterContextServices =
  jest.fn<(filters: unknown[]) => Promise<ContextSystem[]>>();
const mockUpdateContextService =
  jest.fn<
    (id: string, changes: Partial<ContextSystem>) => Promise<ContextSystem>
  >();
const mockDeleteContextService = jest.fn<(id: string) => Promise<void>>();
const mockExportContextServices = jest.fn<(ids: string[]) => Promise<Blob>>();
const mockCreateContextService =
  jest.fn<
    (data: { name: string; description: string }) => Promise<ContextSystem>
  >();
const mockShowModal = jest.fn();

jest.mock("../../../../src/api/api", () => ({
  api: {
    getContextServices: (...args: unknown[]) => mockGetContextServices(...args),
    searchContextServices: (...args: unknown[]) =>
      mockSearchContextServices(...args),
    filterContextServices: (...args: unknown[]) =>
      mockFilterContextServices(...args),
    updateContextService: (...args: unknown[]) =>
      mockUpdateContextService(...args),
    deleteContextService: (...args: unknown[]) =>
      mockDeleteContextService(...args),
    exportContextServices: (...args: unknown[]) =>
      mockExportContextServices(...args),
    createContextService: (...args: unknown[]) =>
      mockCreateContextService(...args),
  },
}));

// Mock hooks
const mockNavigate = jest.fn();
jest.mock("react-router-dom", () => ({
  useNavigate: () => mockNavigate,
  Link: ({ children, to }: { children: React.ReactNode; to: string }) => (
    <a href={to}>{children}</a>
  ),
}));

const mockRequestFailed = jest.fn();
jest.mock("../../../../src/hooks/useNotificationService", () => ({
  useNotificationService: () => ({
    requestFailed: mockRequestFailed,
  }),
}));

jest.mock("antd/es/message", () => ({
  success: jest.fn(),
  info: jest.fn(),
}));

jest.mock("../../../../src/icons/IconProvider.tsx", () => ({
  OverridableIcon: ({ name }: { name: string }) => (
    <span data-testid={`icon-${name}`} />
  ),
}));

jest.mock("../../../../src/misc/format-utils.ts", () => ({
  formatTimestamp: (ts: string | undefined) => ts ?? "",
  formatOptional: (value: string | undefined) => value ?? "",
}));

jest.mock("../../../../src/misc/download-utils.ts", () => ({
  downloadFile: jest.fn(),
}));

jest.mock("../../../../src/components/services/utils.tsx", () => ({
  prepareFile: (file: Blob) => file,
}));

jest.mock("../../../../src/permissions/Require.tsx", () => ({
  Require: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));

jest.mock("../../../../src/permissions/ProtectedDropdown.tsx", () => ({
  ProtectedDropdown: ({
    children,
    menu,
  }: {
    children: React.ReactNode;
    menu: {
      items: Array<{
        key: string;
        label: string;
        onClick?: () => void;
      }>;
    };
  }) => (
    <div>
      {children}
      {menu.items.map((item) => (
        <button
          key={item.key}
          type="button"
          data-testid={`menu-item-${item.key}`}
          onClick={item.onClick}
        >
          {item.label + "MenuItem"}
        </button>
      ))}
    </div>
  ),
}));

jest.mock(
  "../../../../src/components/table/useColumnSettingsButton.tsx",
  () => ({
    useColumnSettingsBasedOnColumnsType: (
      _key: string,
      columns: unknown[],
    ) => ({
      orderedColumns: columns,
      columnSettingsButton: (
        <button data-testid="column-settings">Settings</button>
      ),
    }),
  }),
);

jest.mock("../../../../src/hooks/useContextServiceFilter.ts", () => ({
  useContextServiceFilters: () => ({
    filters: [],
    filterButton: <button data-testid="filter-button">Filter</button>,
  }),
}));

jest.mock("../../../../src/components/labels/EntityLabels.tsx", () => ({
  EntityLabels: ({
    labels,
  }: {
    labels?: { name: string; technical: boolean }[];
  }) => (
    <span data-testid="entity-labels">
      {labels?.map((l) => l.name).join(", ")}
    </span>
  ),
}));

jest.mock("../../../../src/components/table/LabelsEdit.tsx", () => ({
  LabelsEdit: ({
    values,
    onSubmit,
  }: {
    values: { labels: string[] };
    onSubmit: (v: unknown) => void;
  }) => (
    <button
      data-testid="labels-edit"
      onClick={() => onSubmit({ labels: values.labels })}
    >
      Edit Labels
    </button>
  ),
}));

jest.mock("../../../../src/components/services/ui/ChainColumn.tsx", () => ({
  ChainColumn: ({ chains }: { chains: { id: string; name: string }[] }) => (
    <span data-testid="chain-column">
      {chains.length === 0 ? "No chains" : `${chains.length} chain(s)`}
    </span>
  ),
}));

jest.mock("../../../../src/components/InlineEdit.tsx", () => ({
  InlineEdit: ({ viewer }: { viewer: React.ReactNode }) => <>{viewer}</>,
}));

jest.mock("../../../../src/Modals", () => ({
  useModalsContext: () => ({ showModal: mockShowModal }),
}));

jest.mock(
  "../../../../src/components/services/GenericServiceListPage.tsx",
  () => ({
    GenericServiceListPage: ({
      title,
      icon,
      extraActions,
      children,
      onSearch,
      onExport,
      onImport,
      onCreate,
    }: {
      title: string;
      icon: React.ReactNode;
      extraActions: React.ReactNode[];
      children: React.ReactNode;
      onSearch: (v: string) => void;
      onExport: () => void;
      onImport: () => void;
      onCreate: (name: string, description: string) => Promise<void>;
    }) => (
      <div data-testid="generic-service-list-page">
        <h1 data-testid="page-title">{title}</h1>
        <div data-testid="extra-actions">
          {extraActions.map((a, i) => (
            <span key={i}>{a}</span>
          ))}
        </div>
        <input
          data-testid="search-input"
          onChange={(e) => onSearch(e.target.value)}
          placeholder="Search..."
        />
        <button data-testid="export-button" onClick={onExport}>
          Export
        </button>
        <button data-testid="import-button" onClick={onImport}>
          Import
        </button>
        <button
          data-testid="create-button"
          onClick={() => void onCreate("New", "Desc")}
        >
          Create
        </button>
        {children}
      </div>
    ),
  }),
);

// Helper to create mock ContextSystem
function makeContextSystem(
  overrides: Partial<ContextSystem> = {},
): ContextSystem {
  return {
    id: "test-id-1",
    name: "Test Service",
    description: "Test description",
    labels: [],
    chains: [],
    createdWhen: "2026-01-01T00:00:00Z",
    createdBy: { id: "user-1", username: "testuser" },
    modifiedWhen: "2026-01-02T00:00:00Z",
    modifiedBy: { id: "user-1", username: "testuser" },
    ...overrides,
  } as ContextSystem;
}

async function renderAndWaitForLoad(services: ContextSystem[] = []) {
  mockGetContextServices.mockResolvedValue(services);
  const result = render(<ContextServiceList />);
  await waitFor(() => {
    expect(mockGetContextServices).toHaveBeenCalled();
  });
  return result;
}

describe("ContextServiceList", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockGetContextServices.mockResolvedValue([]);
    mockSearchContextServices.mockResolvedValue([]);
    mockFilterContextServices.mockResolvedValue([]);
  });

  it("should render page title when loaded", async () => {
    await renderAndWaitForLoad([makeContextSystem()]);
    expect(screen.getByText("Context Services"));
  });

  it("should render extra actions (filter, column settings) when loaded", async () => {
    await renderAndWaitForLoad([makeContextSystem()]);
    expect(screen.getByTestId("filter-button")).toBeInTheDocument();
    expect(screen.getByTestId("column-settings")).toBeInTheDocument();
  });

  it("should display services in table when loaded", async () => {
    const services = [
      makeContextSystem({ id: "svc-1", name: "Service One" }),
      makeContextSystem({ id: "svc-2", name: "Service Two" }),
    ];
    await renderAndWaitForLoad(services);
    await waitFor(() => {
      expect(screen.getByText("Service One")).toBeInTheDocument();
      expect(screen.getByText("Service Two")).toBeInTheDocument();
    });
  });

  it("should navigate to parameters page when clicking service name", async () => {
    const service = makeContextSystem({ id: "svc-123", name: "Click Me" });
    await renderAndWaitForLoad([service]);
    await waitFor(() => {
      expect(screen.getByText("Click Me")).toBeInTheDocument();
    });
    fireEvent.click(screen.getByText("Click Me"));
    expect(mockNavigate).toHaveBeenCalledWith(
      "/services/context/svc-123/parameters",
    );
  });

  it("should render labels using EntityLabels component", async () => {
    const service = makeContextSystem({
      id: "svc-1",
      labels: [
        { name: "env:prod", technical: false },
        { name: "_internal", technical: true },
      ],
    });
    await renderAndWaitForLoad([service]);
    await waitFor(() => {
      // EntityLabels should receive filtered non-technical labels
      expect(screen.getByTestId("entity-labels")).toHaveTextContent("env:prod");
    });
  });

  it("should render usedBy count using ChainColumn component", async () => {
    const service = makeContextSystem({ id: "svc-1", chains: [{} as Chain] });
    await renderAndWaitForLoad([service]);
    await waitFor(() => {
      expect(screen.getByTestId("chain-column")).toHaveTextContent("1");
    });
  });

  /* it("should call update API when LabelsEdit submits changes", async () => {
    const service = makeContextSystem({ id: "svc-1", labels: [] });
    const updatedService = { ...service, labels: [{ name: "new-label", technical: false }] };
    mockUpdateContextService.mockResolvedValue(updatedService);
    await renderAndWaitForLoad([service]);
    await waitFor(() => {
      expect(screen.getByTestId("labels-edit")).toBeInTheDocument();
    });
    fireEvent.click(screen.getByTestId("labels-edit"));
    await waitFor(() => {
      expect(mockUpdateContextService).toHaveBeenCalledWith("svc-1", {
        labels: [{ name: "new-label", technical: false }],
      });
    });
  }); */

  it("should navigate to edit page when clicking Edit action", async () => {
    const service = makeContextSystem({ id: "edit-me" });
    await renderAndWaitForLoad([service]);
    await waitFor(() => {
      expect(screen.getByTestId("icon-more")).toBeInTheDocument();
    });
    fireEvent.click(screen.getByTestId("icon-more"));
    await waitFor(() => {
      fireEvent.click(screen.getByTestId("menu-item-edit"));
    });

    await waitFor(() => {
      expect(mockNavigate).toHaveBeenCalledWith(
        "/services/context/edit-me/parameters",
      );
    });
  });

  it("should call delete API and show success when confirming delete", async () => {
    const service = makeContextSystem({ id: "to-delete" });
    mockDeleteContextService.mockResolvedValue();
    await renderAndWaitForLoad([service]);
    await waitFor(() => {
      const rowOptions = screen.getByTestId("icon-more");
      expect(rowOptions).toBeInTheDocument();
      fireEvent.click(rowOptions);
    });

    await waitFor(() => {
      fireEvent.click(screen.getByTestId("menu-item-delete"));
    });

    await waitFor(() => {
      fireEvent.click(screen.queryByText("Delete")!);
    });

    await waitFor(() => {
      expect(mockDeleteContextService).toHaveBeenCalledWith("to-delete");
    });
  });

  it("should show error notification when delete fails", async () => {
    const service = makeContextSystem({ id: "fail-delete" });
    const error = new Error("Delete failed");
    mockDeleteContextService.mockRejectedValue(error);
    await renderAndWaitForLoad([service]);
    await waitFor(() => {
      fireEvent.click(screen.getByTestId("icon-more"));
    });


    await waitFor(() => {
      fireEvent.click(screen.getByTestId("menu-item-delete"));
    });

    await waitFor(() => {
      fireEvent.click(screen.queryByText("Delete")!);
    });

    await waitFor(() => {
      expect(mockRequestFailed).toHaveBeenCalledWith(
        "Failed to delete service",
        error,
      );
    });
  });

  it("should export selected services when clicking Export action", async () => {
    const services = [makeContextSystem({ id: "exp-1", name: "Export Me" })];
    mockExportContextServices.mockResolvedValue(
      new Blob(["test"], { type: "application/json" }),
    );
    await renderAndWaitForLoad(services);
    // Select all rows via the header checkbox
    const [headerCheckbox] = screen.getAllByRole("checkbox");
    fireEvent.click(headerCheckbox);

    await waitFor(() => {
      fireEvent.click(screen.getByTestId("export-button"));
    });

    await waitFor(() => {
      expect(mockExportContextServices).toHaveBeenCalledWith(["exp-1"]);
    });
  });

  it("should show 'No services selected' message when the toolbar export button is clicked with no rows selected", async () => {
    const messageInfoSpy = jest
      .spyOn(message, "info")
      .mockImplementation(() => {});
    await renderAndWaitForLoad([]);

    await waitFor(() => {
      fireEvent.click(screen.getByTestId("export-button"));
    });

    expect(messageInfoSpy).toHaveBeenCalledWith("No services selected");
    messageInfoSpy.mockRestore();
  });

  it("should filter services when search string is provided", async () => {
    const mockResults = [makeContextSystem({ name: "Matching Service" })];
    mockSearchContextServices.mockResolvedValue(mockResults);
    await renderAndWaitForLoad();
    const searchInput = screen.getByTestId("search-input");
    fireEvent.change(searchInput, { target: { value: "match" } });
    await waitFor(() => {
      expect(mockSearchContextServices).toHaveBeenCalledWith("match");
    });
  });

  it("should create new service and navigate on success", async () => {
    const newService = makeContextSystem({
      id: "new-svc",
      name: "New Service",
    });
    mockCreateContextService.mockResolvedValue(newService);
    await renderAndWaitForLoad();
    await waitFor(() => {
      expect(screen.getByTestId("create-button")).toBeInTheDocument();
    });
    fireEvent.click(screen.getByTestId("create-button"));
    await waitFor(() => {
      expect(mockCreateContextService).toHaveBeenCalledWith({
        name: "New",
        description: "Desc",
      });
      expect(mockNavigate).toHaveBeenCalledWith(
        "/services/context/new-svc/parameters",
      );
    });
  });

  it("should handle API error when loading services", async () => {
    const error = new Error("Network error");
    mockGetContextServices.mockRejectedValue(error);
    render(<ContextServiceList />);
    await waitFor(() => {
      expect(mockRequestFailed).toHaveBeenCalledWith(
        expect.stringContaining("Failed to load context services"),
        error,
      );
    });
  });

  it("should handle non-array response from getContextServices gracefully", async () => {
    mockGetContextServices.mockResolvedValue(
      undefined as unknown as ContextSystem[],
    );
    await renderAndWaitForLoad();
    await waitFor(() => {
      // Should not crash; table renders with empty data
      expect(screen.getByRole("table")).toBeInTheDocument();
    });
  });
});
