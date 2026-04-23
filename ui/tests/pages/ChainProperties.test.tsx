/**
 * @jest-environment jsdom
 */
import React from "react";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom";
import { ChainProperties } from "../../src/pages/ChainProperties";

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

type BlockerState = {
  state: "blocked" | "unblocked";
  proceed: () => void;
  reset: () => void;
};

const mockShowModal = jest.fn<void, [{ component: React.ReactElement }]>();
const mockRequestFailed = jest.fn<void, unknown[]>();
const mockProceed = jest.fn<void, []>();
const mockReset = jest.fn<void, []>();
const mockUseBlocker = jest.fn<BlockerState, []>();
const mockCloseContainingModal = jest.fn<void, []>();
const mockGetPathToFolderByName = jest.fn<Promise<unknown[]>, unknown[]>();
const mockMoveChain = jest.fn<Promise<void>, unknown[]>();
const mockChainUpdate = jest.fn<Promise<void>, unknown[]>();

jest.mock("../../src/api/api.ts", () => ({
  api: {
    getPathToFolderByName: (...args: unknown[]) =>
      mockGetPathToFolderByName(...args),
    moveChain: (...args: unknown[]) => mockMoveChain(...args),
  },
}));

jest.mock("../../src/Modals.tsx", () => ({
  useModalsContext: () => ({ showModal: mockShowModal }),
}));

jest.mock("../../src/hooks/useNotificationService.tsx", () => ({
  useNotificationService: () => ({
    requestFailed: mockRequestFailed,
  }),
}));

jest.mock("../../src/ModalContextProvider.tsx", () => ({
  useModalContext: () => ({
    closeContainingModal: mockCloseContainingModal,
  }),
}));

jest.mock("../../src/api/rest/vscodeExtensionApi.ts", () => ({
  isVsCode: false,
}));

jest.mock("react-router-dom", () => ({
  ...jest.requireActual<typeof import("react-router-dom")>("react-router-dom"),
  useBlocker: () => mockUseBlocker(),
}));

jest.mock("../../src/pages/ChainHeaderActionsContext.tsx", () => ({
  useRegisterChainHeaderActions: jest.fn(),
}));

jest.mock("../../src/components/ApplyFormButton.tsx", () => ({
  ApplyFormButton: () => <button type="button">Apply</button>,
}));

jest.mock("../../src/permissions/usePermissions.tsx", () => ({
  usePermissions: () => ({ chain: ["update"] }),
}));

jest.mock("../../src/permissions/funcs.ts", () => ({
  hasPermissions: () => true,
}));

jest.mock("../../src/permissions/Require.tsx", () => ({
  Require: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));

jest.mock("../../src/pages/ChainExtensionProperties.tsx", () => ({
  ChainExtensionProperties: () => (
    <div data-testid="chain-extension-properties" />
  ),
  loadChainExtensionPropertiesToForm: jest.fn(),
  readChainExtensionPropertiesFromForm: jest.fn(),
}));

jest.mock("../../src/pages/ChainPage.tsx", () => ({
  ChainContext: React.createContext({
    chain: {
      id: "chain-1",
      name: "Chain",
      labels: [],
      description: "",
      businessDescription: "",
      assumptions: "",
      outOfScope: "",
      navigationPath: [],
      parentId: undefined,
    },
    update: (...args: unknown[]) => mockChainUpdate(...args),
    refresh: jest.fn(),
  }),
}));

describe("ChainProperties", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockGetPathToFolderByName.mockResolvedValue([]);
    mockChainUpdate.mockResolvedValue(undefined);
    mockUseBlocker.mockReturnValue({
      state: "unblocked" as const,
      proceed: mockProceed,
      reset: mockReset,
    });
  });

  it("uses blocker proceed when user chooses Yes in unsaved changes dialog", async () => {
    mockUseBlocker.mockReturnValue({
      state: "blocked" as const,
      proceed: mockProceed,
      reset: mockReset,
    });

    render(<ChainProperties />);

    await waitFor(() => expect(mockShowModal).toHaveBeenCalled());
    const modal = mockShowModal.mock.calls[0][0]
      .component;
    render(modal);

    fireEvent.click(screen.getByRole("button", { name: "Yes" }));

    expect(mockProceed).toHaveBeenCalled();
    expect(mockReset).not.toHaveBeenCalled();
    expect(mockChainUpdate).not.toHaveBeenCalled();
  });

  it("resets blocker when user chooses No in unsaved changes dialog", async () => {
    mockUseBlocker.mockReturnValue({
      state: "blocked" as const,
      proceed: mockProceed,
      reset: mockReset,
    });

    render(<ChainProperties />);

    await waitFor(() => expect(mockShowModal).toHaveBeenCalled());
    const modal = mockShowModal.mock.calls[0][0]
      .component;
    render(modal);

    fireEvent.click(screen.getByRole("button", { name: "No" }));

    expect(mockReset).toHaveBeenCalled();
    expect(mockProceed).not.toHaveBeenCalled();
    expect(mockChainUpdate).not.toHaveBeenCalled();
  });

  it("close icon dismisses the question and keeps editing on Properties", async () => {
    mockUseBlocker.mockReturnValue({
      state: "blocked" as const,
      proceed: mockProceed,
      reset: mockReset,
    });

    render(<ChainProperties />);

    await waitFor(() => expect(mockShowModal).toHaveBeenCalled());
    const modal = mockShowModal.mock.calls[0][0]
      .component;
    render(modal);

    fireEvent.click(screen.getByRole("button", { name: "Close" }));

    expect(mockReset).toHaveBeenCalled();
    expect(mockProceed).not.toHaveBeenCalled();
    expect(mockChainUpdate).not.toHaveBeenCalled();
  });
});
