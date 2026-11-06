/**
 * @jest-environment jsdom
 */

import React from "react";
import {
  act,
  render,
  screen,
  waitFor,
  fireEvent,
  within,
} from "@testing-library/react";
import "@testing-library/jest-dom";
import {
  RestApiError,
  CatalogItemType,
  FolderItem,
  ChainItem,
  ChainCreationRequest,
  DomainType,
  BulkDeploymentSnapshotAction,
  DeployMode,
} from "../../src/api/apiTypes";

type ConfirmOptions = {
  title: string;
  content: string;
  onOk: () => void | Promise<void>;
};

const mockNavigate = jest.fn();
let mockSearchParams = new URLSearchParams();
let mockCapturedEnsureChainLoaded:
  | ((record: FolderItem | ChainItem) => Promise<React.Key[]>)
  | undefined;

jest.mock("../../src/components/table/useTableRowExpandCollapse", () => {
  const actual = jest.requireActual(
    "../../src/components/table/useTableRowExpandCollapse",
  );
  return {
    ...actual,
    useTableRowExpandCollapse: (opts: {
      getSubtreeIds: (record: unknown) => React.Key[];
      ensureLoaded?: (record: unknown) => Promise<React.Key[]>;
    }) => {
      mockCapturedEnsureChainLoaded = opts.ensureLoaded;
      return actual.useTableRowExpandCollapse(opts);
    },
  };
});

const mockFolder: FolderItem = {
  id: "folder-1",
  name: "Test Folder",
  description: "A test folder",
  itemType: CatalogItemType.FOLDER,
  createdWhen: 1700000000000,
  modifiedWhen: 1700000000000,
};

const mockChain: ChainItem = {
  id: "chain-1",
  name: "Test Chain",
  description: "A test chain",
  itemType: CatalogItemType.CHAIN,
  labels: [],
  createdWhen: 1700000000000,
  modifiedWhen: 1700000000000,
};

const mockFolder2: FolderItem = {
  id: "folder-2",
  name: "Another Folder",
  description: "",
  itemType: CatalogItemType.FOLDER,
};

const mockChain2: ChainItem = {
  id: "chain-2",
  name: "Another Chain",
  description: "",
  itemType: CatalogItemType.CHAIN,
  labels: [{ name: "env", technical: false }],
};

const mockNestedChain: ChainItem = {
  id: "chain-nested",
  name: "Nested Chain",
  description: "",
  itemType: CatalogItemType.CHAIN,
  labels: [],
  parentId: "folder-1",
};

// --------------- Mocks ---------------

jest.mock("antd", () =>
  require("tests/helpers/antdMockWithLightweightTable").antdMockWithLightweightTable(),
);

jest.mock("react-router", () => ({
  useNavigate: () => mockNavigate,
  useSearchParams: () => [mockSearchParams],
}));

const mockShowModal = jest.fn();
jest.mock("../../src/Modals", () => ({
  useModalsContext: () => ({
    showModal: mockShowModal,
    closeModal: jest.fn(),
  }),
}));

const mockShowGenerateDdsModal = jest.fn();
jest.mock("../../src/hooks/useGenerateDds.tsx", () => ({
  useGenerateDds: () => ({
    showGenerateDdsModal: mockShowGenerateDdsModal,
  }),
}));

const mockConfirmAndRun = jest.fn<void, [ConfirmOptions]>();
jest.mock("../../src/misc/confirm-utils", () => ({
  confirmAndRun: (options: ConfirmOptions) => mockConfirmAndRun(options),
}));

const mockMessageInfo = jest.fn<void, [string]>();
const mockModalError = jest.fn<void, [unknown]>();
jest.mock("../../src/misc/antd-app", () => ({
  message: { info: (...args: [string]) => mockMessageInfo(...args) },
  modal: { error: (...args: [unknown]) => mockModalError(...args) },
}));

const mockRequestFailed = jest.fn();
const mockNotificationService = {
  requestFailed: mockRequestFailed,
  errorWithDetails: jest.fn(),
  info: jest.fn(),
  warning: jest.fn(),
};
jest.mock("../../src/hooks/useNotificationService", () => ({
  useNotificationService: () => mockNotificationService,
}));

const mockChainFilters: import("../../src/components/table/filter/filterTypes").EntityFilterModel[] =
  [];
jest.mock("../../src/hooks/useChainFilter", () => ({
  useChainFilters: () => ({
    filters: mockChainFilters,
    filterButton: <button data-testid="filter-btn">Filter</button>,
  }),
}));

jest.mock("../../src/components/table/useColumnSettingsButton", () => ({
  useColumnSettingsBasedOnColumnsType: (_key: string, columns: unknown[]) => ({
    orderedColumns: columns,
    columnSettingsButton: (
      <button data-testid="column-settings-btn">Columns</button>
    ),
  }),
}));

jest.mock("../../src/hooks/useTableDragDrop", () => ({
  useTableDragDrop: () => ({
    draggedItemId: null,
    dropTargetId: null,
    isDragging: false,
    dropBreadcrumbId: null,
    getBreadcrumbDropProps: () => ({}),
    onRow: () => ({}),
  }),
}));

jest.mock("../../src/icons/IconProvider", () => ({
  OverridableIcon: ({ name }: { name: string }) => (
    <span data-testid={`icon-${name}`}>{name}</span>
  ),
}));

jest.mock(
  "../../src/components/deployment_runtime_states/DeploymentsCumulativeState",
  () => ({
    DeploymentsCumulativeState: () => <span data-testid="deployment-state" />,
  }),
);

jest.mock("../../src/components/labels/EntityLabels", () => ({
  EntityLabels: ({ labels }: { labels: Array<{ name: string }> }) => (
    <span data-testid="entity-labels">{labels?.length ?? 0} labels</span>
  ),
}));

jest.mock("../../src/components/chains/ChainDetailsDrawer", () => ({
  ChainDetailsDrawer: ({
    chain,
    open,
    onClose,
  }: {
    chain: { id: string; name: string } | null;
    open: boolean;
    onClose: () => void;
  }) =>
    open ? (
      <div data-testid="chain-details-drawer">
        <span data-testid="drawer-chain-id">{chain?.id}</span>
        <span data-testid="drawer-chain-name">{chain?.name}</span>
        <button data-testid="drawer-close" onClick={onClose}>
          close
        </button>
      </div>
    ) : null,
}));

jest.mock("../../src/components/table/CompactSearch", () => ({
  CompactSearch: (props: {
    value: string;
    onChange: (v: string) => void;
    placeholder: string;
  }) => (
    <input
      data-testid="search-input"
      value={props.value}
      onChange={(e) => props.onChange(e.target.value)}
      placeholder={props.placeholder}
    />
  ),
}));

jest.mock("../../src/permissions/ProtectedButton", () => ({
  ProtectedButton: ({
    buttonProps,
    tooltipProps,
  }: {
    buttonProps: {
      onClick?: () => void;
      iconName?: string;
      disabled?: boolean;
    };
    tooltipProps: { title: string };
  }) => (
    <button
      data-testid={`protected-btn-${tooltipProps.title.replace(/\s+/g, "-").toLowerCase()}`}
      onClick={buttonProps?.onClick}
      disabled={buttonProps?.disabled}
    >
      {tooltipProps.title}
    </button>
  ),
}));

jest.mock("../../src/permissions/ProtectedDropdown", () => ({
  ProtectedDropdown: ({
    children,
    menu,
  }: {
    children: React.ReactNode;
    menu: {
      items: Array<{ key: string; label: string; onClick?: () => void }>;
      onClick?: (info: { key: string; domEvent: React.MouseEvent }) => void;
    };
  }) => (
    <div data-testid="protected-dropdown">
      {children}
      <div data-testid="dropdown-menu" style={{ display: "none" }}>
        {menu?.items
          ?.filter((item) => item.label)
          .map((item) => (
            <button
              key={item.key}
              data-testid={`menu-item-${item.key}`}
              onClick={(e) =>
                item.onClick
                  ? item.onClick()
                  : menu.onClick?.({ key: item.key, domEvent: e })
              }
            >
              {item.label}
            </button>
          ))}
      </div>
    </div>
  ),
  ProtectedMenuItem: {},
}));

jest.mock("../../src/misc/format-utils", () => ({
  formatTimestamp: (ts: number) => new Date(ts).toISOString(),
}));

const mockCopyToClipboard = jest.fn<Promise<void>, [string]>();
jest.mock("../../src/misc/clipboard-util", () => ({
  copyToClipboard: (...args: [string]) => mockCopyToClipboard(...args),
}));

jest.mock("../../src/misc/selection-utils", () => ({
  toStringIds: (keys: React.Key[]) => keys.map(String),
}));

jest.mock("../../src/misc/tree-utils", () => ({
  traverseElementsDepthFirst: jest.fn(),
}));

const mockDownloadFile = jest.fn<void, [File]>();
const mockMergeZipArchives = jest.fn<Promise<ArrayBuffer>, [Blob[]]>();
jest.mock("../../src/misc/download-utils", () => ({
  downloadFile: (...args: [File]) => mockDownloadFile(...args),
  mergeZipArchives: (...args: [Blob[]]) => mockMergeZipArchives(...args),
}));

jest.mock("../../src/misc/export-additions", () => ({
  exportAdditionsForChains: jest.fn().mockResolvedValue([]),
}));

// Mock modals as simple stubs
jest.mock("../../src/components/modal/FolderEdit", () => ({
  FolderEdit: () => <div data-testid="folder-edit-modal" />,
}));
jest.mock("../../src/components/modal/ChainCreate", () => ({
  ChainCreate: () => <div data-testid="chain-create-modal" />,
}));
jest.mock("../../src/components/modal/ExportChains", () => ({
  ExportChains: () => <div data-testid="export-chains-modal" />,
}));
jest.mock("../../src/components/modal/ImportChains", () => ({
  ImportChains: () => <div data-testid="import-chains-modal" />,
}));
jest.mock("../../src/components/modal/DeployChains", () => ({
  DeployChains: () => <div data-testid="deploy-chains-modal" />,
}));

// Mock api
const mockApi = {
  listFolder: jest.fn().mockResolvedValue([]),
  getPathToFolder: jest.fn().mockResolvedValue([]),
  createFolder: jest.fn(),
  updateFolder: jest.fn(),
  deleteFolder: jest.fn(),
  deleteFolders: jest.fn(),
  createChain: jest.fn(),
  updateChain: jest.fn(),
  getChain: jest.fn(),
  deleteChain: jest.fn(),
  deleteChains: jest.fn(),
  bulkDeploy: jest.fn(),
  duplicateChain: jest.fn(),
  copyChain: jest.fn(),
  moveChain: jest.fn(),
  moveFolder: jest.fn(),
  exportChains: jest.fn(),
  exportAllChains: jest.fn(),
  getNestedChains: jest.fn().mockResolvedValue([]),
};

jest.mock("../../src/api/api", () => ({
  api: mockApi,
}));

// Import after all mocks
import Chains from "../../src/pages/Chains";

// --------------- Tests ---------------

describe("Chains page", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockSearchParams = new URLSearchParams();
    mockCapturedEnsureChainLoaded = undefined;
    mockApi.listFolder.mockResolvedValue([]);
    mockApi.getPathToFolder.mockResolvedValue([]);
    mockCopyToClipboard.mockResolvedValue(undefined);
    mockMergeZipArchives.mockResolvedValue(new ArrayBuffer(0));
  });

  it("refreshes after a catalog write while preserving the current folder", async () => {
    mockSearchParams = new URLSearchParams("folder=folder-1");
    mockApi.listFolder.mockResolvedValue([mockChain]);
    render(<Chains />);
    await screen.findByText("Test Chain");
    mockApi.listFolder.mockResolvedValue([
      { ...mockChain, name: "Renamed by Rocky" },
    ]);
    await act(async () =>
      window.dispatchEvent(
        new CustomEvent("qip-catalog-changed", {
          detail: { chainIds: [mockChain.id], chainsListChanged: true },
        }),
      ),
    );
    await screen.findByText("Renamed by Rocky");
    expect(mockApi.listFolder).toHaveBeenLastCalledWith({
      folderId: "folder-1",
      filters: [],
      searchString: "",
    });
    expect(mockNavigate).not.toHaveBeenCalled();
  });

  it("keeps the previous rows after a background catalog read fails", async () => {
    mockApi.listFolder.mockResolvedValue([mockChain]);
    render(<Chains />);
    await screen.findByText("Test Chain");
    mockApi.listFolder.mockRejectedValueOnce(new Error("offline"));
    await act(async () =>
      window.dispatchEvent(
        new CustomEvent("qip-catalog-changed", {
          detail: { chainIds: null, chainsListChanged: true },
        }),
      ),
    );
    await waitFor(() => expect(mockRequestFailed).toHaveBeenCalled());
    expect(screen.getByText("Test Chain")).toBeInTheDocument();
  });

  it("leaves a deleted folder and preserves the other query parameters", async () => {
    mockSearchParams = new URLSearchParams("folder=gone&view=compact");
    mockApi.listFolder.mockRejectedValueOnce(
      new RestApiError("missing folder", 404),
    );
    render(<Chains />);
    await waitFor(() =>
      expect(mockNavigate).toHaveBeenCalledWith("/chains?view=compact"),
    );
  });

  // --- Rendering & data loading ---

  it("renders without crashing", async () => {
    render(<Chains />);
    await waitFor(() => {
      expect(mockApi.listFolder).toHaveBeenCalled();
    });
  });

  it("calls listFolder on mount with no folderId", async () => {
    render(<Chains />);
    await waitFor(() => {
      expect(mockApi.listFolder).toHaveBeenCalledWith({
        folderId: undefined,
        filters: [],
        searchString: "",
      });
    });
  });

  it("calls listFolder with folderId from search params", async () => {
    mockSearchParams = new URLSearchParams("folder=folder-1");
    render(<Chains />);
    await waitFor(() => {
      expect(mockApi.listFolder).toHaveBeenCalledWith(
        expect.objectContaining({ folderId: "folder-1" }),
      );
      expect(mockApi.getPathToFolder).toHaveBeenCalledWith("folder-1");
    });
  });

  it("displays folder and chain items in the table", async () => {
    mockApi.listFolder.mockResolvedValue([mockFolder, mockChain]);
    render(<Chains />);
    await waitFor(() => {
      expect(screen.getByText("Test Folder")).toBeInTheDocument();
      expect(screen.getByText("Test Chain")).toBeInTheDocument();
    });
  });

  it("displays folder icon for folders and chain icon for chains", async () => {
    mockApi.listFolder.mockResolvedValue([mockFolder, mockChain]);
    render(<Chains />);
    await waitFor(() => {
      expect(screen.getByTestId("icon-folder")).toBeInTheDocument();
      expect(screen.getByTestId("icon-chain")).toBeInTheDocument();
    });
  });

  it("shows deployment state for chains but not folders", async () => {
    mockApi.listFolder.mockResolvedValue([mockFolder, mockChain]);
    render(<Chains />);
    await waitFor(() => {
      const states = screen.getAllByTestId("deployment-state");
      // Only chains get deployment state
      expect(states).toHaveLength(1);
    });
  });

  // --- Table toolbar (TableToolbar + CompactSearch + actions) ---

  it("renders full text search in toolbar", async () => {
    render(<Chains />);
    await waitFor(() => expect(mockApi.listFolder).toHaveBeenCalled());
    expect(
      screen.getByPlaceholderText("Search chains and folders..."),
    ).toBeInTheDocument();
  });

  it("renders toolbar ProtectedButtons by tooltip title", async () => {
    render(<Chains />);
    await waitFor(() => expect(mockApi.listFolder).toHaveBeenCalled());
    expect(
      screen.getByTestId("protected-btn-import-chains"),
    ).toBeInTheDocument();
    expect(screen.getByTestId("protected-btn-paste")).toBeInTheDocument();
    expect(
      screen.getByTestId("protected-btn-deploy-selected-chains"),
    ).toBeInTheDocument();
    expect(
      screen.getByTestId("protected-btn-export-selected-chains"),
    ).toBeInTheDocument();
    expect(
      screen.getByTestId("protected-btn-delete-selected-chains-and-folders"),
    ).toBeInTheDocument();
    expect(
      screen.getByTestId("protected-btn-compare-selected-chains"),
    ).toBeInTheDocument();
  });

  it("calls showModal when Import chains is clicked", async () => {
    render(<Chains />);
    await waitFor(() => expect(mockApi.listFolder).toHaveBeenCalled());
    fireEvent.click(screen.getByTestId("protected-btn-import-chains"));
    expect(mockShowModal).toHaveBeenCalledTimes(1);
    expect(mockShowModal.mock.calls[0][0]).toEqual(
      expect.objectContaining({
        component: expect.anything(),
      }),
    );
  });

  // --- Breadcrumb ---

  it("renders home breadcrumb icon", async () => {
    render(<Chains />);
    await waitFor(() => {
      expect(screen.getByTestId("icon-home")).toBeInTheDocument();
    });
  });

  it("renders folder path in breadcrumb", async () => {
    mockSearchParams = new URLSearchParams("folder=folder-2");
    const pathFolders: FolderItem[] = [
      {
        id: "folder-1",
        name: "Parent Folder",
        description: "",
        itemType: CatalogItemType.FOLDER,
      },
      {
        id: "folder-2",
        name: "Current Folder",
        description: "",
        itemType: CatalogItemType.FOLDER,
      },
    ];
    mockApi.getPathToFolder.mockResolvedValue(pathFolders);

    render(<Chains />);
    await waitFor(() => {
      expect(screen.getByText("Parent Folder")).toBeInTheDocument();
      expect(screen.getByText("Current Folder")).toBeInTheDocument();
    });
  });

  it("navigates to /chains on home breadcrumb click", async () => {
    render(<Chains />);
    await waitFor(() => {
      expect(screen.getByTestId("icon-home")).toBeInTheDocument();
    });

    const homeSpan = screen.getByTestId("icon-home").closest("span")!;
    fireEvent.click(homeSpan);
    expect(mockNavigate).toHaveBeenCalledWith("/chains");
  });

  it("navigates to parent folder on breadcrumb click", async () => {
    mockSearchParams = new URLSearchParams("folder=folder-2");
    const pathFolders: FolderItem[] = [
      {
        id: "folder-1",
        name: "Parent Folder",
        description: "",
        itemType: CatalogItemType.FOLDER,
      },
      {
        id: "folder-2",
        name: "Current Folder",
        description: "",
        itemType: CatalogItemType.FOLDER,
      },
    ];
    mockApi.getPathToFolder.mockResolvedValue(pathFolders);

    render(<Chains />);
    await waitFor(() => {
      expect(screen.getByText("Parent Folder")).toBeInTheDocument();
    });

    fireEvent.click(screen.getByText("Parent Folder"));
    expect(mockNavigate).toHaveBeenCalledWith("/chains?folder=folder-1");
  });

  it("last breadcrumb item is not clickable", async () => {
    mockSearchParams = new URLSearchParams("folder=folder-2");
    const pathFolders: FolderItem[] = [
      {
        id: "folder-2",
        name: "Current Folder",
        description: "",
        itemType: CatalogItemType.FOLDER,
      },
    ];
    mockApi.getPathToFolder.mockResolvedValue(pathFolders);

    render(<Chains />);
    await waitFor(() => {
      expect(screen.getByText("Current Folder")).toBeInTheDocument();
    });

    const currentSpan = screen.getByText("Current Folder");
    fireEvent.click(currentSpan);
    // Should not navigate — it's the current folder
    expect(mockNavigate).not.toHaveBeenCalledWith(
      expect.stringContaining("folder-2"),
    );
  });

  // --- Item navigation ---

  it("navigates to chain on chain name click", async () => {
    mockApi.listFolder.mockResolvedValue([mockChain]);
    render(<Chains />);
    await waitFor(() => {
      expect(screen.getByText("Test Chain")).toBeInTheDocument();
    });

    fireEvent.click(screen.getByText("Test Chain"));
    expect(mockNavigate).toHaveBeenCalledWith("/chains/chain-1");
  });

  it("navigates to folder on folder name click", async () => {
    mockApi.listFolder.mockResolvedValue([mockFolder]);
    render(<Chains />);
    await waitFor(() => {
      expect(screen.getByText("Test Folder")).toBeInTheDocument();
    });

    fireEvent.click(screen.getByText("Test Folder"));
    expect(mockNavigate).toHaveBeenCalledWith("/chains?folder=folder-1");
  });

  // --- Error handling ---

  it("shows notification on listFolder failure", async () => {
    mockApi.listFolder.mockRejectedValue(new Error("Network error"));
    render(<Chains />);
    await waitFor(() => {
      expect(mockRequestFailed).toHaveBeenCalledWith(
        "Failed to refresh chains; the previous list is preserved",
        expect.any(Error),
        expect.any(Function),
      );
    });
  });

  it("shows notification on getPathToFolder failure", async () => {
    mockSearchParams = new URLSearchParams("folder=folder-1");
    mockApi.getPathToFolder.mockRejectedValue(new Error("Not found"));
    render(<Chains />);
    await waitFor(() => {
      expect(mockRequestFailed).toHaveBeenCalledWith(
        "Failed to refresh chains; the previous list is preserved",
        expect.any(Error),
        expect.any(Function),
      );
    });
  });

  // --- Create operations ---

  it("opens create folder modal via Create dropdown", async () => {
    render(<Chains />);
    await waitFor(() => {
      expect(mockApi.listFolder).toHaveBeenCalled();
    });

    const newFolderBtn = screen.getByTestId("menu-item-folder");
    fireEvent.click(newFolderBtn);

    expect(mockShowModal).toHaveBeenCalledWith(
      expect.objectContaining({
        component: expect.anything(),
      }),
    );
  });

  it("opens create chain modal via Create dropdown", async () => {
    render(<Chains />);
    await waitFor(() => {
      expect(mockApi.listFolder).toHaveBeenCalled();
    });

    const newChainBtn = screen.getByTestId("menu-item-chain");
    fireEvent.click(newChainBtn);

    expect(mockShowModal).toHaveBeenCalledWith(
      expect.objectContaining({
        component: expect.anything(),
      }),
    );
  });

  // --- Move operations (via moveChain/moveFolder used by drag-drop) ---

  it("renders items with parentId as nested under parent folder", async () => {
    // When nested chain has parentId matching a folder, buildTableItems nests it
    mockApi.listFolder.mockResolvedValue([mockFolder, mockNestedChain]);
    render(<Chains />);
    await waitFor(() => {
      // The folder should be in the table
      expect(screen.getByText("Test Folder")).toBeInTheDocument();
      // The nested chain is inside the folder (not visible unless expanded)
    });
  });

  // --- Empty state ---

  it("renders empty table when no items", async () => {
    mockApi.listFolder.mockResolvedValue([]);
    render(<Chains />);
    await waitFor(() => {
      expect(mockApi.listFolder).toHaveBeenCalled();
    });
    // antd Table renders header columns — use getAllByText since antd duplicates them
    expect(screen.getAllByText("Name").length).toBeGreaterThan(0);
    expect(screen.getAllByText("Description").length).toBeGreaterThan(0);
  });

  // --- Toolbar buttons ---

  it("renders all toolbar buttons", async () => {
    render(<Chains />);
    await waitFor(() => {
      expect(mockApi.listFolder).toHaveBeenCalled();
    });

    expect(screen.getByTestId("protected-btn-paste")).toBeInTheDocument();
    expect(
      screen.getByTestId("protected-btn-deploy-selected-chains"),
    ).toBeInTheDocument();
    expect(
      screen.getByTestId("protected-btn-export-selected-chains"),
    ).toBeInTheDocument();
    expect(
      screen.getByTestId("protected-btn-import-chains"),
    ).toBeInTheDocument();
    expect(
      screen.getByTestId("protected-btn-delete-selected-chains-and-folders"),
    ).toBeInTheDocument();
  });

  it("renders search input", async () => {
    render(<Chains />);
    await waitFor(() => {
      expect(mockApi.listFolder).toHaveBeenCalled();
    });
    expect(screen.getByTestId("search-input")).toBeInTheDocument();
  });

  // --- Table sorting ---

  it("sorts items: folders first, then chains, alphabetically", async () => {
    mockApi.listFolder.mockResolvedValue([
      mockChain2,
      mockFolder2,
      mockChain,
      mockFolder,
    ]);
    render(<Chains />);
    await waitFor(() => {
      expect(screen.getByText("Test Folder")).toBeInTheDocument();
    });

    // Item names are rendered as <a> tags inside the Name column
    const nameLinks = screen
      .getAllByRole("cell")
      .map((cell) => cell.querySelector("a"))
      .filter(Boolean)
      .map((a) => a!.textContent);

    // Folders first (alphabetical), then chains (alphabetical)
    expect(nameLinks).toEqual([
      "Another Folder",
      "Test Folder",
      "Another Chain",
      "Test Chain",
    ]);
  });

  // --- Labels ---

  it("shows labels for chain items", async () => {
    mockApi.listFolder.mockResolvedValue([mockChain2]);
    render(<Chains />);
    await waitFor(() => {
      expect(screen.getByTestId("entity-labels")).toBeInTheDocument();
      expect(screen.getByText("1 labels")).toBeInTheDocument();
    });
  });

  // --- Chain details drawer ---

  it("opens chain details drawer when clicking on an empty area of a chain row", async () => {
    mockApi.listFolder.mockResolvedValue([mockChain]);
    render(<Chains />);
    await waitFor(() => {
      expect(screen.getByText("Test Chain")).toBeInTheDocument();
    });

    expect(
      screen.queryByTestId("chain-details-drawer"),
    ).not.toBeInTheDocument();

    // Click on the Description cell (plain text, no link/button).
    fireEvent.click(screen.getByText("A test chain"));

    expect(screen.getByTestId("chain-details-drawer")).toBeInTheDocument();
    expect(screen.getByTestId("drawer-chain-id")).toHaveTextContent("chain-1");
    expect(screen.getByTestId("drawer-chain-name")).toHaveTextContent(
      "Test Chain",
    );
    // Clicking on the chain name link navigates — drawer click must not also fire.
    expect(mockNavigate).not.toHaveBeenCalledWith("/chains/chain-1");
  });

  it("does not open drawer when clicking on the chain name link", async () => {
    mockApi.listFolder.mockResolvedValue([mockChain]);
    render(<Chains />);
    await waitFor(() => {
      expect(screen.getByText("Test Chain")).toBeInTheDocument();
    });

    fireEvent.click(screen.getByText("Test Chain"));

    expect(
      screen.queryByTestId("chain-details-drawer"),
    ).not.toBeInTheDocument();
    expect(mockNavigate).toHaveBeenCalledWith("/chains/chain-1");
  });

  it("does not open drawer when clicking on a folder row", async () => {
    mockApi.listFolder.mockResolvedValue([mockFolder]);
    render(<Chains />);
    await waitFor(() => {
      expect(screen.getByText("Test Folder")).toBeInTheDocument();
    });

    // Click on the Description cell of the folder row.
    fireEvent.click(screen.getByText("A test folder"));

    expect(
      screen.queryByTestId("chain-details-drawer"),
    ).not.toBeInTheDocument();
  });

  it("closes the drawer when onClose is fired", async () => {
    mockApi.listFolder.mockResolvedValue([mockChain]);
    render(<Chains />);
    await waitFor(() => {
      expect(screen.getByText("Test Chain")).toBeInTheDocument();
    });

    fireEvent.click(screen.getByText("A test chain"));
    expect(screen.getByTestId("chain-details-drawer")).toBeInTheDocument();

    fireEvent.click(screen.getByTestId("drawer-close"));
    expect(
      screen.queryByTestId("chain-details-drawer"),
    ).not.toBeInTheDocument();
  });

  // --- Folder create/update via the FolderEdit modal onSubmit ---

  it("creates a folder via the folder edit modal onSubmit", async () => {
    mockApi.createFolder.mockResolvedValue({
      id: "folder-new",
      name: "New Folder",
    });
    render(<Chains />);
    await waitFor(() => expect(mockApi.listFolder).toHaveBeenCalled());

    fireEvent.click(screen.getByTestId("menu-item-folder"));
    const onSubmit = mockShowModal.mock.calls.at(-1)![0].component.props
      .onSubmit as (n: string, o: boolean, t: boolean) => Promise<void>;
    await onSubmit("New Folder", false, false);

    await waitFor(() =>
      expect(mockApi.createFolder).toHaveBeenCalledWith({
        name: "New Folder",
        parentId: undefined,
      }),
    );
  });

  it("updates a folder via the folder edit modal onSubmit", async () => {
    mockApi.listFolder.mockResolvedValue([mockFolder]);
    mockApi.updateFolder.mockResolvedValue({ id: "folder-1", name: "Renamed" });
    render(<Chains />);
    await waitFor(() =>
      expect(screen.getByText("Test Folder")).toBeInTheDocument(),
    );

    fireEvent.click(screen.getByTestId("menu-item-editFolder"));
    const onSubmit = mockShowModal.mock.calls.at(-1)![0].component.props
      .onSubmit as (n: string, o: boolean, t: boolean) => Promise<void>;
    await onSubmit("Renamed", false, false);

    await waitFor(() =>
      expect(mockApi.updateFolder).toHaveBeenCalledWith("folder-1", {
        name: "Renamed",
      }),
    );
  });

  it("opens a create-folder modal from a folder's context menu", async () => {
    mockApi.listFolder.mockResolvedValue([mockFolder]);
    render(<Chains />);
    await waitFor(() =>
      expect(screen.getByText("Test Folder")).toBeInTheDocument(),
    );

    fireEvent.click(screen.getByTestId("menu-item-createNewFolder"));

    const call = mockShowModal.mock.calls.at(-1)![0];
    expect(call.component.props.mode).toBe("create");
    const onSubmit = call.component.props.onSubmit as (
      n: string,
      o: boolean,
      t: boolean,
    ) => Promise<void>;
    await onSubmit("Child", false, false);

    await waitFor(() =>
      expect(mockApi.createFolder).toHaveBeenCalledWith({
        name: "Child",
        parentId: "folder-1",
      }),
    );
  });

  it("handles chain context menu actions", async () => {
    mockApi.listFolder.mockResolvedValue([mockChain]);
    mockApi.updateChain.mockResolvedValue({
      ...mockChain,
      name: "Renamed Chain",
    });
    mockApi.duplicateChain.mockResolvedValue({
      ...mockChain,
      id: "chain-dup",
      name: "Duplicated Chain",
    });
    mockApi.deleteChain.mockResolvedValue(undefined);
    render(<Chains />);
    await screen.findByText("Test Chain");

    fireEvent.click(screen.getByTestId("menu-item-copyChainLink"));
    await waitFor(() =>
      expect(mockCopyToClipboard).toHaveBeenCalledWith(
        `${window.location.origin}/chains/chain-1`,
      ),
    );
    await waitFor(() =>
      expect(mockMessageInfo).toHaveBeenCalledWith(
        "Link to a chain was copied to the clipboard",
      ),
    );

    fireEvent.click(screen.getByTestId("menu-item-generateDDS"));
    expect(mockShowGenerateDdsModal).toHaveBeenCalledWith("chain-1");

    fireEvent.click(screen.getByTestId("menu-item-editChain"));
    const editProps = mockShowModal.mock.calls.at(-1)![0].component.props as {
      variant: string;
      chainId: string;
      onUpdateMetadata: (
        id: string,
        update: { name: string; labels: [] },
        open: boolean,
        tab: boolean,
      ) => Promise<void>;
    };
    expect(editProps.variant).toBe("editChainMetaData");
    expect(editProps.chainId).toBe("chain-1");

    const update = { name: "Renamed Chain", labels: [] as [] };
    await act(async () => {
      await editProps.onUpdateMetadata("chain-1", update, true, false);
    });
    expect(mockApi.updateChain).toHaveBeenCalledWith("chain-1", update);
    await waitFor(() =>
      expect(mockNavigate).toHaveBeenCalledWith("/chains/chain-1"),
    );
    await screen.findByText("Renamed Chain");

    mockApi.updateChain.mockRejectedValueOnce(new Error("offline"));
    await act(async () => {
      await editProps.onUpdateMetadata("chain-1", update, false, false);
    });
    expect(mockRequestFailed).toHaveBeenCalledWith(
      "Failed to update chain",
      expect.any(Error),
    );

    fireEvent.click(screen.getByTestId("menu-item-duplicateChain"));
    await screen.findByText("Duplicated Chain");

    const originalRow = document.querySelector<HTMLElement>(
      'tr[data-row-key="chain-1"]',
    )!;
    fireEvent.click(within(originalRow).getByTestId("menu-item-deleteChain"));
    const confirm = mockConfirmAndRun.mock.calls.at(-1)![0];
    expect(confirm.title).toBe("Delete Chain");
    expect(confirm.content).toContain("Renamed Chain");
    await act(async () => {
      await confirm.onOk();
    });
    expect(mockApi.deleteChain).toHaveBeenCalledWith("chain-1");
    await waitFor(() =>
      expect(screen.queryByText("Renamed Chain")).not.toBeInTheDocument(),
    );
  });

  it("copies a folder link and deletes the folder after confirmation", async () => {
    mockApi.listFolder.mockResolvedValue([mockFolder]);
    const error = new Error("offline");
    mockApi.deleteFolder
      .mockRejectedValueOnce(error)
      .mockResolvedValueOnce(undefined);
    render(<Chains />);
    await screen.findByText("Test Folder");

    fireEvent.click(screen.getByTestId("menu-item-copyFolderLink"));
    await waitFor(() =>
      expect(mockCopyToClipboard).toHaveBeenCalledWith(
        `${window.location.origin}/chains?folder=folder-1`,
      ),
    );
    await waitFor(() =>
      expect(mockMessageInfo).toHaveBeenCalledWith(
        "Link to a folder was copied to the clipboard",
      ),
    );

    fireEvent.click(screen.getByTestId("menu-item-deleteFolder"));
    const firstConfirm = mockConfirmAndRun.mock.calls.at(-1)![0];
    expect(firstConfirm.title).toBe("Delete Folder");
    await act(async () => {
      await firstConfirm.onOk();
    });
    await waitFor(() =>
      expect(mockRequestFailed).toHaveBeenCalledWith(
        "Failed to delete folder",
        error,
      ),
    );
    expect(screen.getByText("Test Folder")).toBeInTheDocument();

    fireEvent.click(screen.getByTestId("menu-item-deleteFolder"));
    await act(async () => {
      await mockConfirmAndRun.mock.calls.at(-1)![0].onOk();
    });
    await waitFor(() =>
      expect(mockApi.deleteFolder).toHaveBeenCalledWith("folder-1"),
    );
    await waitFor(() =>
      expect(screen.queryByText("Test Folder")).not.toBeInTheDocument(),
    );
  });

  it("moves or copies a chain when pasted from the toolbar", async () => {
    mockApi.listFolder.mockResolvedValue([mockChain]);
    mockApi.moveChain.mockResolvedValue({ ...mockChain, parentId: "folder-2" });
    mockApi.copyChain.mockResolvedValue({
      ...mockChain,
      id: "chain-copy",
      name: "Copied Chain",
    });
    render(<Chains />);
    await screen.findByText("Test Chain");

    fireEvent.click(screen.getByTestId("menu-item-cut"));
    fireEvent.click(screen.getByTestId("protected-btn-paste"));
    await waitFor(() =>
      expect(mockApi.moveChain).toHaveBeenCalledWith("chain-1", undefined),
    );
    expect(mockApi.copyChain).not.toHaveBeenCalled();

    fireEvent.click(screen.getByTestId("menu-item-copy"));
    fireEvent.click(screen.getByTestId("protected-btn-paste"));
    await waitFor(() =>
      expect(mockApi.copyChain).toHaveBeenCalledWith("chain-1", undefined),
    );
    await screen.findByText("Copied Chain");

    fireEvent.click(screen.getByTestId("protected-btn-paste"));
    expect(mockApi.moveChain).toHaveBeenCalledTimes(1);
    expect(mockApi.copyChain).toHaveBeenCalledTimes(1);
  });

  it("compares and deploys the selected chains", async () => {
    mockApi.listFolder.mockResolvedValue([mockChain, mockChain2]);
    mockApi.bulkDeploy.mockResolvedValue([]);
    render(<Chains />);
    await screen.findByText("Test Chain");
    await screen.findByText("Another Chain");

    const compare = () =>
      screen.getByTestId("protected-btn-compare-selected-chains");
    const deploy = () =>
      screen.getByTestId("protected-btn-deploy-selected-chains");
    const checkboxes = () =>
      document.querySelectorAll<HTMLInputElement>(
        'tbody input[type="checkbox"]',
      );

    expect(compare()).toBeDisabled();
    expect(deploy()).toBeDisabled();

    fireEvent.click(checkboxes()[0]);
    expect(deploy()).not.toBeDisabled();
    expect(compare()).toBeDisabled();

    fireEvent.click(checkboxes()[1]);
    expect(compare()).not.toBeDisabled();

    fireEvent.click(compare());
    const diffProps = mockShowModal.mock.calls.at(-1)![0].component.props as {
      item1: { kind: string; id: string };
      item2: { kind: string; id: string };
    };
    expect(diffProps.item1).toEqual({ kind: "chain", id: "chain-1" });
    expect(diffProps.item2).toEqual({ kind: "chain", id: "chain-2" });

    fireEvent.click(deploy());
    const deployProps = mockShowModal.mock.calls.at(-1)![0].component.props as {
      chainCount: number;
      chainIds: string[];
      onSubmit: (request: {
        domains: { name: string; type: DomainType }[];
        snapshotAction: BulkDeploymentSnapshotAction;
      }) => Promise<unknown>;
    };
    expect(deployProps.chainCount).toBe(2);
    expect(deployProps.chainIds).toEqual(["chain-1", "chain-2"]);

    const request = {
      domains: [{ name: "domain-a", type: DomainType.CLASSIC }],
      snapshotAction: BulkDeploymentSnapshotAction.LAST_CREATED,
    };
    await act(async () => {
      await deployProps.onSubmit(request);
    });
    expect(mockApi.bulkDeploy).toHaveBeenCalledWith({
      domains: ["domain-a"],
      chainIds: ["chain-1", "chain-2"],
      snapshotAction: BulkDeploymentSnapshotAction.LAST_CREATED,
      mode: DeployMode.APPEND,
    });

    const error = new Error("deploy failed");
    mockApi.bulkDeploy.mockRejectedValueOnce(error);
    let failure: unknown;
    await act(async () => {
      try {
        await deployProps.onSubmit(request);
      } catch (caught) {
        failure = caught;
      }
    });
    expect(failure).toBe(error);
    expect(mockRequestFailed).toHaveBeenCalledWith(
      "Failed to deploy chains",
      error,
    );
  });

  it("deletes selected folders and chains through the toolbar after confirmation", async () => {
    mockApi.listFolder.mockResolvedValue([mockFolder, mockChain]);
    mockApi.deleteFolders.mockResolvedValue(undefined);
    mockApi.deleteChains.mockResolvedValue(undefined);
    render(<Chains />);
    await screen.findByText("Test Folder");
    await screen.findByText("Test Chain");

    fireEvent.click(
      screen.getByTestId("protected-btn-delete-selected-chains-and-folders"),
    );
    expect(mockConfirmAndRun).not.toHaveBeenCalled();

    const checkboxes = document.querySelectorAll<HTMLInputElement>(
      'tbody input[type="checkbox"]',
    );
    fireEvent.click(checkboxes[0]);
    fireEvent.click(checkboxes[1]);
    fireEvent.click(
      screen.getByTestId("protected-btn-delete-selected-chains-and-folders"),
    );

    const confirm = mockConfirmAndRun.mock.calls.at(-1)![0];
    expect(confirm.title).toBe("Delete selected");
    expect(confirm.content).toContain("folders and chains");
    await act(async () => {
      await confirm.onOk();
    });

    await waitFor(() =>
      expect(mockApi.deleteFolders).toHaveBeenCalledWith(["folder-1"]),
    );
    expect(mockApi.deleteChains).toHaveBeenCalledWith(["chain-1"]);
    expect(mockApi.deleteFolder).not.toHaveBeenCalled();
    expect(mockApi.deleteChain).not.toHaveBeenCalled();
  });

  it("exports all chains or the selected chain through the export modal", async () => {
    mockApi.listFolder.mockResolvedValue([mockChain]);
    mockApi.exportAllChains.mockResolvedValue({
      size: 1,
      name: "all-chains.zip",
    });
    mockApi.exportChains.mockResolvedValue({
      size: 1,
      name: "selected-chain.zip",
    });
    render(<Chains />);
    await screen.findByText("Test Chain");

    const submitExport = () => {
      const props = mockShowModal.mock.calls.at(-1)![0].component.props as {
        multiple: boolean;
        onSubmit: (options: {
          exportSubchains: boolean;
          exportServices: boolean;
          exportVariables: boolean;
        }) => void;
      };
      const options = {
        exportSubchains: true,
        exportServices: false,
        exportVariables: false,
      };
      return { props, options };
    };

    fireEvent.click(screen.getByTestId("protected-btn-export-selected-chains"));
    let { props, options } = submitExport();
    expect(props.multiple).toBe(true);
    props.onSubmit(options);
    await waitFor(() =>
      expect(mockApi.exportAllChains).toHaveBeenCalledTimes(1),
    );
    await waitFor(() =>
      expect(mockDownloadFile).toHaveBeenCalledWith(
        expect.objectContaining({ name: "all-chains.zip" }),
      ),
    );

    fireEvent.click(document.querySelector('tbody input[type="checkbox"]')!);
    fireEvent.click(screen.getByTestId("protected-btn-export-selected-chains"));
    ({ props, options } = submitExport());
    expect(props.multiple).toBe(false);
    props.onSubmit(options);
    await waitFor(() =>
      expect(mockApi.exportChains).toHaveBeenCalledWith(["chain-1"], true),
    );
    await waitFor(() =>
      expect(mockDownloadFile).toHaveBeenLastCalledWith(
        expect.objectContaining({ name: "selected-chain.zip" }),
      ),
    );
  });

  it("loads folder children on expand and reuses them afterwards", async () => {
    mockApi.listFolder.mockImplementation(
      async (request: { folderId?: string }) => {
        if (request.folderId === "folder-1") {
          return [mockNestedChain];
        }
        return [mockFolder];
      },
    );
    render(<Chains />);
    await screen.findByText("Test Folder");

    fireEvent.click(screen.getByRole("button", { name: "Expand row" }));
    await screen.findByText("Nested Chain");
    expect(mockApi.listFolder).toHaveBeenCalledWith({
      folderId: "folder-1",
      filters: [],
      searchString: "",
    });
    const folderLoads = () =>
      mockApi.listFolder.mock.calls.filter(
        ([request]: [{ folderId?: string }]) => request.folderId === "folder-1",
      ).length;
    expect(folderLoads()).toBe(1);

    fireEvent.click(screen.getByRole("button", { name: "Collapse row" }));
    await waitFor(() =>
      expect(screen.queryByText("Nested Chain")).not.toBeInTheDocument(),
    );

    fireEvent.click(screen.getByRole("button", { name: "Expand row" }));
    await screen.findByText("Nested Chain");
    expect(folderLoads()).toBe(1);
  });

  it("creates a folder and opens it in the current or a new tab", async () => {
    mockApi.createFolder.mockResolvedValue({
      id: "folder-new",
      name: "New Folder",
    });
    render(<Chains />);
    await waitFor(() => expect(mockApi.listFolder).toHaveBeenCalled());

    fireEvent.click(screen.getByTestId("menu-item-folder"));
    const onSubmit = mockShowModal.mock.calls.at(-1)![0].component.props
      .onSubmit as (name: string, open: boolean, tab: boolean) => Promise<void>;

    await act(async () => {
      await onSubmit("New Folder", true, false);
    });
    await waitFor(() =>
      expect(mockNavigate).toHaveBeenCalledWith("/chains?folder=folder-new"),
    );

    const openSpy = jest.spyOn(window, "open").mockImplementation(() => null);
    await act(async () => {
      await onSubmit("Another Folder", true, true);
    });
    expect(openSpy).toHaveBeenCalledWith(
      "/chains?folder=folder-new",
      "_blank",
      "noreferrer",
    );
    openSpy.mockRestore();

    const error = new Error("offline");
    mockApi.createFolder.mockRejectedValueOnce(error);
    await act(async () => {
      await onSubmit("Broken Folder", false, false);
    });
    expect(mockRequestFailed).toHaveBeenCalledWith(
      "Failed to create folder",
      error,
    );
  });

  it("creates a chain and opens it in the current or a new tab", async () => {
    mockApi.createChain.mockResolvedValue({
      ...mockChain,
      id: "chain-new",
      name: "New Chain",
    });
    render(<Chains />);
    await waitFor(() => expect(mockApi.listFolder).toHaveBeenCalled());

    fireEvent.click(screen.getByTestId("menu-item-chain"));
    const onSubmit = mockShowModal.mock.calls.at(-1)![0].component.props
      .onSubmit as (
      request: ChainCreationRequest,
      open: boolean,
      tab: boolean,
    ) => Promise<void>;

    await act(async () => {
      await onSubmit({ name: "New Chain" }, true, false);
    });
    expect(mockApi.createChain).toHaveBeenCalledWith({
      name: "New Chain",
      parentId: undefined,
    });
    await waitFor(() =>
      expect(mockNavigate).toHaveBeenCalledWith("/chains/chain-new"),
    );
    await waitFor(() =>
      expect(
        document.querySelector('tr[data-row-key="chain-new"]'),
      ).toBeInTheDocument(),
    );

    const openSpy = jest.spyOn(window, "open").mockImplementation(() => null);
    await act(async () => {
      await onSubmit({ name: "Another Chain" }, true, true);
    });
    expect(openSpy).toHaveBeenCalledWith(
      "/chains/chain-new",
      "_blank",
      "noreferrer",
    );
    openSpy.mockRestore();

    const error = new Error("offline");
    mockApi.createChain.mockRejectedValueOnce(error);
    await act(async () => {
      await onSubmit({ name: "Broken Chain" }, false, false);
    });
    expect(mockRequestFailed).toHaveBeenCalledWith(
      "Failed to create chain",
      error,
    );
  });

  it("keeps the open details drawer in sync during catalog refreshes", async () => {
    const dispatchCatalogChange = () =>
      act(async () =>
        window.dispatchEvent(
          new CustomEvent("qip-catalog-changed", {
            detail: { chainIds: [mockChain.id], chainsListChanged: true },
          }),
        ),
      );

    mockApi.listFolder.mockResolvedValue([mockChain]);
    render(<Chains />);
    await screen.findByText("Test Chain");
    fireEvent.click(screen.getByText("A test chain"));
    expect(screen.getByTestId("drawer-chain-id")).toHaveTextContent("chain-1");

    mockApi.listFolder.mockResolvedValue([
      { ...mockChain, name: "Updated In List" },
    ]);
    await dispatchCatalogChange();
    await waitFor(() =>
      expect(screen.getByTestId("drawer-chain-name")).toHaveTextContent(
        "Updated In List",
      ),
    );

    mockApi.listFolder.mockResolvedValue([]);
    mockApi.getChain.mockResolvedValue({
      ...mockChain,
      name: "Updated From Details",
    });
    await dispatchCatalogChange();
    await waitFor(() =>
      expect(screen.getByTestId("drawer-chain-name")).toHaveTextContent(
        "Updated From Details",
      ),
    );

    mockApi.getChain.mockRejectedValueOnce(new RestApiError("gone", 404));
    await dispatchCatalogChange();
    await waitFor(() =>
      expect(
        screen.queryByTestId("chain-details-drawer"),
      ).not.toBeInTheDocument(),
    );
    expect(mockNotificationService.info).toHaveBeenCalledWith(
      "Chain deleted",
      expect.stringContaining("no longer exists"),
    );
  });

  describe("ensureChainSubtreeLoaded", () => {
    const makeFolder = (
      id: string,
      name: string,
      parentId?: string,
    ): FolderItem => ({
      id,
      name,
      description: "",
      itemType: CatalogItemType.FOLDER,
      ...(parentId ? { parentId } : {}),
    });

    const makeChain = (
      id: string,
      name: string,
      parentId?: string,
    ): ChainItem => ({
      id,
      name,
      description: "",
      itemType: CatalogItemType.CHAIN,
      labels: [],
      ...(parentId ? { parentId } : {}),
    });

    const renderAndCaptureEnsureLoaded = async (
      rootItems: (FolderItem | ChainItem)[] = [],
    ) => {
      mockApi.listFolder.mockResolvedValue(rootItems);
      render(<Chains />);
      await waitFor(() =>
        expect(mockApi.listFolder).toHaveBeenCalledWith(
          expect.objectContaining({ folderId: undefined }),
        ),
      );
      await waitFor(() => expect(mockCapturedEnsureChainLoaded).toBeDefined());
      return mockCapturedEnsureChainLoaded!;
    };

    it("should return only the chain id when record is not a folder", async () => {
      const ensureLoaded = await renderAndCaptureEnsureLoaded([mockChain]);
      mockApi.listFolder.mockClear();

      const result = await ensureLoaded(mockChain);
      expect(result).toEqual(["chain-1"]);
      expect(mockApi.listFolder).not.toHaveBeenCalled();
    });

    it("should fetch an unloaded folder subtree and return descendant folder ids", async () => {
      const childFolder = makeFolder("folder-1-1", "Child", "folder-1");
      const childChain = makeChain("chain-child", "Child Chain", "folder-1");
      mockApi.listFolder.mockImplementation(
        async (req: { folderId?: string }) => {
          if (req.folderId === "folder-1") {
            return [childFolder, childChain];
          }
          if (req.folderId === "folder-1-1") {
            return [];
          }
          return [mockFolder];
        },
      );
      render(<Chains />);
      await waitFor(() =>
        expect(mockApi.listFolder).toHaveBeenCalledWith(
          expect.objectContaining({ folderId: undefined }),
        ),
      );
      await waitFor(() => expect(mockCapturedEnsureChainLoaded).toBeDefined());
      const ensureLoaded = mockCapturedEnsureChainLoaded!;
      mockApi.listFolder.mockClear();

      const result = await ensureLoaded(mockFolder);

      expect(mockApi.listFolder).toHaveBeenCalledWith(
        expect.objectContaining({ folderId: "folder-1" }),
      );
      expect(mockApi.listFolder).toHaveBeenCalledWith(
        expect.objectContaining({ folderId: "folder-1-1" }),
      );
      // Only folders are part of the expand/collapse subtree; chains are not.
      expect(result).toEqual(
        expect.arrayContaining(["folder-1", "folder-1-1"]),
      );
      expect(result).not.toContain("chain-child");
    });

    it("should reuse cached child folders when the folder is expanded again", async () => {
      const childFolder = makeFolder("folder-1-1", "Child", "folder-1");
      mockApi.listFolder.mockImplementation(
        async (req: { folderId?: string }) => {
          if (req.folderId === "folder-1") {
            return [childFolder];
          }
          if (req.folderId === "folder-1-1") {
            return [];
          }
          return [mockFolder];
        },
      );
      render(<Chains />);
      await waitFor(() => expect(mockCapturedEnsureChainLoaded).toBeDefined());
      const firstEnsure = mockCapturedEnsureChainLoaded!;
      mockApi.listFolder.mockClear();

      await firstEnsure(mockFolder);
      const callsAfterFirst = mockApi.listFolder.mock.calls.length;
      expect(callsAfterFirst).toBeGreaterThan(0);

      await waitFor(() =>
        expect(mockCapturedEnsureChainLoaded).not.toBe(firstEnsure),
      );
      const freshEnsure = mockCapturedEnsureChainLoaded!;
      mockApi.listFolder.mockClear();

      const result = await freshEnsure(mockFolder);

      // The already-loaded child folder is reused without another fetch.
      expect(mockApi.listFolder).toHaveBeenCalledTimes(1);
      expect(mockApi.listFolder).toHaveBeenCalledWith(
        expect.objectContaining({ folderId: "folder-1" }),
      );
      expect(result).toEqual(
        expect.arrayContaining(["folder-1", "folder-1-1"]),
      );
    });

    it("should notify and return local ids when folder content fails to load", async () => {
      const ensureLoaded = await renderAndCaptureEnsureLoaded([mockFolder]);
      mockApi.listFolder.mockRejectedValue(new Error("boom"));
      mockRequestFailed.mockClear();

      const result = await ensureLoaded(mockFolder);

      expect(mockRequestFailed).toHaveBeenCalledWith(
        "Failed to get folder content",
        expect.any(Error),
      );
      expect(result).toEqual(["folder-1"]);
    });

    it("should load nested folders recursively", async () => {
      const level1 = makeFolder("folder-1-1", "Level 1", "folder-1");
      const level2 = makeFolder("folder-1-1-1", "Level 2", "folder-1-1");
      const leafChain = makeChain("chain-leaf", "Leaf", "folder-1-1-1");
      mockApi.listFolder.mockImplementation(
        async (req: { folderId?: string }) => {
          if (req.folderId === undefined) {
            return [mockFolder];
          }
          if (req.folderId === "folder-1") {
            return [level1];
          }
          if (req.folderId === "folder-1-1") {
            return [level2];
          }
          return [leafChain];
        },
      );
      render(<Chains />);
      await waitFor(() => expect(mockCapturedEnsureChainLoaded).toBeDefined());
      const ensureLoaded = mockCapturedEnsureChainLoaded!;
      mockApi.listFolder.mockClear();

      const result = await ensureLoaded(mockFolder);

      expect(mockApi.listFolder).toHaveBeenCalledWith(
        expect.objectContaining({ folderId: "folder-1" }),
      );
      expect(mockApi.listFolder).toHaveBeenCalledWith(
        expect.objectContaining({ folderId: "folder-1-1" }),
      );
      expect(mockApi.listFolder).toHaveBeenCalledWith(
        expect.objectContaining({ folderId: "folder-1-1-1" }),
      );
      expect(result).toEqual(
        expect.arrayContaining(["folder-1", "folder-1-1", "folder-1-1-1"]),
      );
    });

    it("should load the subtree when Expand All is clicked in the folder menu", async () => {
      const nestedChain = makeChain("chain-nested", "Nested Chain", "folder-1");
      mockApi.listFolder.mockImplementation(
        async (req: { folderId?: string }) => {
          if (req.folderId === "folder-1") {
            return [nestedChain];
          }
          return [mockFolder];
        },
      );
      render(<Chains />);
      await waitFor(() =>
        expect(screen.getByText("Test Folder")).toBeInTheDocument(),
      );

      mockApi.listFolder.mockClear();
      fireEvent.click(screen.getByTestId("menu-item-expandAll"));

      await waitFor(() =>
        expect(mockApi.listFolder).toHaveBeenCalledWith(
          expect.objectContaining({ folderId: "folder-1" }),
        ),
      );
      await waitFor(() =>
        expect(screen.getByText("Nested Chain")).toBeInTheDocument(),
      );
    });
  });
});
