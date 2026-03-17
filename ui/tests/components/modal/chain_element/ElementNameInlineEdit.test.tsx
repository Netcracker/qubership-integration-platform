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
  "../../../../src/components/modal/chain_element/ChainElementModification.module.css",
  () => ({
    __esModule: true,
    default: {
      "modal-title-name": "modal-title-name",
      "modal-title-type": "modal-title-type",
      "element-name-edit-wrapper": "element-name-edit-wrapper",
      "element-name-inline-viewer": "element-name-inline-viewer",
      "element-name-edit-icon": "element-name-edit-icon",
      "element-name-inline-editor": "element-name-inline-editor",
      "element-name-input": "element-name-input",
    },
  }),
);

describe("ElementNameInlineEdit", () => {
  it("renders value and pencil icon in view mode", () => {
    render(
      <ElementNameInlineEdit value="My Element" typeLabel="http-trigger" />,
    );
    expect(screen.getByText("My Element")).toBeInTheDocument();
    expect(screen.getByText(/http-trigger/)).toBeInTheDocument();
    expect(screen.getByTestId("icon")).toHaveAttribute("data-icon", "edit");
  });

  it("shows type label in parentheses when provided", () => {
    render(<ElementNameInlineEdit value="Trigger" typeLabel="HTTP Trigger" />);
    expect(screen.getByText("HTTP Trigger")).toBeInTheDocument();
  });

  it("switches to edit mode on viewer click", () => {
    render(<ElementNameInlineEdit value="Original Name" onSave={jest.fn()} />);
    const viewer = screen.getByRole("button", { name: /edit name/i });
    fireEvent.click(viewer);

    const input = screen.getByTestId("element-name-input");
    expect(input).toBeInTheDocument();
    expect(input).toHaveValue("Original Name");
  });

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

  it("does not call onSave when Apply is clicked with empty value", async () => {
    const onSave = jest.fn();
    render(<ElementNameInlineEdit value="Original" onSave={onSave} />);
    fireEvent.click(screen.getByRole("button", { name: /edit name/i }));

    const input = screen.getByTestId("element-name-input");
    fireEvent.change(input, { target: { value: "   " } });

    const applyButton = screen.getByTestId("element-name-apply");
    expect(applyButton).toBeDisabled();
    fireEvent.click(applyButton);

    expect(onSave).not.toHaveBeenCalled();
  });

  it("calls onSave when Apply is clicked with non-empty value", async () => {
    const onSave = jest.fn().mockResolvedValue(undefined);
    render(<ElementNameInlineEdit value="Original" onSave={onSave} />);
    fireEvent.click(screen.getByRole("button", { name: /edit name/i }));

    const input = screen.getByTestId("element-name-input");
    fireEvent.change(input, { target: { value: "New Name" } });

    fireEvent.click(screen.getByTestId("element-name-apply"));

    await waitFor(() => {
      expect(onSave).toHaveBeenCalledWith("New Name");
    });
  });

  it("exits without saving when Cancel is clicked", () => {
    const onSave = jest.fn();
    render(<ElementNameInlineEdit value="Original" onSave={onSave} />);
    fireEvent.click(screen.getByRole("button", { name: /edit name/i }));

    const input = screen.getByTestId("element-name-input");
    fireEvent.change(input, { target: { value: "Changed" } });

    fireEvent.click(screen.getByTestId("element-name-cancel"));

    expect(onSave).not.toHaveBeenCalled();
    expect(screen.getByText("Original")).toBeInTheDocument();
  });

  it("does not switch to edit mode when disabled", () => {
    render(
      <ElementNameInlineEdit value="Original" onSave={jest.fn()} disabled />,
    );
    const viewer = screen.getByText("Original");
    fireEvent.click(viewer);

    expect(screen.queryByTestId("element-name-input")).not.toBeInTheDocument();
  });

  it("handles async onSave", async () => {
    const onSave = jest
      .fn()
      .mockImplementation(
        () => new Promise((resolve) => setTimeout(resolve, 10)),
      );
    render(<ElementNameInlineEdit value="Original" onSave={onSave} />);
    fireEvent.click(screen.getByRole("button", { name: /edit name/i }));

    const input = screen.getByTestId("element-name-input");
    fireEvent.change(input, { target: { value: "Async Save" } });
    fireEvent.click(screen.getByTestId("element-name-apply"));

    await waitFor(() => {
      expect(onSave).toHaveBeenCalledWith("Async Save");
    });
  });

  describe("syncIfEditing (ref API)", () => {
    it("returns null when not in edit mode", () => {
      const ref = React.createRef<ElementNameInlineEditRef>();
      render(
        <ElementNameInlineEdit ref={ref} value="Original" onSave={jest.fn()} />,
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
      const input = screen.getByTestId("element-name-input");
      fireEvent.change(input, { target: { value: "Pending Name" } });

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
          onSave={jest.fn()}
          disabled
        />,
      );
      expect(ref.current?.syncIfEditing()).toBeNull();
    });
  });
});
