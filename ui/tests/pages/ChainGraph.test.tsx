/**
 * @jest-environment jsdom
 */
/* eslint-disable @typescript-eslint/no-require-imports -- jest mock requires dynamic require */
import React from "react";
import { render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";

jest.mock("@xyflow/react/dist/style.css", () => ({}));
jest.mock("../../src/styles/reactflow-theme.css", () => ({}));

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
const mockNavigate = jest.fn();

jest.mock("../../src/Modals", () => ({
  useModalsContext: () => ({ showModal: mockShowModal }),
}));

jest.mock("react-router-dom", () => ({
  useParams: () => ({ chainId: "chain-1", elementId: undefined }),
  useNavigate: () => mockNavigate,
}));

jest.mock("react-router", () => ({
  useNavigate: () => mockNavigate,
}));

jest.mock("../../src/hooks/useNotificationService", () => ({
  useNotificationService: () => ({ requestFailed: mockRequestFailed }),
}));

jest.mock("../../src/hooks/graph/useChainGraph", () => ({
  useChainGraph: () => ({
    nodes: [],
    edges: [],
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
    menu: null,
    closeMenu: jest.fn(),
    onContextMenuCall: jest.fn(),
    isLoading: false,
    expandAllContainers: jest.fn(),
    collapseAllContainers: jest.fn(),
  }),
}));

let mockRightPanel = false;
jest.mock("../../src/hooks/graph/useElkDirection", () => ({
  useElkDirection: () => ({
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

jest.mock("../../src/pages/ChainHeaderActionsContext", () => ({
  useRegisterChainHeaderActions: jest.fn(),
}));

jest.mock("@xyflow/react", () => ({
  ReactFlowProvider: ({ children }: { children: React.ReactNode }) => (
    <>{children}</>
  ),
  ReactFlow: () => <div data-testid="react-flow" />,
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

jest.mock("../../src/pages/ChainPage", () => {
  const React = require("react");
  return {
    ChainContext: React.createContext({
      chain: undefined,
      refresh: jest.fn(),
      update: jest.fn(),
    }),
  };
});

describe("ChainGraph", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    document.body.setAttribute("data-theme", "light");
  });

  it("renders without crashing", () => {
    render(<ChainGraph />);
    expect(screen.getByTestId("react-flow")).toBeInTheDocument();
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
    mockRightPanel = false;
  });
});
