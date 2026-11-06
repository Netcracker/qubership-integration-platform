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
} from "@testing-library/react";
import "@testing-library/jest-dom";
import {
  RestApiError,
  CatalogItemType,
  FolderItem,
  ChainItem,
} from "../../src/api/apiTypes";

// --------------- Mock data ---------------

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

const makeChain = (id: string, name: string, parentId?: string): ChainItem => ({
  id,
  name,
  description: "",
  itemType: CatalogItemType.CHAIN,
  labels: [],
  ...(parentId ? { parentId } : {}),
});

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

jest.mock("../../src/hooks/useGenerateDds.tsx", () => ({
  useGenerateDds: () => ({
    showGenerateDdsModal: jest.fn(),
  }),
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

jest.mock("../../src/misc/clipboard-util", () => ({
  copyToClipboard: jest.fn().mockResolvedValue(undefined),
}));

jest.mock("../../src/misc/selection-utils", () => ({
  toStringIds: (keys: React.Key[]) => keys.map(String),
}));

jest.mock("../../src/misc/confirm-utils", () => ({
  confirmAndRun: (options: { onOk?: () => void }) => options.onOk?.(),
}));

jest.mock("../../src/misc/tree-utils", () => ({
  traverseElementsDepthFirst: jest.fn(),
}));

jest.mock("../../src/misc/download-utils", () => ({
  downloadFile: jest.fn(),
  mergeZipArchives: jest.fn().mockResolvedValue(new ArrayBuffer(0)),
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

  it("invalidates a pending subtree load when a catalog refresh commits", async () => {
    const obsoleteChain = makeChain(
      "obsolete-chain",
      "Obsolete Chain",
      "folder-1",
    );
    const currentChain = makeChain(
      "current-chain",
      "Current Chain",
      "folder-1",
    );
    let resolveObsolete: (items: (FolderItem | ChainItem)[]) => void = () => {};
    let folderRequests = 0;
    let rootRequests = 0;
    mockApi.listFolder.mockImplementation((req: { folderId?: string }) => {
      if (req.folderId === "folder-1") {
        folderRequests += 1;
        if (folderRequests === 1) {
          return new Promise((resolve) => {
            resolveObsolete = resolve;
          });
        }
        return Promise.resolve([currentChain]);
      }
      rootRequests += 1;
      return Promise.resolve([mockFolder, mockChain]);
    });
    render(<Chains />);
    await screen.findByText("Test Folder");
    fireEvent.click(
      document.querySelector<HTMLInputElement>(
        '[data-row-key="folder-1"] input[type="checkbox"]',
      )!,
    );
    await waitFor(() => expect(folderRequests).toBe(1));

    await act(async () =>
      window.dispatchEvent(
        new CustomEvent("qip-catalog-changed", {
          detail: { chainIds: null, chainsListChanged: true },
        }),
      ),
    );
    await waitFor(() => expect(rootRequests).toBe(2));
    await act(async () => resolveObsolete([obsoleteChain]));

    fireEvent.click(
      document.querySelector<HTMLInputElement>(
        '[data-row-key="folder-1"] input[type="checkbox"]',
      )!,
    );
    await waitFor(() => expect(folderRequests).toBe(2));
    const deploy = screen.getByTestId("protected-btn-deploy-selected-chains");
    await waitFor(() => expect(deploy).toBeEnabled());
    fireEvent.click(deploy);
    expect(
      mockShowModal.mock.calls.at(-1)?.[0].component.props.chainIds,
    ).toEqual(["current-chain"]);
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

  describe("ensureChainSubtreeLoaded", () => {
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

    it("should reuse the whole cached subtree when the folder is expanded again", async () => {
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

      // The first walk cached the whole subtree, so the second fetches nothing.
      expect(mockApi.listFolder).not.toHaveBeenCalled();
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

    it("should fetch a folder again after its content failed to load", async () => {
      const ensureLoaded = await renderAndCaptureEnsureLoaded([mockFolder]);
      mockApi.listFolder.mockRejectedValueOnce(new Error("boom"));

      await ensureLoaded(mockFolder);
      mockApi.listFolder.mockClear();

      await ensureLoaded(mockFolder);

      expect(mockApi.listFolder).toHaveBeenCalledWith(
        expect.objectContaining({ folderId: "folder-1" }),
      );
    });

    it("should keep both folders when two subtree walks finish out of order", async () => {
      const folderA = makeFolder("folder-a", "A");
      const folderB = makeFolder("folder-b", "B");
      let resolveA: (items: (FolderItem | ChainItem)[]) => void = () => {};
      let resolveB: (items: (FolderItem | ChainItem)[]) => void = () => {};
      mockApi.listFolder.mockImplementation(
        (req: { folderId?: string }): Promise<(FolderItem | ChainItem)[]> => {
          if (req.folderId === "folder-a") {
            return new Promise((resolve) => {
              resolveA = resolve;
            });
          }
          if (req.folderId === "folder-b") {
            return new Promise((resolve) => {
              resolveB = resolve;
            });
          }
          return Promise.resolve([folderA, folderB]);
        },
      );
      render(<Chains />);
      await waitFor(() => expect(mockCapturedEnsureChainLoaded).toBeDefined());
      const firstEnsure = mockCapturedEnsureChainLoaded!;

      const walkA = firstEnsure(folderA);
      const walkB = firstEnsure(folderB);
      await act(async () => {
        resolveA([]);
        await walkA;
      });
      await act(async () => {
        resolveB([]);
        await walkB;
      });

      await waitFor(() =>
        expect(mockCapturedEnsureChainLoaded).not.toBe(firstEnsure),
      );
      const freshEnsure = mockCapturedEnsureChainLoaded!;
      mockApi.listFolder.mockClear();

      await freshEnsure(folderA);

      expect(mockApi.listFolder).not.toHaveBeenCalled();
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

  describe("folder selection cascades to nested chains", () => {
    const deployButton = () =>
      screen.getByTestId("protected-btn-deploy-selected-chains");

    const folderCheckbox = (folderId: string) =>
      document.querySelector<HTMLInputElement>(
        `[data-row-key="${folderId}"] input[type="checkbox"]`,
      )!;

    it("enables Deploy when a folder is ticked without expanding it", async () => {
      mockApi.listFolder.mockImplementation(
        async (req: { folderId?: string }) => {
          if (req.folderId === "folder-1") {
            return [mockNestedChain];
          }
          return [mockFolder];
        },
      );
      render(<Chains />);
      await screen.findByText("Test Folder");
      expect(deployButton()).toBeDisabled();

      fireEvent.click(folderCheckbox("folder-1"));

      await waitFor(() => expect(deployButton()).toBeEnabled());
    });

    it("keeps Deploy disabled for a folder without chains", async () => {
      mockApi.listFolder.mockImplementation(
        async (req: { folderId?: string }) => {
          if (req.folderId === "folder-1") {
            return [];
          }
          return [mockFolder];
        },
      );
      render(<Chains />);
      await screen.findByText("Test Folder");

      fireEvent.click(folderCheckbox("folder-1"));

      await waitFor(() =>
        expect(mockApi.listFolder).toHaveBeenCalledWith(
          expect.objectContaining({ folderId: "folder-1" }),
        ),
      );
      expect(deployButton()).toBeDisabled();
    });

    it("selects chains nested inside uncached subfolders on tick", async () => {
      const subFolder = makeFolder("folder-1-1", "Sub Folder", "folder-1");
      const deepChain = makeChain("chain-deep", "Deep Chain", "folder-1-1");
      mockApi.listFolder.mockImplementation(
        async (req: { folderId?: string }) => {
          if (req.folderId === "folder-1") {
            return [subFolder];
          }
          if (req.folderId === "folder-1-1") {
            return [deepChain];
          }
          return [mockFolder];
        },
      );
      render(<Chains />);
      await screen.findByText("Test Folder");

      fireEvent.click(folderCheckbox("folder-1"));

      await waitFor(() =>
        expect(mockApi.listFolder).toHaveBeenCalledWith(
          expect.objectContaining({ folderId: "folder-1" }),
        ),
      );
      await waitFor(() =>
        expect(mockApi.listFolder).toHaveBeenCalledWith(
          expect.objectContaining({ folderId: "folder-1-1" }),
        ),
      );
      await waitFor(() => expect(deployButton()).toBeEnabled());
    });

    it("keeps Deploy disabled when a nested folder in a selected subtree fails to load", async () => {
      const childFolder = makeFolder("folder-1-1", "Sub Folder", "folder-1");
      const directChain = makeChain("chain-direct", "Direct Chain", "folder-1");
      const nestedChain = makeChain(
        "chain-nested",
        "Nested Chain",
        "folder-1-1",
      );
      let nestedRequests = 0;
      mockApi.listFolder.mockImplementation(
        async (req: { folderId?: string }) => {
          if (req.folderId === "folder-1") return [childFolder, directChain];
          if (req.folderId === "folder-1-1") {
            nestedRequests += 1;
            if (nestedRequests === 1) {
              throw new Error("nested folder unavailable");
            }
            return [nestedChain];
          }
          return [mockFolder];
        },
      );
      render(<Chains />);
      await screen.findByText("Test Folder");

      const checkbox = folderCheckbox("folder-1");
      fireEvent.click(checkbox);

      await waitFor(() =>
        expect(mockApi.listFolder).toHaveBeenCalledWith(
          expect.objectContaining({ folderId: "folder-1-1" }),
        ),
      );
      await waitFor(() => expect(deployButton()).toBeDisabled());

      fireEvent.click(checkbox);
      fireEvent.click(checkbox);

      await waitFor(() => expect(deployButton()).toBeEnabled());
    });

    it("disables Deploy immediately while a newly selected folder is loading", async () => {
      let resolveFolder: (items: (FolderItem | ChainItem)[]) => void = () => {};
      mockApi.listFolder.mockImplementation(
        (req: { folderId?: string }): Promise<(FolderItem | ChainItem)[]> => {
          if (req.folderId === "folder-1") {
            return new Promise((resolve) => {
              resolveFolder = resolve;
            });
          }
          return Promise.resolve([mockFolder, mockChain]);
        },
      );
      render(<Chains />);
      await screen.findByText("Test Folder");

      fireEvent.click(
        document.querySelector<HTMLInputElement>(
          '[data-row-key="chain-1"] input[type="checkbox"]',
        )!,
      );
      await waitFor(() => expect(deployButton()).toBeEnabled());

      fireEvent.click(folderCheckbox("folder-1"));
      await waitFor(() =>
        expect(mockApi.listFolder).toHaveBeenCalledWith(
          expect.objectContaining({ folderId: "folder-1" }),
        ),
      );

      expect(deployButton()).toBeDisabled();
      fireEvent.click(deployButton());
      expect(mockShowModal).not.toHaveBeenCalled();

      await act(async () => resolveFolder([mockNestedChain]));

      await waitFor(() => expect(deployButton()).toBeEnabled());
    });

    it("keeps Deploy disabled when an earlier folder load finishes before a retick load", async () => {
      const pendingResponses: Array<
        (items: (FolderItem | ChainItem)[]) => void
      > = [];
      mockApi.listFolder.mockImplementation(
        (req: { folderId?: string }): Promise<(FolderItem | ChainItem)[]> => {
          if (req.folderId === "folder-1") {
            return new Promise((resolve) => pendingResponses.push(resolve));
          }
          return Promise.resolve([mockFolder]);
        },
      );
      render(<Chains />);
      await screen.findByText("Test Folder");
      const checkbox = folderCheckbox("folder-1");

      fireEvent.click(checkbox);
      await waitFor(() => expect(pendingResponses).toHaveLength(1));
      fireEvent.click(checkbox);
      fireEvent.click(checkbox);
      await waitFor(() => expect(pendingResponses).toHaveLength(2));

      await act(async () => pendingResponses[0]([mockNestedChain]));
      expect(deployButton()).toBeDisabled();

      await act(async () => pendingResponses[1]([mockNestedChain]));
      await waitFor(() => expect(deployButton()).toBeEnabled());
    });

    it("ignores an older failure after the current folder selection succeeds", async () => {
      const responses: Array<{
        resolve: (items: (FolderItem | ChainItem)[]) => void;
        reject: (error: Error) => void;
      }> = [];
      mockApi.listFolder.mockImplementation((req: { folderId?: string }) => {
        if (req.folderId === "folder-1") {
          return new Promise((resolve, reject) =>
            responses.push({ resolve, reject }),
          );
        }
        return Promise.resolve([mockFolder, mockChain]);
      });
      render(<Chains />);
      await screen.findByText("Test Folder");
      fireEvent.click(
        document.querySelector<HTMLInputElement>(
          '[data-row-key="chain-1"] input[type="checkbox"]',
        )!,
      );
      fireEvent.click(folderCheckbox("folder-1"));
      await waitFor(() => expect(responses).toHaveLength(1));
      fireEvent.click(folderCheckbox("folder-1"));
      fireEvent.click(folderCheckbox("folder-1"));
      await waitFor(() => expect(responses).toHaveLength(2));

      await act(async () => responses[1].resolve([mockNestedChain]));
      await waitFor(() => expect(deployButton()).toBeEnabled());
      await act(async () =>
        responses[0].reject(new Error("old request failed")),
      );

      expect(deployButton()).toBeEnabled();
    });

    it("keeps the current folder selection incomplete after an older success", async () => {
      const responses: Array<{
        resolve: (items: (FolderItem | ChainItem)[]) => void;
        reject: (error: Error) => void;
      }> = [];
      mockApi.listFolder.mockImplementation((req: { folderId?: string }) => {
        if (req.folderId === "folder-1") {
          return new Promise((resolve, reject) =>
            responses.push({ resolve, reject }),
          );
        }
        return Promise.resolve([mockFolder, mockChain]);
      });
      render(<Chains />);
      await screen.findByText("Test Folder");
      fireEvent.click(
        document.querySelector<HTMLInputElement>(
          '[data-row-key="chain-1"] input[type="checkbox"]',
        )!,
      );
      fireEvent.click(folderCheckbox("folder-1"));
      await waitFor(() => expect(responses).toHaveLength(1));
      fireEvent.click(folderCheckbox("folder-1"));
      fireEvent.click(folderCheckbox("folder-1"));
      await waitFor(() => expect(responses).toHaveLength(2));

      await act(async () =>
        responses[1].reject(new Error("current request failed")),
      );
      expect(deployButton()).toBeDisabled();
      await act(async () => responses[0].resolve([mockNestedChain]));

      expect(deployButton()).toBeDisabled();
    });

    it("does not select chains from a folder unticked while its content is loading", async () => {
      let resolveChildren: (
        items: (FolderItem | ChainItem)[],
      ) => void = () => {};
      mockApi.listFolder.mockImplementation(
        (req: { folderId?: string }): Promise<(FolderItem | ChainItem)[]> => {
          if (req.folderId === "folder-1") {
            return new Promise((resolve) => {
              resolveChildren = resolve;
            });
          }
          return Promise.resolve([mockFolder]);
        },
      );
      render(<Chains />);
      await screen.findByText("Test Folder");
      const checkbox = folderCheckbox("folder-1");
      fireEvent.click(checkbox);
      fireEvent.click(checkbox);
      await act(async () => {
        resolveChildren([mockNestedChain]);
      });
      expect(deployButton()).toBeDisabled();
    });

    it("discards a subtree response started before the search view changed", async () => {
      const staleChain = makeChain("stale-chain", "Stale Chain", "folder-1");
      let resolveSubtree: (
        items: (FolderItem | ChainItem)[],
      ) => void = () => {};
      let subtreeRequests = 0;
      mockApi.listFolder.mockImplementation(
        (req: { folderId?: string; searchString?: string }) => {
          if (req.folderId === "folder-1") {
            subtreeRequests += 1;
            if (subtreeRequests === 1) {
              return new Promise((resolve) => {
                resolveSubtree = resolve;
              });
            }
          }
          return Promise.resolve([mockFolder]);
        },
      );
      render(<Chains />);
      await screen.findByText("Test Folder");

      fireEvent.click(folderCheckbox("folder-1"));
      await waitFor(() =>
        expect(mockApi.listFolder).toHaveBeenCalledWith(
          expect.objectContaining({ folderId: "folder-1" }),
        ),
      );
      fireEvent.change(screen.getByTestId("search-input"), {
        target: { value: "changed" },
      });
      await waitFor(() =>
        expect(mockApi.listFolder).toHaveBeenCalledWith(
          expect.objectContaining({ searchString: "changed" }),
        ),
      );

      await act(async () => resolveSubtree([staleChain]));

      expect(screen.queryByText("Stale Chain")).not.toBeInTheDocument();
      await mockCapturedEnsureChainLoaded!(mockFolder);
      expect(subtreeRequests).toBe(2);
    });

    it("clears a pending folder selection when the changed view fails to load", async () => {
      let resolveSubtree: (
        items: (FolderItem | ChainItem)[],
      ) => void = () => {};
      mockApi.listFolder.mockImplementation(
        (req: { folderId?: string; searchString?: string }) => {
          if (req.folderId === "folder-1") {
            return new Promise((resolve) => {
              resolveSubtree = resolve;
            });
          }
          if (req.searchString === "changed") {
            return Promise.reject(new Error("offline"));
          }
          return Promise.resolve([mockFolder, mockChain]);
        },
      );
      render(<Chains />);
      await screen.findByText("Test Folder");
      const folderCheckboxInput = folderCheckbox("folder-1");

      fireEvent.click(
        document.querySelector<HTMLInputElement>(
          '[data-row-key="chain-1"] input[type="checkbox"]',
        )!,
      );
      fireEvent.click(folderCheckboxInput);
      await waitFor(() => expect(folderCheckbox("folder-1")).toBeChecked());
      fireEvent.change(screen.getByTestId("search-input"), {
        target: { value: "changed" },
      });
      await waitFor(() =>
        expect(mockRequestFailed).toHaveBeenCalledWith(
          "Failed to refresh chains; the previous list is preserved",
          expect.any(Error),
          expect.any(Function),
        ),
      );

      await waitFor(() => expect(folderCheckbox("folder-1")).not.toBeChecked());
      await waitFor(() => expect(deployButton()).toBeEnabled());
      await act(async () => resolveSubtree([mockNestedChain]));

      expect(screen.queryByText("Nested Chain")).not.toBeInTheDocument();
      expect(folderCheckbox("folder-1")).not.toBeChecked();
    });

    it("does not separately delete chains covered by a selected folder", async () => {
      const childChain = makeChain("chain-child", "Child Chain", "folder-1");
      mockApi.listFolder.mockImplementation(
        async (req: { folderId?: string }) =>
          req.folderId === "folder-1" ? [childChain] : [mockFolder],
      );
      render(<Chains />);
      await screen.findByText("Test Folder");
      fireEvent.click(folderCheckbox("folder-1"));
      await waitFor(() => expect(deployButton()).toBeEnabled());

      fireEvent.click(
        screen.getByTestId("protected-btn-delete-selected-chains-and-folders"),
      );

      await waitFor(() =>
        expect(mockApi.deleteFolders).toHaveBeenCalledWith(["folder-1"]),
      );
      expect(mockApi.deleteChains).not.toHaveBeenCalled();
    });

    it("does not delete covered chains separately when folder deletion fails", async () => {
      const childChain = makeChain("chain-child", "Child Chain", "folder-1");
      mockApi.listFolder.mockImplementation(
        async (req: { folderId?: string }) =>
          req.folderId === "folder-1" ? [childChain] : [mockFolder],
      );
      mockApi.deleteFolders.mockRejectedValueOnce(new Error("delete failed"));
      render(<Chains />);
      await screen.findByText("Test Folder");
      fireEvent.click(folderCheckbox("folder-1"));
      await waitFor(() => expect(deployButton()).toBeEnabled());

      fireEvent.click(
        screen.getByTestId("protected-btn-delete-selected-chains-and-folders"),
      );

      await waitFor(() =>
        expect(mockApi.deleteFolders).toHaveBeenCalledWith(["folder-1"]),
      );
      expect(mockRequestFailed).toHaveBeenCalledWith(
        "Failed to delete folders",
        expect.any(Error),
      );
      expect(mockApi.deleteChains).not.toHaveBeenCalled();
    });

    it("does not reload a folder's content when it is re-selected", async () => {
      mockApi.listFolder.mockImplementation(
        async (req: { folderId?: string }) => {
          if (req.folderId === "folder-1") {
            return [mockNestedChain];
          }
          return [mockFolder];
        },
      );
      render(<Chains />);
      await screen.findByText("Test Folder");

      fireEvent.click(folderCheckbox("folder-1"));
      await waitFor(() => expect(deployButton()).toBeEnabled());
      mockApi.listFolder.mockClear();

      fireEvent.click(folderCheckbox("folder-1"));
      await waitFor(() => expect(folderCheckbox("folder-1")).not.toBeChecked());
      fireEvent.click(folderCheckbox("folder-1"));
      await waitFor(() => expect(folderCheckbox("folder-1")).toBeChecked());
      await act(async () => undefined);

      expect(mockApi.listFolder).not.toHaveBeenCalled();
    });

    it("does not reload a folder's content when it is expanded after a tick", async () => {
      mockApi.listFolder.mockImplementation(
        async (req: { folderId?: string }) => {
          if (req.folderId === "folder-1") {
            return [mockNestedChain];
          }
          return [mockFolder];
        },
      );
      render(<Chains />);
      await screen.findByText("Test Folder");

      fireEvent.click(folderCheckbox("folder-1"));
      await waitFor(() => expect(deployButton()).toBeEnabled());
      mockApi.listFolder.mockClear();

      const folderRow = folderCheckbox("folder-1").closest("tr")!;
      fireEvent.click(
        folderRow.querySelector('button[aria-label="Expand row"]')!,
      );

      expect(await screen.findByText("Nested Chain")).toBeInTheDocument();
      expect(mockApi.listFolder).not.toHaveBeenCalled();
    });
  });
});
