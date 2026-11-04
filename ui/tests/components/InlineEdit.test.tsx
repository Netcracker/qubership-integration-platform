/**
 * @jest-environment jsdom
 */
import {
  render,
  screen,
  fireEvent,
  waitFor,
  act,
} from "@testing-library/react";
import "@testing-library/jest-dom";
import { InlineEdit } from "../../src/components/InlineEdit";
import { TextValueEdit } from "../../src/components/table/TextValueEdit";

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

jest.mock("../../src/components/InlineEdit.module.css", () => ({
  __esModule: true,
  default: { inlineEditValueWrap: "inline-edit-value-wrap" },
}));

function cell(
  name: string,
  onSubmit?: (values: { name: string }) => void | Promise<void>,
) {
  return (
    <InlineEdit<{ name: string }>
      values={{ name }}
      editor={<TextValueEdit name="name" />}
      viewer={<span>{name}</span>}
      onSubmit={onSubmit}
      initialActive
    />
  );
}

function renderCell(
  onSubmit: (values: { name: string }) => void | Promise<void>,
) {
  return render(cell("before", onSubmit));
}

describe("InlineEdit", () => {
  // A submit used to flip the editor twice and land back open, which reads as a
  // field that refused to save.
  it("should leave the editor once a value is committed", async () => {
    const onSubmit = jest.fn().mockResolvedValue(undefined);
    renderCell(onSubmit);

    const input = screen.getByRole("textbox");
    fireEvent.change(input, { target: { value: "after" } });
    fireEvent.keyDown(input, { key: "Enter", keyCode: 13 });

    await waitFor(() =>
      expect(onSubmit).toHaveBeenCalledWith({ name: "after" }),
    );
    await waitFor(() =>
      expect(screen.queryByRole("textbox")).not.toBeInTheDocument(),
    );
    expect(screen.getByRole("button")).toBeInTheDocument();
  });

  it("should submit when focus leaves the editor", async () => {
    const onSubmit = jest.fn().mockResolvedValue(undefined);
    renderCell(onSubmit);

    const input = screen.getByRole("textbox");
    fireEvent.change(input, { target: { value: "typed then left" } });
    fireEvent.focusOut(input, { relatedTarget: document.body });

    await waitFor(() =>
      expect(onSubmit).toHaveBeenCalledWith({ name: "typed then left" }),
    );
  });

  it("should not submit while focus stays inside the editor", async () => {
    const onSubmit = jest.fn().mockResolvedValue(undefined);
    renderCell(onSubmit);

    const input = screen.getByRole("textbox");
    fireEvent.change(input, { target: { value: "still editing" } });

    // Focus moving to another control of the same editor is not a departure.
    fireEvent.focusOut(input, { relatedTarget: input.parentElement });
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 50));
    });
    expect(onSubmit).not.toHaveBeenCalled();

    // The same event does submit once focus lands outside, which is what proves
    // the handler was reached at all above.
    fireEvent.focusOut(input, { relatedTarget: document.body });
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
  });

  // Every redraw of the caller passes a new values object. Each one used to put the
  // stored value back into the open editor and drop what the user had typed (#1226).
  it("should keep what the user typed when the caller redraws", () => {
    const { rerender } = render(cell("before"));

    fireEvent.change(screen.getByRole("textbox"), {
      target: { value: "typed" },
    });
    rerender(cell("before"));

    expect(screen.getByRole("textbox")).toHaveValue("typed");
  });

  // A table keyed by row index moves another record under an open editor when a row
  // above it is deleted, and the editor then submits to that record.
  it("should show the new value when the caller passes a different one", () => {
    const { rerender } = render(cell("before"));

    fireEvent.change(screen.getByRole("textbox"), {
      target: { value: "typed" },
    });
    rerender(cell("another record"));

    expect(screen.getByRole("textbox")).toHaveValue("another record");
  });

  // A rejected save keeps the editor open so the value can be retyped.
  it("should keep the editor open when the submit fails", async () => {
    const onSubmit = jest.fn().mockRejectedValue(new Error("nope"));
    renderCell(onSubmit);

    const input = screen.getByRole("textbox");
    fireEvent.change(input, { target: { value: "after" } });
    fireEvent.keyDown(input, { key: "Enter", keyCode: 13 });

    await waitFor(() => expect(onSubmit).toHaveBeenCalled());
    expect(screen.getByRole("textbox")).toBeInTheDocument();
  });
});
