/**
 * @jest-environment jsdom
 */
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom";
import { InlineTypeEdit } from "../../../src/components/mapper/InlineTypeEdit";
import { DataType } from "../../../src/mapper/model/model";
import { DataTypes } from "../../../src/mapper/util/types";

jest.mock("../../../src/components/InlineEdit.module.css", () => ({
  __esModule: true,
  default: {},
}));

describe("InlineTypeEdit", () => {
  // The mapping table passes a new definitions array on every redraw, which rebuilds the
  // options. An open editor has to keep a value that still matches one of them (#1226).
  it("should submit the stored type when the table redraws while the editor is open", async () => {
    const onSubmit = jest.fn<void, [DataType | null | undefined]>();
    const type = DataTypes.stringType();
    const cell = () => (
      <InlineTypeEdit
        type={type}
        definitions={[]}
        disableArrayTypes
        disableObjectType
        onSubmit={onSubmit}
      />
    );
    const { rerender } = render(cell());

    fireEvent.click(screen.getByRole("button"));
    rerender(cell());
    fireEvent.blur(screen.getByRole("combobox"), {
      relatedTarget: document.body,
    });

    await waitFor(() => expect(onSubmit).toHaveBeenCalled());
    expect(onSubmit).toHaveBeenCalledWith(type);
  });
});
