/**
 * @jest-environment jsdom
 */
import { fireEvent, render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";
import { UnsavedChangesModal } from "../../../src/components/modal/UnsavedChangesModal";

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

const mockCloseContainingModal = jest.fn();

jest.mock("../../../src/ModalContextProvider", () => ({
  useModalContext: () => ({
    closeContainingModal: mockCloseContainingModal,
  }),
}));

describe("UnsavedChangesModal", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("renders unsaved changes copy", () => {
    render(<UnsavedChangesModal onYes={jest.fn()} onNo={jest.fn()} />);

    expect(screen.getByText("Unsaved Changes")).toBeInTheDocument();
    expect(
      screen.getByText(
        "You have made changes, that haven't been saved. Are you sure you want to leave the window and discard the changes?",
      ),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Yes" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "No" })).toBeInTheDocument();
  });

  it("closes modal and calls onNo when Yes is clicked", () => {
    const onNo = jest.fn();

    render(<UnsavedChangesModal onYes={jest.fn()} onNo={onNo} />);

    fireEvent.click(screen.getByRole("button", { name: "Yes" }));

    expect(mockCloseContainingModal).toHaveBeenCalled();
    expect(onNo).toHaveBeenCalled();
  });

  it("dismisses only the question dialog when No is clicked", () => {
    const onNo = jest.fn();
    const onCancelQuestion = jest.fn();

    render(
      <UnsavedChangesModal
        onYes={jest.fn()}
        onNo={onNo}
        onCancelQuestion={onCancelQuestion}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: "No" }));

    expect(mockCloseContainingModal).toHaveBeenCalled();
    expect(onCancelQuestion).toHaveBeenCalled();
    expect(onNo).not.toHaveBeenCalled();
  });

  it("close icon dismisses only the question dialog", () => {
    const onNo = jest.fn();
    const onCancelQuestion = jest.fn();

    render(
      <UnsavedChangesModal
        onYes={jest.fn()}
        onNo={onNo}
        onCancelQuestion={onCancelQuestion}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: "Close" }));

    expect(mockCloseContainingModal).toHaveBeenCalled();
    expect(onCancelQuestion).toHaveBeenCalled();
    expect(onNo).not.toHaveBeenCalled();
  });
});
