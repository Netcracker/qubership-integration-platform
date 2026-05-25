/**
 * @jest-environment jsdom
 */

import { render, screen, fireEvent } from "@testing-library/react";
import "@testing-library/jest-dom";
import type { Chain } from "../../../../src/api/apiTypes";
import type { Change } from "../../../../src/components/chains/diff/compare/types";

const mockCloseContainingModal = jest.fn();
const mockUseChainDiff = jest.fn();

jest.mock("../../../../src/ModalContextProvider.tsx", () => ({
  useModalContext: () => ({
    closeContainingModal: mockCloseContainingModal,
  }),
}));

jest.mock("../../../../src/components/chains/diff/useChainDiff.tsx", () => ({
  useChainDiff: (...args: unknown[]) => mockUseChainDiff(...args),
}));

jest.mock("antd", () => ({
  Spin: ({ className, size }: { className?: string; size?: string }) => (
    <div data-testid="loading-spinner" className={className} data-size={size} />
  ),
}));

jest.mock("../../../../src/components/modal/ModalWithFullscreenToggle.tsx", () => ({
  ModalWithFullscreenToggle: jest.fn(({ title, onCancel, children }: any) => (
    <div data-testid="mock-modal">
      <div data-testid="modal-title">{title}</div>
      <button data-testid="modal-close" onClick={onCancel}>
        Close
      </button>
      {children}
    </div>
  )),
}));

jest.mock("../../../../src/components/chains/diff/ChainDiffView.tsx", () => ({
  ChainDiffView: jest.fn(() => <div data-testid="chain-diff-view" />),
}));

import { ModalWithFullscreenToggle } from "../../../../src/components/modal/ModalWithFullscreenToggle";
import { ChainDiffView } from "../../../../src/components/chains/diff/ChainDiffView";
import { ChainDiffPopup } from "../../../../src/components/chains/diff/ChainDiffPopup";

const MockModal = ModalWithFullscreenToggle as jest.MockedFunction<
  typeof ModalWithFullscreenToggle
>;
const MockChainDiffView = ChainDiffView as jest.MockedFunction<
  typeof ChainDiffView
>;

const defaultHookReturn = {
  isLoading: false,
  chain1: undefined as Chain | undefined,
  chain2: undefined as Chain | undefined,
  changes: [] as Change[],
  selectedChangeId: undefined as string | undefined,
  setSelectedChangeId: jest.fn(),
};

describe("ChainDiffPopup", () => {
  beforeEach(() => {
    mockUseChainDiff.mockReturnValue({
      ...defaultHookReturn,
      setSelectedChangeId: jest.fn(),
    });
  });

  it("should render the modal with title 'Chain compare' when mounted", () => {
    render(<ChainDiffPopup chainId1="c1" chainId2="c2" />);

    expect(screen.getByTestId("modal-title")).toHaveTextContent(
      "Chain compare",
    );
  });

  it("should render the modal with no footer when mounted", () => {
    render(<ChainDiffPopup chainId1="c1" chainId2="c2" />);

    expect(MockModal.mock.calls[0][0].footer).toBeNull();
  });

  it("should show a loading spinner and hide ChainDiffView when isLoading is true", () => {
    mockUseChainDiff.mockReturnValue({ ...defaultHookReturn, isLoading: true });

    render(<ChainDiffPopup chainId1="c1" chainId2="c2" />);

    expect(screen.getByTestId("loading-spinner")).toBeInTheDocument();
    expect(screen.queryByTestId("chain-diff-view")).not.toBeInTheDocument();
  });

  it("should show ChainDiffView and hide the spinner when isLoading is false", () => {
    render(<ChainDiffPopup chainId1="c1" chainId2="c2" />);

    expect(screen.getByTestId("chain-diff-view")).toBeInTheDocument();
    expect(screen.queryByTestId("loading-spinner")).not.toBeInTheDocument();
  });

  it("should pass chain1, chain2, changes, and selectedChangeId to ChainDiffView when not loading", () => {
    const chain1 = { id: "chain-1" } as Chain;
    const chain2 = { id: "chain-2" } as Chain;
    const changes: Change[] = [{ id: "c1", kind: "element" }];
    mockUseChainDiff.mockReturnValue({
      ...defaultHookReturn,
      chain1,
      chain2,
      changes,
      selectedChangeId: "change-1",
    });

    render(<ChainDiffPopup chainId1="chain-1" chainId2="chain-2" />);

    const props = MockChainDiffView.mock.calls[0][0];
    expect(props.chain1).toBe(chain1);
    expect(props.chain2).toBe(chain2);
    expect(props.changes).toBe(changes);
    expect(props.selectedChangeId).toBe("change-1");
  });

  it("should pass setSelectedChangeId as onSelectChange to ChainDiffView when not loading", () => {
    const mockSetSelectedChangeId = jest.fn();
    mockUseChainDiff.mockReturnValue({
      ...defaultHookReturn,
      setSelectedChangeId: mockSetSelectedChangeId,
    });

    render(<ChainDiffPopup chainId1="c1" chainId2="c2" />);

    const { onSelectChange } = MockChainDiffView.mock.calls[0][0];
    onSelectChange("change-123");

    expect(mockSetSelectedChangeId).toHaveBeenCalledWith("change-123");
  });

  it("should call closeContainingModal when the modal is closed", () => {
    render(<ChainDiffPopup chainId1="c1" chainId2="c2" />);

    fireEvent.click(screen.getByTestId("modal-close"));

    expect(mockCloseContainingModal).toHaveBeenCalledTimes(1);
  });
});
