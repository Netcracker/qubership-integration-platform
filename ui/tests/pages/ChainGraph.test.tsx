/**
 * @jest-environment jsdom
 */
import React from "react";
import { render, screen, fireEvent, within } from "@testing-library/react";
import "@testing-library/jest-dom";

jest.mock("@xyflow/react/dist/style.css", () => ({}));
jest.mock("../../src/styles/reactflow-theme.css", () => ({}));

jest.mock("../../src/api/api.ts", () => {
  const actual = jest.requireActual<typeof import("../../src/api/api.ts")>(
    "../../src/api/api.ts",
  );
  return {
    api: {
      ...actual.api,
      createSnapshot: jest.fn(),
      createDeployment: jest.fn(),
    },
  };
});

jest.mock(
  "../../src/components/modal/chain_element/ChainElementModification",
  () => ({
    ChainElementModification: () => null,
  }),
);

jest.mock("../../src/components/modal/SequenceDiagram", () => ({
  SequenceDiagram: () => null,
}));

jest.mock("../../src/components/modal/SaveAndDeploy", () => ({
  SaveAndDeploy: () => null,
}));

jest.mock("../../src/components/modal/ExportChains", () => ({
  ExportChains: () => null,
}));

import { api } from "../../src/api/api.ts";
import { ChainContext } from "../../src/pages/ChainPage.tsx";
import { ChainGraph } from "../../src/pages/ChainGraph";

Object.defineProperty(globalThis, "matchMedia", {
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

const mockShowModal = jest.fn();
const mockRequestFailed = jest.fn();
const mockInfo = jest.fn();
const mockNavigate = jest.fn();
let mockElementId: string | undefined;
const mockReactFlow = jest.fn();

jest.mock("../../src/Modals", () => ({
  useModalsContext: () => ({ showModal: mockShowModal }),
}));

jest.mock("react-router-dom", () => ({
  useParams: () => ({ chainId: "chain-1", elementId: mockElementId }),
  useNavigate: () => mockNavigate,
}));

jest.mock("react-router", () => ({
  useNavigate: () => mockNavigate,
}));

jest.mock("../../src/hooks/useNotificationService", () => ({
  useNotificationService: () => ({
    requestFailed: mockRequestFailed,
    info: mockInfo,
  }),
}));

jest.mock("../../src/hooks/graph/useChainGraph", () => ({
  useChainGraph: () => ({
    nodes: [],
    setNodes: jest.fn(),
    edges: [],
    setEdges: jest.fn(),
    decorativeEdges: [],
    onConnect: jest.fn(),
    onDragOver: jest.fn(),
    onDrop: jest.fn(),
    onDelete: jest.fn(),
    onEdgesChange: jest.fn(),
    onNodesChange: jest.fn(),
    onNodeDragStart: jest.fn(),
    onNodeDrag: jest.fn(),
    onNodeDragStop: jest.fn(),
    direction: "RIGHT",
    toggleDirection: jest.fn(),
    updateNodeData: jest.fn(),
    isLoading: false,
    expandAllContainers: jest.fn(),
    collapseAllContainers: jest.fn(),
    structureChanged: jest.fn(),
  }),
}));

jest.mock("../../src/hooks/graph/useContextMenu", () => ({
  useContextMenu: () => ({
    menu: null,
    closeMenu: jest.fn(),
    onContextMenuCall: jest.fn(),
  }),
}));

let mockLeftPanel = true;
let mockRightPanel = false;
jest.mock("../../src/hooks/graph/useElkDirection", () => ({
  useElkDirection: () => ({
    leftPanel: mockLeftPanel,
    toggleLeftPanel: jest.fn(),
    rightPanel: mockRightPanel,
    toggleRightPanel: jest.fn(),
  }),
}));

jest.mock("../../src/components/LibraryContext", () => ({
  LibraryProvider: ({ children }: { children: React.ReactNode }) => (
    <>{children}</>
  ),
  useLibraryContext: () => ({
    isLibraryLoading: false,
    libraryElements: [{ name: "script", title: "Script", type: "script" }],
  }),
}));

jest.mock("../../src/permissions/usePermissions", () => ({
  usePermissions: () => ({ chain: ["read", "update"] }),
}));

jest.mock("../../src/permissions/funcs", () => ({
  hasPermissions: () => true,
}));

jest.mock("../../src/api/rest/vscodeExtensionApi", () => ({
  isVsCode: false,
}));

jest.mock("../../src/pages/ChainHeaderActionsContext", () => {
  const R = require("react") as typeof import("react");
  const ReactDOM =
    require("react-dom/client") as typeof import("react-dom/client");
  return {
    useRegisterChainHeaderActions: (actions: R.ReactNode, deps: unknown[]) => {
      R.useEffect(() => {
        const container = document.createElement("div");
        container.dataset.testid = "chain-header-actions-slot";
        document.body.appendChild(container);
        const root = ReactDOM.createRoot(container);
        root.render(actions);
        return () => {
          queueMicrotask(() => {
            root.unmount();
            container.remove();
          });
        };
      }, deps);
    },
  };
});

jest.mock("@xyflow/react", () => ({
  ReactFlowProvider: ({ children }: { children: React.ReactNode }) => (
    <>{children}</>
  ),
  ReactFlow: (props: Record<string, unknown>) => {
    mockReactFlow(props);
    return <div data-testid="react-flow" />;
  },
  Background: () => null,
  BackgroundVariant: { Dots: "dots" },
  MiniMap: () => null,
  useOnSelectionChange: jest.fn(),
}));

jest.mock("../../src/components/DndContext", () => ({
  DnDProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));

jest.mock(
  "../../src/components/elements_library/ElementsLibrarySidebar",
  () => ({
    ElementsLibrarySidebar: () => (
      <div data-testid="elements-library-sidebar" />
    ),
  }),
);

jest.mock("../../src/components/PanelResizeHandle", () => ({
  PanelResizeHandle: () => <div data-testid="panel-resize-handle" />,
}));

jest.mock("../../src/components/graph/CustomControls", () => ({
  CustomControls: () => null,
}));

jest.mock("../../src/components/graph/ElementFocus", () => ({
  ElementFocus: () => null,
  ElementFocusContext: {
    Provider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  },
}));

jest.mock("../../src/pages/ElkDirectionContext", () => ({
  ElkDirectionContextProvider: ({
    children,
  }: {
    children: React.ReactNode;
  }) => <>{children}</>,
}));

jest.mock("../../src/pages/ChainFullscreenContext", () => ({
  ChainFullscreenContextProvider: ({
    children,
  }: {
    children: React.ReactNode;
  }) => <>{children}</>,
  useChainFullscreenContext: () => ({
    fullscreen: false,
    toggleFullscreen: jest.fn(),
  }),
}));

jest.mock("../../src/pages/PageWithRightPanel", () => ({
  PageWithRightPanel: () => <div data-testid="page-with-right-panel" />,
}));

jest.mock("../../src/permissions/Require", () => ({
  Require: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));

jest.mock("../../src/permissions/ProtectedButton", () => ({
  ProtectedButton: ({
    children,
    buttonProps,
  }: {
    children?: React.ReactNode;
    buttonProps?: { onClick?: () => void };
  }) => (
    <button
      data-testid="protected-button"
      onClick={buttonProps?.onClick}
      type="button"
    >
      {children}
    </button>
  ),
}));

jest.mock("../../src/components/graph/ContextMenu", () => ({
  __esModule: true,
  default: () => null,
}));

jest.mock("../../src/pages/ChainPage", () => ({
  ChainContext: React.createContext(undefined),
}));

describe("ChainGraph", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockLeftPanel = true;
    mockRightPanel = false;
    mockElementId = undefined;
    document.body.dataset.theme = "light";
  });

  it("renders without crashing", () => {
    render(<ChainGraph />);
    expect(screen.getByTestId("react-flow")).toBeInTheDocument();
  });

  it("enables delete hotkeys on graph when element modal is closed", () => {
    render(<ChainGraph />);

    expect(mockReactFlow).toHaveBeenCalled();
    const lastProps = mockReactFlow.mock.calls.at(-1)?.[0] as {
      deleteKeyCode?: string[];
    };
    expect(lastProps.deleteKeyCode).toEqual(["Backspace", "Delete"]);
  });

  it("disables delete hotkeys on graph when element modal is open", () => {
    mockElementId = "element-1";

    render(<ChainGraph />);

    expect(mockReactFlow).toHaveBeenCalled();
    const lastProps = mockReactFlow.mock.calls.at(-1)?.[0] as {
      deleteKeyCode?: string[] | null;
    };
    expect(lastProps.deleteKeyCode).toBeNull();
  });

  it("renders ElementsLibrarySidebar", () => {
    render(<ChainGraph />);
    expect(screen.getByTestId("elements-library-sidebar")).toBeInTheDocument();
  });

  it("renders PanelResizeHandle", () => {
    render(<ChainGraph />);
    expect(screen.getByTestId("panel-resize-handle")).toBeInTheDocument();
  });

  it("renders PageWithRightPanel when rightPanel is true", () => {
    mockRightPanel = true;
    render(<ChainGraph />);
    expect(screen.getByTestId("page-with-right-panel")).toBeInTheDocument();
  });

  it("hides ElementsLibrarySidebar when leftPanel is false", () => {
    mockLeftPanel = false;
    render(<ChainGraph />);
    expect(
      screen.queryByTestId("elements-library-sidebar"),
    ).not.toBeInTheDocument();
  });

  it("save and deploy creates snapshot, deploys, refreshes chain context", async () => {
    const refreshMock = jest.fn().mockResolvedValue(undefined);
    const createSnapshot = api.createSnapshot as jest.Mock;
    const createDeployment = api.createDeployment as jest.Mock;
    createSnapshot.mockResolvedValue({ id: "snap-1", name: "snap-a" });
    createDeployment.mockResolvedValue(undefined);

    render(
      <ChainContext.Provider
        value={{
          chain: undefined,
          refresh: refreshMock,
          update: jest.fn().mockResolvedValue(undefined),
        }}
      >
        <ChainGraph />
      </ChainContext.Provider>,
    );

    const headerSlot = await screen.findByTestId("chain-header-actions-slot");
    const headerButtons = within(headerSlot).getAllByTestId("protected-button");
    expect(headerButtons.length).toBeGreaterThanOrEqual(3);
    fireEvent.click(headerButtons[2]);

    expect(mockShowModal).toHaveBeenCalled();
    const modalArg = mockShowModal.mock.calls.at(-1)?.[0] as {
      component: React.ReactElement<{
        onSubmit: (domain: string) => void | Promise<void>;
      }>;
    };
    expect(modalArg?.component?.props?.onSubmit).toEqual(expect.any(Function));
    await modalArg.component.props.onSubmit("domain-1");

    expect(createSnapshot).toHaveBeenCalledWith("chain-1");
    expect(createDeployment).toHaveBeenCalledWith(
      "chain-1",
      expect.objectContaining({
        domain: "domain-1",
        snapshotId: "snap-1",
        suspended: false,
      }),
    );
    expect(refreshMock).toHaveBeenCalled();
    expect(mockInfo).toHaveBeenCalled();
  });

  it("save and deploy refreshes chain context even when deployment fails", async () => {
    const refreshMock = jest.fn().mockResolvedValue(undefined);
    const createSnapshot = api.createSnapshot as jest.Mock;
    const createDeployment = api.createDeployment as jest.Mock;
    createSnapshot.mockResolvedValue({ id: "snap-1", name: "snap-a" });
    const deployError = new Error("deploy failed");
    createDeployment.mockRejectedValue(deployError);

    render(
      <ChainContext.Provider
        value={{
          chain: undefined,
          refresh: refreshMock,
          update: jest.fn().mockResolvedValue(undefined),
        }}
      >
        <ChainGraph />
      </ChainContext.Provider>,
    );

    const headerSlot = await screen.findByTestId("chain-header-actions-slot");
    const headerButtons = within(headerSlot).getAllByTestId("protected-button");
    fireEvent.click(headerButtons[2]);

    const modalArg = mockShowModal.mock.calls.at(-1)?.[0] as {
      component: React.ReactElement<{
        onSubmit: (domain: string) => void | Promise<void>;
      }>;
    };
    await modalArg.component.props.onSubmit("domain-1");

    expect(createSnapshot).toHaveBeenCalledWith("chain-1");
    expect(createDeployment).toHaveBeenCalled();
    expect(refreshMock).toHaveBeenCalled();
    const refreshCallOrder = refreshMock.mock.invocationCallOrder[0];
    const deployCallOrder = createDeployment.mock.invocationCallOrder[0];
    expect(refreshCallOrder).toBeLessThan(deployCallOrder);
    expect(mockRequestFailed).toHaveBeenCalledWith(
      expect.stringContaining("Failed to create snapshot and deploy it"),
      deployError,
    );
  });
});
