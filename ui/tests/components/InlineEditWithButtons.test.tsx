/**
 * @jest-environment jsdom
 */
import { fireEvent, render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";
import { InlineEditWithButtons } from "../../src/components/InlineEditWithButtons";
import { TextValueEdit } from "../../src/components/table/TextValueEdit";

function cell(name: string) {
  return (
    <InlineEditWithButtons<{ name: string }>
      values={{ name }}
      editor={<TextValueEdit name="name" rules={[]} />}
      viewer={<span>{name}</span>}
      initialActive
    />
  );
}

describe("InlineEditWithButtons", () => {
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

  it("should show the new value when the caller passes a different one", () => {
    const { rerender } = render(cell("before"));

    fireEvent.change(screen.getByRole("textbox"), {
      target: { value: "typed" },
    });
    rerender(cell("another record"));

    expect(screen.getByRole("textbox")).toHaveValue("another record");
  });
});
