/**
 * @jest-environment jsdom
 */
import { fireEvent, render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";
import { DeleteDeploymentWarningModal } from "../../../src/components/modal/DeleteDeploymentWarningModal.tsx";

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

jest.mock("../../../src/ModalContextProvider.tsx", () => ({
  useModalContext: () => ({
    closeContainingModal: mockCloseContainingModal,
  }),
}));

describe("DeleteDeploymentWarningModal", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("should render the deployment deletion warning", () => {
    render(<DeleteDeploymentWarningModal onDelete={jest.fn()} />);

    expect(screen.getByText("Delete Deployment")).toBeInTheDocument();
    expect(
      screen.getByText(
        "Are you sure you want to permanently delete this deployment?",
      ),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Cancel" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Delete" })).toBeInTheDocument();
  });

  it("should close the modal without deleting when Cancel is clicked", () => {
    const onDelete = jest.fn();
    render(<DeleteDeploymentWarningModal onDelete={onDelete} />);

    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));

    expect(mockCloseContainingModal).toHaveBeenCalledTimes(1);
    expect(onDelete).not.toHaveBeenCalled();
  });

  it("should close the modal and delete the deployment when Delete is clicked", () => {
    const onDelete = jest.fn();
    render(<DeleteDeploymentWarningModal onDelete={onDelete} />);

    fireEvent.click(screen.getByRole("button", { name: "Delete" }));

    expect(mockCloseContainingModal).toHaveBeenCalledTimes(1);
    expect(onDelete).toHaveBeenCalledTimes(1);
  });
});
