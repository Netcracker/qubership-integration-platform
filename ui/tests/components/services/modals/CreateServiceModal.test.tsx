/**
 * @jest-environment jsdom
 */
import React from "react";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom";
import { CreateServiceModal } from "../../../../src/components/services/modals/CreateServiceModal";

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

const mockCloseModal = jest.fn();

jest.mock("../../../../src/ModalContextProvider.tsx", () => ({
  useModalContext: () => ({
    closeContainingModal: mockCloseModal,
  }),
}));

describe("CreateServiceModal", () => {
  const mockSubmit = jest.fn().mockResolvedValue(undefined);

  beforeEach(() => {
    jest.clearAllMocks();
    mockSubmit.mockResolvedValue(undefined);
  });

  it("renders with defaultName pre-filled in the Name field", () => {
    render(
      <CreateServiceModal
        defaultName="New external service"
        onSubmit={mockSubmit}
      />,
    );

    expect(screen.getByText("Create service")).toBeInTheDocument();
    const nameInput = screen.getByRole("textbox", { name: /name/i });
    expect(nameInput).toHaveValue("New external service");
  });

  it("shows Cancel and Create buttons and Name field", () => {
    render(<CreateServiceModal onSubmit={mockSubmit} />);

    expect(screen.getByText("Create service")).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: /name/i })).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /^cancel$/i }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /^create$/i }),
    ).toBeInTheDocument();
  });

  it("Cancel button calls closeContainingModal", () => {
    render(<CreateServiceModal onSubmit={mockSubmit} />);

    fireEvent.click(screen.getByRole("button", { name: /^cancel$/i }));
    expect(mockCloseModal).toHaveBeenCalled();
  });

  it("submit calls onSubmit with name and description", async () => {
    render(<CreateServiceModal onSubmit={mockSubmit} />);

    fireEvent.change(screen.getByRole("textbox", { name: /name/i }), {
      target: { value: "svc-a" },
    });
    fireEvent.change(screen.getByRole("textbox", { name: /description/i }), {
      target: { value: "desc" },
    });

    fireEvent.click(screen.getByRole("button", { name: /^create$/i }));

    await waitFor(() => {
      expect(mockSubmit).toHaveBeenCalledWith("svc-a", "desc");
    });
  });

  it("calls closeContainingModal after successful submit", async () => {
    render(<CreateServiceModal onSubmit={mockSubmit} />);

    fireEvent.change(screen.getByRole("textbox", { name: /name/i }), {
      target: { value: "svc-b" },
    });

    fireEvent.click(screen.getByRole("button", { name: /^create$/i }));

    await waitFor(() => {
      expect(mockCloseModal).toHaveBeenCalled();
    });
  });

  it("shows error alert when onSubmit throws", async () => {
    mockSubmit.mockRejectedValue(new Error("Creation failed"));
    render(<CreateServiceModal onSubmit={mockSubmit} />);

    fireEvent.change(screen.getByRole("textbox", { name: /name/i }), {
      target: { value: "svc-c" },
    });

    fireEvent.click(screen.getByRole("button", { name: /^create$/i }));

    await waitFor(() => {
      expect(screen.getByRole("alert")).toBeInTheDocument();
    });
    expect(mockCloseModal).not.toHaveBeenCalled();
  });
});
