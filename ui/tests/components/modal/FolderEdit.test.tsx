/**
 * @jest-environment jsdom
 */
import React from "react";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom";
import { FolderEdit } from "../../../src/components/modal/FolderEdit";

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

describe("FolderEdit", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("create mode shows New Folder and Create primary button", () => {
    render(<FolderEdit onSubmit={jest.fn()} mode="create" />);
    expect(screen.getByText("New Folder")).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /^create$/i }),
    ).toBeInTheDocument();
  });

  it("update mode shows Edit Folder and Apply primary button", () => {
    render(<FolderEdit onSubmit={jest.fn()} mode="update" />);
    expect(screen.getByText("Edit Folder")).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /^apply$/i }),
    ).toBeInTheDocument();
  });

  it("submits name and description from initialValues and fields", async () => {
    const onSubmit = jest.fn();
    render(
      <FolderEdit
        onSubmit={onSubmit}
        mode="update"
        name="N0"
        description="D0"
      />,
    );

    fireEvent.change(screen.getByRole("textbox", { name: /name/i }), {
      target: { value: "N1" },
    });
    fireEvent.change(screen.getByRole("textbox", { name: /description/i }), {
      target: { value: "D1" },
    });

    fireEvent.submit(document.getElementById("folderEditForm")!);

    await waitFor(() => {
      expect(onSubmit).toHaveBeenCalledWith("N1", "D1", undefined, undefined);
    });
    expect(mockCloseContainingModal).toHaveBeenCalled();
  });

  it("create mode passes openFolder and newTab checkboxes to onSubmit", async () => {
    const onSubmit = jest.fn();
    render(<FolderEdit onSubmit={onSubmit} mode="create" />);

    fireEvent.change(screen.getByRole("textbox", { name: /name/i }), {
      target: { value: "F" },
    });
    fireEvent.click(screen.getByRole("checkbox", { name: /in new tab/i }));

    fireEvent.submit(document.getElementById("folderEditForm")!);

    await waitFor(() => {
      expect(onSubmit).toHaveBeenCalledWith("F", "", true, true);
    });
  });
});
