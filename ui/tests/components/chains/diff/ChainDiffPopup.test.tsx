/**
 * @jest-environment jsdom
 */

import { render, screen, fireEvent } from "@testing-library/react";
import "@testing-library/jest-dom";
import type { ComparableItem } from "../../../../src/components/chains/diff/useChainDiff";

const mockCloseContainingModal = jest.fn();

jest.mock("../../../../src/ModalContextProvider.tsx", () => ({
  useModalContext: () => ({
    closeContainingModal: mockCloseContainingModal,
  }),
}));

jest.mock(
  "../../../../src/components/modal/ModalWithFullscreenToggle.tsx",
  () => ({
    ModalWithFullscreenToggle: jest.fn(({ title, onCancel, children }: any) => (
      <div data-testid="mock-modal">
        <div data-testid="modal-title">{title}</div>
        <button data-testid="modal-close" onClick={onCancel}>
          Close
        </button>
        {children}
      </div>
    )),
  }),
);

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

const item1: ComparableItem = { kind: "snapshot", id: "snap-1" };
const item2: ComparableItem = { kind: "snapshot", id: "snap-2" };

describe("ChainDiffPopup", () => {
  it("should render the modal with title 'Chain compare' when mounted", () => {
    render(<ChainDiffPopup item1={item1} item2={item2} />);

    expect(screen.getByTestId("modal-title")).toHaveTextContent(
      "Chain compare",
    );
  });

  it("should render the modal with no footer when mounted", () => {
    render(<ChainDiffPopup item1={item1} item2={item2} />);

    expect(MockModal.mock.calls[0][0].footer).toBeNull();
  });

  it("should always render ChainDiffView inside the modal", () => {
    render(<ChainDiffPopup item1={item1} item2={item2} />);

    expect(screen.getByTestId("chain-diff-view")).toBeInTheDocument();
  });

  it("should pass item1 and item2 to ChainDiffView", () => {
    render(<ChainDiffPopup item1={item1} item2={item2} />);

    const props = MockChainDiffView.mock.calls[0][0];
    expect(props.item1).toBe(item1);
    expect(props.item2).toBe(item2);
  });

  it("should pass editable1 and editable2 to ChainDiffView", () => {
    render(
      <ChainDiffPopup
        item1={item1}
        item2={item2}
        editable1={true}
        editable2={false}
      />,
    );

    const props = MockChainDiffView.mock.calls[0][0];
    expect(props.editable1).toBe(true);
    expect(props.editable2).toBe(false);
  });

  it("should pass style={{ height: '100%' }} to ChainDiffView", () => {
    render(<ChainDiffPopup item1={item1} item2={item2} />);

    const props = MockChainDiffView.mock.calls[0][0];
    expect(props.style).toEqual({ height: "100%" });
  });

  it("should call closeContainingModal when the modal is closed", () => {
    render(<ChainDiffPopup item1={item1} item2={item2} />);

    fireEvent.click(screen.getByTestId("modal-close"));

    expect(mockCloseContainingModal).toHaveBeenCalledTimes(1);
  });
});
