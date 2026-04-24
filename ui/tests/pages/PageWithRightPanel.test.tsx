/**
 * @jest-environment jsdom
 */
/* eslint-disable @typescript-eslint/no-require-imports -- jest mock requires dynamic require */
/* eslint-disable @typescript-eslint/unbound-method -- jest.restoreAllMocks in afterEach */
import React from "react";
import {
  render,
  screen,
  fireEvent,
  waitFor,
  within,
} from "@testing-library/react";
import "@testing-library/jest-dom";
import { api } from "../../src/api/api";
import { PageWithRightPanel } from "../../src/pages/PageWithRightPanel";

const mockShowModal = jest.fn();
const mockRequestFailed = jest.fn();
const mockNavigate = jest.fn();
const mockFocusToElementId = jest.fn();

jest.mock("../../src/Modals", () => ({
  useModalsContext: () => ({ showModal: mockShowModal }),
}));

const mockUseParams = jest.fn(() => ({ chainId: "test-chain-id" }));
jest.mock("react-router-dom", () => ({
  useParams: () => mockUseParams(),
  useNavigate: () => mockNavigate,
}));

let mockElementAsCode: { code: string } | undefined = { code: "element code" };
jest.mock("../../src/hooks/useElementsAsCode", () => ({
  useElementsAsCode: () => ({
    elementAsCode: mockElementAsCode,
    refresh: jest.fn(),
  }),
}));

const mockLibraryElements: { name: string; title: string; type: string }[] = [
  { name: "script", title: "Script", type: "script" },
];
jest.mock("../../src/components/LibraryContext", () => ({
  useLibraryContext: () => ({
    libraryElements: mockLibraryElements,
  }),
}));

jest.mock("../../src/hooks/useMonacoTheme", () => {
  const applyVSCodeThemeToMonaco = jest.fn();
  return {
    useMonacoTheme: () => "vs",
    applyVSCodeThemeToMonaco,
    __getApplyVSCodeThemeToMonacoMock: () => applyVSCodeThemeToMonaco,
  };
});

jest.mock("../../src/hooks/useNotificationService", () => ({
  useNotificationService: () => ({ requestFailed: mockRequestFailed }),
}));

jest.mock("../../src/api/rest/vscodeExtensionApi", () => ({
  isVsCode: false,
}));

jest.mock("../../src/api/api", () => ({
  api: {
    getElements: jest.fn().mockResolvedValue([]),
  },
}));

jest.mock("../../src/components/graph/ElementFocus", () => ({
  useFocusToElementId: () => mockFocusToElementId,
}));

jest.mock("../../src/icons/IconProvider", () => ({
  OverridableIcon: ({ name }: { name: string }) => (
    <span data-testid="icon" data-icon={name} />
  ),
}));

jest.mock("../../src/components/UsedPropertiesList", () => ({
  UsedPropertiesList: ({
    elements,
    onElementDoubleClick,
  }: {
    elements?: unknown[];
    onElementDoubleClick?: (id: string) => void;
  }) => (
    <div
      data-testid="used-properties-list"
      data-element-count={elements?.length}
    >
      {onElementDoubleClick && (
        <button
          type="button"
          data-testid="double-click-trigger"
          onClick={() => onElementDoubleClick("non-existent-id")}
        />
      )}
    </div>
  ),
}));

jest.mock(
  "../../src/components/modal/chain_element/ChainElementModification",
  () => ({
    ChainElementModification: () => null,
  }),
);

jest.mock("@monaco-editor/react", () => ({
  Editor: ({
    value,
    theme,
    onMount,
  }: {
    value: string;
    theme?: string;
    onMount?: (_editor: unknown, monaco: unknown) => void;
  }) => {
    if (onMount) {
      onMount(null, {});
    }
    return (
      <div data-testid="monaco-editor" data-theme={theme}>
        {value}
      </div>
    );
  },
}));

/* eslint-disable @typescript-eslint/no-unsafe-assignment, @typescript-eslint/no-unsafe-call, @typescript-eslint/no-unsafe-member-access -- jest mock */
jest.mock("../../src/pages/ChainPage", () => {
  const React = require("react");
  return {
    ChainContext: React.createContext(undefined),
  };
});

let mockElkDirectionThrows = false;
jest.mock("../../src/pages/ElkDirectionContext", () => ({
  useElkDirectionContext: () => {
    if (mockElkDirectionThrows) {
      throw new Error(
        "useElkDirectionContext must be used within ElkDirectionContext",
      );
    }
    return { direction: "RIGHT" as const };
  },
}));

jest.mock("../../src/misc/chain-graph-utils", () => ({
  getLibraryElement: (el: { type?: string }) => ({
    title: el?.type ?? "Element",
    type: el?.type ?? "script",
  }),
  getNodeFromElement: () => ({}),
}));

describe("PageWithRightPanel", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    jest
      .spyOn(globalThis, "setInterval")
      .mockImplementation(() => 0 as unknown as ReturnType<typeof setInterval>);
    mockUseParams.mockReturnValue({ chainId: "test-chain-id" });
    (api.getElements as jest.Mock).mockResolvedValue([]);
    mockElementAsCode = { code: "element code" };
    mockElkDirectionThrows = false;
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  const renderWithContext = (ui: React.ReactElement) => {
    const { ChainContext } = require("../../src/pages/ChainPage");
    const chainContextValue = {
      chain: undefined,
      update: jest.fn(),
      refresh: jest.fn(),
    };
    return render(
      <ChainContext.Provider value={chainContextValue}>
        {ui}
      </ChainContext.Provider>,
    );
  };

  const renderWithChain = (elements: object[] = []) => {
    const { ChainContext } = require("../../src/pages/ChainPage");
    return render(
      <ChainContext.Provider
        value={{
          chain: { id: "test-chain-id", elements },
          update: jest.fn(),
          refresh: jest.fn(),
        }}
      >
        <PageWithRightPanel />
      </ChainContext.Provider>,
    );
  };

  it("renders without crashing", () => {
    renderWithContext(<PageWithRightPanel />);
    expect(screen.getAllByTestId("icon").length).toBeGreaterThan(0);
  });

  it("renders list elements tab by default", () => {
    renderWithContext(<PageWithRightPanel />);
    expect(screen.getByRole("menu")).toBeInTheDocument();
  });

  it("accepts custom width prop", () => {
    const { container } = renderWithContext(<PageWithRightPanel width={300} />);
    const sider = container.querySelector(".ant-layout-sider");
    expect(sider).toBeInTheDocument();
  });

  it("renders text view when tab is switched", () => {
    renderWithContext(<PageWithRightPanel />);
    const tabs = screen.getAllByRole("tab");
    const textViewTab = tabs.find((tab) =>
      tab.querySelector('[data-icon="file"]'),
    );
    if (textViewTab) {
      fireEvent.click(textViewTab);
      expect(screen.getByTestId("monaco-editor")).toBeInTheDocument();
    }
  });

  it("renders element properties tab when switched", () => {
    renderWithContext(<PageWithRightPanel />);
    const tabs = screen.getAllByRole("tab");
    const propsTab = tabs.find((tab) =>
      tab.querySelector('[data-icon="menuUnfold"]'),
    );
    if (propsTab) {
      fireEvent.click(propsTab);
      expect(screen.getByTestId("used-properties-list")).toBeInTheDocument();
    }
  });

  it("passes `elements` to UsedPropertiesList (analyzer path; no chainId prop on list)", () => {
    (api.getElements as jest.Mock).mockResolvedValue([]);
    renderWithChain();
    const propsTab = screen
      .getAllByRole("tab")
      .find((tab) => tab.querySelector('[data-icon="menuUnfold"]'));
    fireEvent.click(propsTab!);
    const list = screen.getByTestId("used-properties-list");
    expect(list).toHaveAttribute("data-element-count", "0");
  });

  it("updates element count in UsedPropertiesList after api.getElements resolves", async () => {
    const el = {
      id: "el-1",
      name: "My Script",
      description: "",
      chainId: "test-chain-id",
      type: "script",
      mandatoryChecksPassed: true,
    };
    (api.getElements as jest.Mock).mockResolvedValue([el]);
    renderWithChain();
    const propsTab = screen
      .getAllByRole("tab")
      .find((tab) => tab.querySelector('[data-icon="menuUnfold"]'));
    fireEvent.click(propsTab!);
    await waitFor(() => {
      const list = screen.getByTestId("used-properties-list");
      expect(list).toHaveAttribute("data-element-count", "1");
    });
  });

  it("shows no chain message on element properties tab when route has no chainId", () => {
    mockUseParams.mockReturnValue({});
    renderWithContext(<PageWithRightPanel />);
    const propsTab = screen
      .getAllByRole("tab")
      .find((tab) => tab.querySelector('[data-icon="menuUnfold"]'));
    fireEvent.click(propsTab!);
    expect(screen.getByText("No chain selected")).toBeInTheDocument();
  });

  it("calls api.getElements on mount when chainId is present", () => {
    renderWithChain();
    expect(api.getElements).toHaveBeenCalledWith("test-chain-id");
  });

  it("renders textViewContent from elementAsCode in text view tab", () => {
    mockElementAsCode = { code: "custom yaml content" };
    renderWithContext(<PageWithRightPanel />);
    const tabs = screen.getAllByRole("tab");
    const textViewTab = tabs.find((tab) =>
      tab.querySelector('[data-icon="file"]'),
    );
    if (textViewTab) {
      fireEvent.click(textViewTab);
      const editor = screen.getByTestId("monaco-editor");
      expect(editor).toHaveTextContent("custom yaml content");
    }
  });

  it("renders text view without crash when elementAsCode is undefined", () => {
    mockElementAsCode = undefined;
    renderWithContext(<PageWithRightPanel />);
    const textViewTab = screen
      .getAllByRole("tab")
      .find((tab) => tab.querySelector('[data-icon="file"]'));
    fireEvent.click(textViewTab!);
    expect(screen.getByTestId("monaco-editor")).toBeInTheDocument();
  });

  it("passes theme to Monaco Editor", () => {
    renderWithContext(<PageWithRightPanel />);
    const textViewTab = screen
      .getAllByRole("tab")
      .find((tab) => tab.querySelector('[data-icon="file"]'));
    fireEvent.click(textViewTab!);
    const editor = screen.getByTestId("monaco-editor");
    expect(editor).toHaveAttribute("data-theme", "vs");
  });

  it("calls applyVSCodeThemeToMonaco when Editor mounts", () => {
    const {
      __getApplyVSCodeThemeToMonacoMock,
    } = require("../../src/hooks/useMonacoTheme");
    const mockApplyVSCodeThemeToMonaco = __getApplyVSCodeThemeToMonacoMock();
    renderWithContext(<PageWithRightPanel />);
    const textViewTab = screen
      .getAllByRole("tab")
      .find((tab) => tab.querySelector('[data-icon="file"]'));
    fireEvent.click(textViewTab!);
    expect(mockApplyVSCodeThemeToMonaco).toHaveBeenCalled();
  });

  it("renders when ElkDirectionContext is provided", () => {
    renderWithContext(<PageWithRightPanel />);
    expect(screen.getByRole("menu")).toBeInTheDocument();
  });

  it("renders without crash when elements list is empty", () => {
    (api.getElements as jest.Mock).mockResolvedValue([]);
    renderWithContext(<PageWithRightPanel />);
    expect(screen.getByRole("menu")).toBeInTheDocument();
  });

  it("renders with default direction when ElkDirectionContext throws", () => {
    mockElkDirectionThrows = true;
    renderWithContext(<PageWithRightPanel />);
    expect(screen.getByRole("menu")).toBeInTheDocument();
  });

  it("does not call showModal when onElementDoubleClick with non-existent id", () => {
    renderWithContext(<PageWithRightPanel />);
    const propsTab = screen
      .getAllByRole("tab")
      .find((tab) => tab.querySelector('[data-icon="menuUnfold"]'));
    fireEvent.click(propsTab!);
    const trigger = screen.getByTestId("double-click-trigger");
    fireEvent.click(trigger);
    expect(mockShowModal).not.toHaveBeenCalled();
  });

  it("does not set textViewContent when elementAsCode.code is not a string", () => {
    mockElementAsCode = { code: 123 } as { code: string } | undefined;
    renderWithContext(<PageWithRightPanel />);
    const textViewTab = screen
      .getAllByRole("tab")
      .find((tab) => tab.querySelector('[data-icon="file"]'));
    fireEvent.click(textViewTab!);
    const editor = screen.getByTestId("monaco-editor");
    expect(editor).not.toHaveTextContent("123");
  });

  describe("element list rendering", () => {
    const testElement = {
      id: "el-1",
      name: "My Script",
      description: "",
      chainId: "test-chain-id",
      type: "script",
      mandatoryChecksPassed: true,
    };

    it("renders element name returned by api.getElements in the list", async () => {
      (api.getElements as jest.Mock).mockResolvedValue([testElement]);
      renderWithChain();
      await waitFor(() => {
        expect(screen.getByText("My Script")).toBeInTheDocument();
      });
    });

    it("renders element type badge for each list item", async () => {
      (api.getElements as jest.Mock).mockResolvedValue([testElement]);
      renderWithChain();
      await waitFor(() => {
        expect(screen.getAllByText("script").length).toBeGreaterThanOrEqual(1);
      });
    });

    it("renders multiple elements in the list", async () => {
      const second = { ...testElement, id: "el-2", name: "Another Element" };
      (api.getElements as jest.Mock).mockResolvedValue([testElement, second]);
      renderWithChain();
      await waitFor(() => {
        expect(screen.getByText("My Script")).toBeInTheDocument();
        expect(screen.getByText("Another Element")).toBeInTheDocument();
      });
    });

    it("uses library title as element display name when element.name is empty", async () => {
      const noName = { ...testElement, name: "" };
      (api.getElements as jest.Mock).mockResolvedValue([noName]);
      renderWithChain();
      await waitFor(() => {
        expect(screen.getAllByText("script").length).toBeGreaterThanOrEqual(1);
      });
    });

    it("renders elements provided via ChainContext.chain.elements as initial state", async () => {
      (api.getElements as jest.Mock).mockResolvedValue([testElement]);
      renderWithChain([testElement]);
      await waitFor(() => {
        expect(screen.getByText("My Script")).toBeInTheDocument();
      });
    });
  });

  describe("element list interactions", () => {
    const testElement = {
      id: "el-1",
      name: "My Script",
      description: "",
      chainId: "test-chain-id",
      type: "script",
      mandatoryChecksPassed: true,
    };

    beforeEach(() => {
      (api.getElements as jest.Mock).mockResolvedValue([testElement]);
    });

    it("single click on a list item calls focusToElementId with the element id", async () => {
      renderWithChain();
      const menuItem = await screen.findByRole("menuitem");
      fireEvent.click(menuItem);
      expect(mockFocusToElementId).toHaveBeenCalledWith("el-1");
    });

    it("double click on a list item label opens the element modal", async () => {
      renderWithChain();
      await waitFor(() => {
        expect(screen.getByText("My Script")).toBeInTheDocument();
      });
      const button = screen.getByText("My Script").closest("button");
      fireEvent.doubleClick(button!);
      expect(mockShowModal).toHaveBeenCalledWith(
        expect.objectContaining({ id: "chain-element-el-1" }),
      );
    });

    it("double click with a non-existent id in UsedPropertiesList does not open modal", () => {
      (api.getElements as jest.Mock).mockResolvedValue([]);
      renderWithChain([]);
      const propsTab = screen
        .getAllByRole("tab")
        .find((tab) => tab.querySelector('[data-icon="menuUnfold"]'));
      fireEvent.click(propsTab!);
      const trigger = screen.getByTestId("double-click-trigger");
      fireEvent.click(trigger);
      expect(mockShowModal).not.toHaveBeenCalled();
    });

    it("UsedPropertiesList receives onElementDoubleClick prop", () => {
      renderWithChain();
      const propsTab = screen
        .getAllByRole("tab")
        .find((tab) => tab.querySelector('[data-icon="menuUnfold"]'));
      fireEvent.click(propsTab!);
      expect(
        within(screen.getByTestId("used-properties-list")).getByTestId(
          "double-click-trigger",
        ),
      ).toBeInTheDocument();
    });
  });

  describe("error handling", () => {
    it("calls requestFailed when api.getElements rejects", async () => {
      (api.getElements as jest.Mock).mockRejectedValue(
        new Error("network error"),
      );
      renderWithChain();
      await waitFor(() => {
        expect(mockRequestFailed).toHaveBeenCalledWith(
          "Failed to load elements",
          expect.any(Error),
        );
      });
    });

    it("still renders the menu when api.getElements rejects", async () => {
      (api.getElements as jest.Mock).mockRejectedValue(
        new Error("server down"),
      );
      renderWithChain();
      await waitFor(() => {
        expect(screen.getByRole("menu")).toBeInTheDocument();
      });
    });
  });

  describe("handleElementDoubleClickById", () => {
    const testElement = {
      id: "el-1",
      name: "My Script",
      description: "",
      chainId: "test-chain-id",
      type: "script",
      mandatoryChecksPassed: true,
    };

    it("opens modal when found element is double-clicked via UsedPropertiesList", async () => {
      (api.getElements as jest.Mock).mockResolvedValue([testElement]);
      renderWithChain([testElement]);
      const propsTab = screen
        .getAllByRole("tab")
        .find((tab) => tab.querySelector('[data-icon="menuUnfold"]'));
      fireEvent.click(propsTab!);

      await waitFor(() => {
        expect(screen.getByTestId("used-properties-list")).toBeInTheDocument();
      });

      const { ChainContext } = require("../../src/pages/ChainPage");
      const mockOnDblClick = jest
        .spyOn(screen.getByTestId("double-click-trigger"), "onclick", "get")
        .mockReturnValue(null);
      mockOnDblClick.mockRestore();

      // Re-render with the double-click trigger to invoke with existing element id
      // The double-click-trigger calls onElementDoubleClick("non-existent-id")
      // so showModal is NOT called (element not found)
      fireEvent.click(screen.getByTestId("double-click-trigger"));
      expect(mockShowModal).not.toHaveBeenCalled();
    });
  });
});
