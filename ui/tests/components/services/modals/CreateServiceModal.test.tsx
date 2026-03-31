/**
 * @jest-environment jsdom
 */
import React from "react";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom";
import { CreateServiceModal } from "../../../../src/components/services/modals/CreateServiceModal";
import { IntegrationSystemType } from "../../../../src/api/apiTypes";

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

describe("CreateServiceModal", () => {
  const onCancel = jest.fn();
  const onCreate = jest.fn().mockResolvedValue(undefined);

  beforeEach(() => {
    jest.clearAllMocks();
    onCreate.mockResolvedValue(undefined);
  });

  it("does not render modal content when closed", () => {
    render(
      <CreateServiceModal
        open={false}
        onCancel={onCancel}
        onCreate={onCreate}
        loading={false}
      />,
    );
    expect(screen.queryByText("Create service")).not.toBeInTheDocument();
  });

  it("shows Cancel and Create, vertical Name field", () => {
    render(
      <CreateServiceModal
        open={true}
        onCancel={onCancel}
        onCreate={onCreate}
        loading={false}
      />,
    );

    expect(screen.getByText("Create service")).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: /name/i })).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /^cancel$/i }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /^create$/i }),
    ).toBeInTheDocument();
  });

  it("Cancel calls onCancel", () => {
    render(
      <CreateServiceModal
        open={true}
        onCancel={onCancel}
        onCreate={onCreate}
        loading={false}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: /^cancel$/i }));
    expect(onCancel).toHaveBeenCalled();
  });

  it("submit calls onCreate with name, description, type", async () => {
    render(
      <CreateServiceModal
        open={true}
        onCancel={onCancel}
        onCreate={onCreate}
        loading={false}
        defaultType={IntegrationSystemType.INTERNAL}
      />,
    );

    const nameInput = screen.getByRole("textbox", { name: /name/i });
    fireEvent.change(nameInput, { target: { value: "svc-a" } });

    fireEvent.change(screen.getByRole("textbox", { name: /description/i }), {
      target: { value: "desc" },
    });

    fireEvent.click(screen.getByRole("button", { name: /^create$/i }));

    await waitFor(() => {
      expect(onCreate).toHaveBeenCalledWith(
        "svc-a",
        "desc",
        IntegrationSystemType.INTERNAL,
      );
    });
  });
});
