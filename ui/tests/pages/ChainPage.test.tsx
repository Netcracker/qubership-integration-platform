/**
 * @jest-environment jsdom
 */
import React from "react";
import { render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";
import type { Chain } from "../../src/api/apiTypes";
import ChainPage from "../../src/pages/ChainPage";

const mockNavigate = jest.fn();

jest.mock("react-router", () => ({
  useParams: () => ({ chainId: "chain-1", sessionId: undefined }),
  useNavigate: () => mockNavigate,
  useLocation: () => ({ pathname: "/chains/chain-1/graph" }),
  Outlet: () => <div data-testid="outlet" />,
}));

const baseChain: Chain = {
  id: "chain-1",
  name: "Test Chain",
  description: "",
  navigationPath: [["folder-1", "Folder"]],
  elements: [],
  dependencies: [],
  deployments: [],
  labels: [],
  defaultSwimlaneId: "sw-1",
  reuseSwimlaneId: "sw-2",
  unsavedChanges: false,
  businessDescription: "",
  assumptions: "",
  outOfScope: "",
  containsDeprecatedContainers: false,
  containsDeprecatedElements: false,
  containsUnsupportedElements: false,
  overallStatus: {},
};

let mockChain: Chain = { ...baseChain };
let mockIsVsCode = false;

jest.mock("../../src/hooks/useChain", () => ({
  useChain: () => ({
    chain: mockChain,
    setChain: jest.fn(),
    updateChain: jest.fn().mockResolvedValue(undefined),
    getChain: jest.fn().mockResolvedValue(mockChain),
    isLoading: false,
    error: null,
  }),
}));

jest.mock("../../src/api/rest/vscodeExtensionApi", () => ({
  get isVsCode() {
    return mockIsVsCode;
  },
}));

jest.mock("../../src/icons/IconProvider", () => ({
  OverridableIcon: ({ name }: { name: string }) => (
    <span data-testid="icon" data-icon={name} />
  ),
}));

describe("ChainPage", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockIsVsCode = false;
    mockChain = { ...baseChain, unsavedChanges: false };
  });

  it("shows Unsaved changes tag in web when unsavedChanges is true", () => {
    mockChain = { ...baseChain, unsavedChanges: true };
    render(<ChainPage />);
    expect(screen.getByTestId("chain-unsaved-changes")).toBeInTheDocument();
    expect(screen.getByText("Unsaved changes")).toBeInTheDocument();
  });

  it("hides Unsaved changes tag in web when unsavedChanges is false", () => {
    mockChain = { ...baseChain, unsavedChanges: false };
    render(<ChainPage />);
    expect(
      screen.queryByTestId("chain-unsaved-changes"),
    ).not.toBeInTheDocument();
  });

  it("hides Unsaved changes tag in VS Code even when unsavedChanges is true", () => {
    mockIsVsCode = true;
    mockChain = { ...baseChain, unsavedChanges: true };
    render(<ChainPage />);
    expect(
      screen.queryByTestId("chain-unsaved-changes"),
    ).not.toBeInTheDocument();
  });
});
