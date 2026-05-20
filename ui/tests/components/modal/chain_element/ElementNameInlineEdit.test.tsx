/**
 * @jest-environment jsdom
 */
import React from "react";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom";
import {
  ElementNameInlineEdit,
  type ElementNameInlineEditRef,
} from "../../../../src/components/modal/chain_element/ElementNameInlineEdit";

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

jest.mock("../../../../src/icons/IconProvider", () => ({
  OverridableIcon: ({ name }: { name: string }) => (
    <span data-testid="icon" data-icon={name} />
  ),
}));

jest.mock("../../../../src/components/InlineEdit.module.css", () => ({
  __esModule: true,
  default: {
    inlineEditValueWrap: "inline-edit-value-wrap",
    inlineEditButtons: "inline-edit-buttons",
    inlineEditFormWrap: "inline-edit-form-wrap",
  },
}));

jest.mock(
  "../../../../src/components/modal/chain_element/ElementNameInlineEdit.module.css",
  () => ({
    __esModule: true,
    default: {
      name: "name",
      "type-badge": "type-badge",
      wrapper: "wrapper",
      viewer: "viewer",
      "edit-icon": "edit-icon",
      editor: "editor",
      input: "input",
    },
  }),
);

const noop = jest.fn();

describe("ElementNameInlineEdit", () => {
  // --- View mode ---

  it("renders value and pencil icon in view mode", () => {
    render(
      <ElementNameInlineEdit
        value="My Element"
        typeLabel="http-trigger"
        onSave={noop}
      />,
    );
    expect(screen.getByText("My Element")).toBeInTheDocument();
    expect(screen.getByText("http-trigger")).toBeInTheDocument();
    expect(screen.getByTestId("icon")).toHaveAttribute("data-icon", "edit");
  });

  it("renders type badge when typeLabel is provided", () => {
    render(
      <ElementNameInlineEdit
        value="Trigger"
        typeLabel="HTTP Trigger"
        onSave={noop}
      />,
    );
    expect(screen.getByText("HTTP Trigger")).toBeInTheDocument();
  });

  it("renders without type badge when typeLabel is omitted", () => {
    render(<ElementNameInlineEdit value="Trigger" onSave={noop} />);
    expect(screen.getByText("Trigger")).toBeInTheDocument();
    // Only the name text and edit icon — no badge element
    const viewer = screen.getByRole("button", { name: /edit name/i });
    expect(viewer.querySelectorAll(".type-badge")).toHaveLength(0);
  });

  // --- Switching to edit mode ---

  it("switches to edit mode on viewer click", () => {
    render(<ElementNameInlineEdit value="Original Name" onSave={noop} />);
    fireEvent.click(screen.getByRole("button", { name: /edit name/i }));

    const input = screen.getByTestId("element-name-input");
    expect(input).toBeInTheDocument();
    expect(input).toHaveValue("Original Name");
  });

  it("does not switch to edit mode when disabled", () => {
    render(<ElementNameInlineEdit value="Original" onSave={noop} disabled />);
    fireEvent.click(screen.getByText("Original"));
    expect(screen.queryByTestId("element-name-input")).not.toBeInTheDocument();
  });

  it("hides pencil icon when disabled", () => {
    render(<ElementNameInlineEdit value="Original" onSave={noop} disabled />);
    expect(screen.queryByTestId("icon")).not.toBeInTheDocument();
  });

  // --- Save via Enter ---

  it("calls onSave with trimmed value when Enter is pressed", async () => {
    const onSave = jest.fn().mockResolvedValue(undefined);
    render(<ElementNameInlineEdit value="Original" onSave={onSave} />);
    fireEvent.click(screen.getByRole("button", { name: /edit name/i }));

    const input = screen.getByTestId("element-name-input");
    fireEvent.change(input, { target: { value: "  New Name  " } });
    fireEvent.keyDown(input, { key: "Enter", code: "Enter" });

    await waitFor(() => {
      expect(onSave).toHaveBeenCalledWith("New Name");
    });
  });

  it("handles async onSave via Enter", async () => {
    const onSave = jest
      .fn()
      .mockImplementation(
        () => new Promise((resolve) => setTimeout(resolve, 10)),
      );
    render(<ElementNameInlineEdit value="Original" onSave={onSave} />);
    fireEvent.click(screen.getByRole("button", { name: /edit name/i }));

    const input = screen.getByTestId("element-name-input");
    fireEvent.change(input, { target: { value: "Async Save" } });
    fireEvent.keyDown(input, { key: "Enter", code: "Enter" });

    await waitFor(() => {
      expect(onSave).toHaveBeenCalledWith("Async Save");
    });
  });

  // --- Empty value handling ---

  it("does not save empty value and closes editor", async () => {
    const onSave = jest.fn();
    render(<ElementNameInlineEdit value="Original" onSave={onSave} />);
    fireEvent.click(screen.getByRole("button", { name: /edit name/i }));

    const input = screen.getByTestId("element-name-input");
    fireEvent.change(input, { target: { value: "   " } });
    fireEvent.keyDown(input, { key: "Enter", code: "Enter" });

    await waitFor(() => {
      expect(
        screen.queryByTestId("element-name-input"),
      ).not.toBeInTheDocument();
    });
    expect(onSave).not.toHaveBeenCalled();
    expect(screen.getByText("Original")).toBeInTheDocument();
  });

  // --- Cancel ---

  it("exits edit mode without saving when Escape is pressed", () => {
    const onSave = jest.fn();
    render(<ElementNameInlineEdit value="Original" onSave={onSave} />);
    fireEvent.click(screen.getByRole("button", { name: /edit name/i }));

    const input = screen.getByTestId("element-name-input");
    fireEvent.change(input, { target: { value: "Changed" } });
    fireEvent.keyDown(input, { key: "Escape", code: "Escape" });

    expect(onSave).not.toHaveBeenCalled();
    expect(screen.getByText("Original")).toBeInTheDocument();
  });

  it("exits without saving when Cancel button is clicked", () => {
    const onSave = jest.fn();
    render(<ElementNameInlineEdit value="Original" onSave={onSave} />);
    fireEvent.click(screen.getByRole("button", { name: /edit name/i }));

    fireEvent.change(screen.getByTestId("element-name-input"), {
      target: { value: "Changed" },
    });
    fireEvent.click(screen.getByTestId("element-name-cancel"));

    expect(onSave).not.toHaveBeenCalled();
    expect(screen.getByText("Original")).toBeInTheDocument();
  });

  // --- Apply button is hidden ---

  it("does not render Apply button", () => {
    render(<ElementNameInlineEdit value="Original" onSave={noop} />);
    fireEvent.click(screen.getByRole("button", { name: /edit name/i }));

    expect(screen.queryByTestId("element-name-apply")).not.toBeInTheDocument();
    // Cancel button is still present
    expect(screen.getByTestId("element-name-cancel")).toBeInTheDocument();
  });

  // --- syncIfEditing (ref API) ---

  describe("syncIfEditing", () => {
    it("returns null when not in edit mode", () => {
      const ref = React.createRef<ElementNameInlineEditRef>();
      render(
        <ElementNameInlineEdit ref={ref} value="Original" onSave={noop} />,
      );
      expect(ref.current?.syncIfEditing()).toBeNull();
    });

    it("returns current value and exits edit mode when editing", async () => {
      const ref = React.createRef<ElementNameInlineEditRef>();
      const onSave = jest.fn();
      render(
        <ElementNameInlineEdit ref={ref} value="Original" onSave={onSave} />,
      );
      fireEvent.click(screen.getByRole("button", { name: /edit name/i }));
      fireEvent.change(screen.getByTestId("element-name-input"), {
        target: { value: "Pending Name" },
      });

      const result = ref.current?.syncIfEditing();
      expect(result).toBe("Pending Name");
      expect(onSave).not.toHaveBeenCalled();
      await waitFor(() => {
        expect(
          screen.queryByTestId("element-name-input"),
        ).not.toBeInTheDocument();
      });
    });

    it("returns null when disabled", () => {
      const ref = React.createRef<ElementNameInlineEditRef>();
      render(
        <ElementNameInlineEdit
          ref={ref}
          value="Original"
          onSave={noop}
          disabled
        />,
      );
      expect(ref.current?.syncIfEditing()).toBeNull();
    });
  });
});
