/**
 * @jest-environment jsdom
 */
import { act, render, screen, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom";
import { RestApiError } from "../../src/api/apiTypes";
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
};

let mockChain: Chain = { ...baseChain };
let mockIsVsCode = false;
const mockGetChain = jest.fn();
const mockSetChain = jest.fn();

jest.mock("../../src/hooks/useChain", () => ({
  useChain: () => ({
    chain: mockChain,
    setChain: mockSetChain,
    updateChain: jest.fn().mockResolvedValue(undefined),
    getChain: mockGetChain,
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

const mockTestingAvailability = { isAvailable: false, isLoading: false };
jest.mock("../../src/hooks/useTestingServiceAvailability", () => ({
  useTestingServiceAvailability: () => mockTestingAvailability,
}));

const mockRequestFailed = jest.fn();
jest.mock("../../src/hooks/useNotificationService", () => ({
  useNotificationService: () => ({
    requestFailed: mockRequestFailed,
  }),
}));

describe("ChainPage", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockIsVsCode = false;
    mockChain = { ...baseChain, unsavedChanges: false };
    mockGetChain.mockResolvedValue(mockChain);
    mockTestingAvailability.isAvailable = false;
    mockTestingAvailability.isLoading = false;
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

  it("shows the Testing tab when the testing service is available", () => {
    mockTestingAvailability.isAvailable = true;
    render(<ChainPage />);
    expect(screen.getByText("Testing")).toBeInTheDocument();
  });

  it("hides the Testing tab when the testing service is unavailable", () => {
    render(<ChainPage />);
    expect(screen.queryByText("Testing")).not.toBeInTheDocument();
  });

  it("hides Unsaved changes tag in VS Code even when unsavedChanges is true", () => {
    mockIsVsCode = true;
    mockChain = { ...baseChain, unsavedChanges: true };
    render(<ChainPage />);
    expect(
      screen.queryByTestId("chain-unsaved-changes"),
    ).not.toBeInTheDocument();
  });

  it("should reload the open chain when Rocky reports a catalog write", async () => {
    render(<ChainPage />);
    await act(async () => {
      window.dispatchEvent(
        new CustomEvent("qip-catalog-changed", {
          detail: { chainIds: ["chain-1"], chainsListChanged: true },
        }),
      );
    });
    await waitFor(() => expect(mockGetChain).toHaveBeenCalledTimes(1));
    expect(mockSetChain).toHaveBeenCalledWith(mockChain);
  });
  it("ignores writes to other chains", async () => {
    render(<ChainPage />);
    await act(async () => {
      window.dispatchEvent(
        new CustomEvent("qip-catalog-changed", {
          detail: { chainIds: ["other"], chainsListChanged: true },
        }),
      );
    });
    expect(mockGetChain).not.toHaveBeenCalled();
  });

  it("preserves the graph on a read failure and offers a read retry", async () => {
    mockGetChain.mockRejectedValueOnce(new Error("offline"));
    render(<ChainPage />);
    await act(async () => {
      window.dispatchEvent(
        new CustomEvent("qip-catalog-changed", {
          detail: { chainIds: null, chainsListChanged: true },
        }),
      );
    });
    await waitFor(() => expect(mockRequestFailed).toHaveBeenCalled());
    expect(screen.getByTestId("outlet")).toBeInTheDocument();
    expect(mockSetChain).not.toHaveBeenCalled();
    await act(async () => {
      mockRequestFailed.mock.calls[0][2]();
    });
    await waitFor(() => expect(mockGetChain).toHaveBeenCalledTimes(2));
  });

  it("replaces a deleted graph with a navigation action", async () => {
    mockGetChain.mockRejectedValueOnce(new RestApiError("missing", 404));
    render(<ChainPage />);
    await act(async () => {
      window.dispatchEvent(
        new CustomEvent("qip-catalog-changed", {
          detail: { chainIds: ["chain-1"], chainsListChanged: true },
        }),
      );
    });
    await waitFor(() =>
      expect(screen.getByText("Chain deleted")).toBeInTheDocument(),
    );
    expect(mockRequestFailed).not.toHaveBeenCalled();
  });
});
