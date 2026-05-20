/**
 * @jest-environment jsdom
 */

import { render, screen, fireEvent } from "@testing-library/react";
import { LoadConfirmationDialog } from "../../../src/components/mapper/LoadConfirmationDialog";
import { useModalContext } from "../../../src/ModalContextProvider";
import { DataTypeDifferencesView } from "../../../src/components/mapper/DataTypeDifferencesView";
import "@testing-library/jest-dom";

jest.mock("../../../src/ModalContextProvider");
jest.mock("../../../src/components/mapper/DataTypeDifferencesView");

const mockUseModalContext = useModalContext as jest.MockedFunction<
  typeof useModalContext
>;
const mockDataTypeDifferencesView =
  DataTypeDifferencesView as jest.MockedFunction<
    typeof DataTypeDifferencesView
  >;

describe("LoadConfirmationDialog", () => {
  const mockCloseContainingModal = jest.fn();
  const mockOnSubmit = jest.fn();

  beforeEach(() => {
    mockUseModalContext.mockReturnValue({
      closeContainingModal: mockCloseContainingModal,
    });
    mockDataTypeDifferencesView.mockImplementation(() => (
      <div>Mock Differences View</div>
    ));
  });

  afterEach(() => {
    jest.clearAllMocks();
  });

  it("renders the modal with warning title and content", () => {
    render(<LoadConfirmationDialog />);
    expect(screen.getByText("Warning")).toBeInTheDocument();
    expect(
      screen.getByText("Applying new data schema may impose breaking changes."),
    ).toBeInTheDocument();
    expect(screen.getByText("Details")).toBeInTheDocument();
  });

  it("shows details when expand button is clicked", () => {
    render(<LoadConfirmationDialog />);
    expect(screen.queryByText("Mock Differences View")).toBeFalsy();
    const expandButton = screen.getByRole("button", { name: /details/i });
    fireEvent.click(expandButton);
    expect(screen.getByText("Mock Differences View")).toBeInTheDocument();
  });

  it("calls onSubmit and closeContainingModal when OK is clicked", () => {
    render(<LoadConfirmationDialog onSubmit={mockOnSubmit} />);
    const okButton = screen.getByRole("button", { name: /apply/i });
    fireEvent.click(okButton);
    expect(mockOnSubmit).toHaveBeenCalledTimes(1);
    expect(mockCloseContainingModal).toHaveBeenCalledTimes(1);
  });

  it("calls closeContainingModal when Cancel is clicked", () => {
    render(<LoadConfirmationDialog />);
    const cancelButton = screen.getByRole("button", { name: /cancel/i });
    fireEvent.click(cancelButton);
    expect(mockCloseContainingModal).toHaveBeenCalledTimes(1);
    expect(mockOnSubmit).not.toHaveBeenCalled();
  });

  it("passes differences to DataTypeDifferencesView", () => {
    const differences = [{ path: ["test"], first: null, second: null }];
    render(<LoadConfirmationDialog differences={differences} />);
    const expandButton = screen.getByRole("button", { name: /details/i });
    fireEvent.click(expandButton);
    expect(mockDataTypeDifferencesView).toHaveBeenCalledWith(
      expect.objectContaining({
        differences,
      }),
      {},
    );
  });

  it("handles no differences", () => {
    render(<LoadConfirmationDialog />);
    const expandButton = screen.getByRole("button", { name: /details/i });
    fireEvent.click(expandButton);
    expect(mockDataTypeDifferencesView).toHaveBeenCalledWith(
      expect.objectContaining({
        differences: [],
      }),
      {},
    );
  });
});
