/**
 * @jest-environment jsdom
 */
import { useState } from "react";
import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import "@testing-library/jest-dom";
import { NameValueTable } from "../../../src/components/testing/NameValueTable";
import { TestingNamedParameter } from "../../../src/api/apiTypes";
import { getHttpFieldNameError } from "../../../src/misc/http-field-utils";

let saved: TestingNamedParameter[] = [];

function Headers() {
  const [rows, setRows] = useState<TestingNamedParameter[]>([
    { name: "X-First", value: "1" },
    { name: "X-Second", value: "2" },
    { name: "X-Second", value: "3" },
  ]);
  return (
    <NameValueTable
      title="Headers"
      rowNoun="Header"
      values={rows}
      onChange={(next) => {
        saved = next;
        setRows(next);
      }}
      validateName={getHttpFieldNameError}
    />
  );
}

// Role queries over the whole table are slow in jsdom.
const bodyRows = () =>
  Array.from(document.querySelectorAll<HTMLElement>("tr.ant-table-row"));

describe("NameValueTable", () => {
  // Rows were keyed by index, so deleting a row above an open editor moved the next
  // header under it, and the edit was saved over that header (#1226 review).
  it("should save an open edit to its own header after a row above is deleted", async () => {
    render(<Headers />);

    fireEvent.click(within(bodyRows()[1]).getByText("X-Second"));
    const input = within(bodyRows()[1]).getByLabelText("Name");
    fireEvent.change(input, { target: { value: "" } });
    // An empty name fails validation, so leaving the cell keeps the editor open.
    const deleteFirst = within(bodyRows()[0]).getByRole("button", {
      name: "Delete",
    });
    fireEvent.blur(input, { relatedTarget: deleteFirst });
    await within(bodyRows()[1]).findByText("Enter a header name.");
    fireEvent.click(deleteFirst);

    const editor = screen.getByLabelText("Name");
    fireEvent.change(editor, { target: { value: "X-New" } });
    fireEvent.keyDown(editor, { key: "Enter", keyCode: 13 });

    await waitFor(() =>
      expect(saved).toEqual([
        { name: "X-New", value: "2" },
        { name: "X-Second", value: "3" },
      ]),
    );
  });
});
