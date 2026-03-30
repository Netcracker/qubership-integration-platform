/**
 * @jest-environment jsdom
 */

import React from "react";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import "@testing-library/jest-dom";
import { CatalogItemType, FolderItem, ChainItem } from "../../src/api/apiTypes";

// --------------- Mock data ---------------

const mockNavigate = jest.fn();
let mockSearchParams = new URLSearchParams();

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
  labels: [{ name: "env", value: "prod" }],
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

const mockRequestFailed = jest.fn();
jest.mock("../../src/hooks/useNotificationService", () => ({
  useNotificationService: () => ({
    requestFailed: mockRequestFailed,
    errorWithDetails: jest.fn(),
    info: jest.fn(),
    warning: jest.fn(),
  }),
}));

jest.mock("../../src/hooks/useChainFilter", () => ({
  useChainFilters: () => ({
    filters: [],
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
              onClick={item.onClick}
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
jest.mock("../../src/components/modal/GenerateDdsModal", () => ({
  GenerateDdsModal: () => <div data-testid="generate-dds-modal" />,
}));
jest.mock("../../src/components/modal/DdsPreview", () => ({
  DdsPreview: () => <div data-testid="dds-preview-modal" />,
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
    mockApi.listFolder.mockResolvedValue([]);
    mockApi.getPathToFolder.mockResolvedValue([]);
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

  it("displays folder icon for folders and file icon for chains", async () => {
    mockApi.listFolder.mockResolvedValue([mockFolder, mockChain]);
    render(<Chains />);
    await waitFor(() => {
      expect(screen.getByTestId("icon-folder")).toBeInTheDocument();
      expect(screen.getByTestId("icon-file")).toBeInTheDocument();
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
      screen.getByPlaceholderText("Full text search"),
    ).toBeInTheDocument();
  });

  it("renders toolbar ProtectedButtons by tooltip title", async () => {
    render(<Chains />);
    await waitFor(() => expect(mockApi.listFolder).toHaveBeenCalled());
    expect(screen.getByTestId("protected-btn-import-chains")).toBeInTheDocument();
    expect(screen.getByTestId("protected-btn-paste")).toBeInTheDocument();
    expect(
      screen.getByTestId("protected-btn-deploy-selected-chains"),
    ).toBeInTheDocument();
    expect(
      screen.getByTestId("protected-btn-export-selected-chains"),
    ).toBeInTheDocument();
    expect(
      screen.getByTestId(
        "protected-btn-delete-selected-chains-and-folders",
      ),
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
        "Failed to get folder content",
        expect.any(Error),
      );
    });
  });

  it("shows notification on getPathToFolder failure", async () => {
    mockSearchParams = new URLSearchParams("folder=folder-1");
    mockApi.getPathToFolder.mockRejectedValue(new Error("Not found"));
    render(<Chains />);
    await waitFor(() => {
      expect(mockRequestFailed).toHaveBeenCalledWith(
        "Failed to get path to folder",
        expect.any(Error),
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
});
