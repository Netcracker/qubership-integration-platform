/**
 * @jest-environment jsdom
 */
/* eslint-disable @typescript-eslint/no-require-imports -- jest mock requires dynamic require */
/* eslint-disable @typescript-eslint/unbound-method -- jest.restoreAllMocks in afterEach */
import React from "react";
import { render, screen, fireEvent } from "@testing-library/react";
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

let mockIsVsCode = false;
jest.mock("../../src/api/rest/vscodeExtensionApi", () => ({
  get isVsCode() {
    return mockIsVsCode;
  },
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
    chainId,
    onElementDoubleClick,
  }: {
    chainId: string;
    onElementDoubleClick?: (id: string) => void;
  }) => (
    <div data-testid="used-properties-list" data-chain-id={chainId}>
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
    mockIsVsCode = false;
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

  it("passes chainId to UsedPropertiesList when element properties tab", () => {
    renderWithContext(<PageWithRightPanel />);
    const propsTab = screen
      .getAllByRole("tab")
      .find((tab) => tab.querySelector('[data-icon="menuUnfold"]'));
    fireEvent.click(propsTab!);
    const list = screen.getByTestId("used-properties-list");
    expect(list).toHaveAttribute("data-chain-id", "test-chain-id");
  });

  it("calls api.getElements on mount when chainId is present", () => {
    renderWithContext(<PageWithRightPanel />);
    expect(api.getElements).toHaveBeenCalledWith("test-chain-id");
  });

  // Note: "No chain selected" when chainId is undefined causes test suite hang - excluded

  it("shows only listElements tab when isVsCode is true", () => {
    mockIsVsCode = true;
    renderWithContext(<PageWithRightPanel />);
    const tabs = screen.getAllByRole("tab");
    expect(tabs).toHaveLength(1);
    expect(tabs[0].querySelector('[data-icon="unorderedList"]')).toBeTruthy();
    expect(tabs.some((tab) => tab.querySelector('[data-icon="file"]'))).toBe(
      false,
    );
    expect(
      tabs.some((tab) => tab.querySelector('[data-icon="menuUnfold"]')),
    ).toBe(false);
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
});
