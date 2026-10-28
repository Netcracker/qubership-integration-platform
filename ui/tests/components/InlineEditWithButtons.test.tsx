/**
 * @jest-environment jsdom
 */
import { fireEvent, render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";
import { InlineEditWithButtons } from "../../src/components/InlineEditWithButtons";
import { TextValueEdit } from "../../src/components/table/TextValueEdit";

jest.mock("../../src/components/InlineEdit.module.css", () => ({
  __esModule: true,
  default: {},
}));

function cell(name: string, initialActive?: boolean) {
  return (
    <InlineEditWithButtons<{ name: string }>
      values={{ name }}
      editor={<TextValueEdit name="name" rules={[]} />}
      viewer={<span>{name}</span>}
      viewerAriaLabel="Edit name"
      initialActive={initialActive}
    />
  );
}

describe("InlineEditWithButtons", () => {
  // Every redraw of the caller passes a new values object. Each one used to put the
  // stored value back into the open editor and drop what the user had typed (#1226).
  it("should keep what the user typed when the caller redraws", () => {
    const { rerender } = render(cell("before", true));

    fireEvent.change(screen.getByRole("textbox"), {
      target: { value: "typed" },
    });
    rerender(cell("before", true));

    expect(screen.getByRole("textbox")).toHaveValue("typed");
  });

  it("should show the current value when the editor opens again after a cancel", () => {
    const { rerender } = render(cell("before"));

    fireEvent.click(screen.getByRole("button", { name: "Edit name" }));
    fireEvent.change(screen.getByRole("textbox"), {
      target: { value: "discarded" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    rerender(cell("after"));
    fireEvent.click(screen.getByRole("button", { name: "Edit name" }));

    expect(screen.getByRole("textbox")).toHaveValue("after");
  });
});
