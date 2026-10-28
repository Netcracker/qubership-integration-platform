/**
 * @jest-environment jsdom
 */
import { createRef } from "react";
import { act, fireEvent, render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";
import {
  KeyValuePairsEditor,
  KeyValuePairsEditorRef,
} from "../../../../src/components/table/kv/KeyValuePairsEditor";

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

jest.mock(
  "../../../../src/components/table/kv/KeyValuePairsTextEditor",
  () => ({
    KeyValuePairsTextEditor: ({
      value,
    }: {
      value?: { key: string; value: string }[];
    }) => <div data-testid="text-editor">{JSON.stringify(value)}</div>,
  }),
);

describe("KeyValuePairsEditor", () => {
  it("should show the table tab by default", () => {
    render(
      <KeyValuePairsEditor
        value={[{ key: "a", value: "1" }]}
        tableHeader={<span>Header</span>}
        tableColumnNames={{ key: "Input", value: "Result" }}
      />,
    );

    expect(screen.getByRole("tab", { name: "Table" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
    expect(screen.getByText("Header")).toBeInTheDocument();
    expect(
      screen.getByRole("columnheader", { name: "Input" }),
    ).toBeInTheDocument();
  });

  it("should pass the value to the text editor when the text tab is clicked", () => {
    render(<KeyValuePairsEditor value={[{ key: "a", value: "1" }]} />);

    fireEvent.click(screen.getByRole("tab", { name: "Text" }));

    expect(screen.getByTestId("text-editor")).toHaveTextContent(
      '[{"key":"a","value":"1"}]',
    );
  });

  it("should switch tabs through activateTab", () => {
    const ref = createRef<KeyValuePairsEditorRef>();
    render(<KeyValuePairsEditor value={[]} actionRef={ref} />);

    act(() => ref.current?.activateTab("text"));

    expect(screen.getByRole("tab", { name: "Text" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
  });

  it("should forward add and clear to the table", () => {
    const onChange = jest.fn();
    const ref = createRef<KeyValuePairsEditorRef>();
    render(
      <KeyValuePairsEditor
        value={[{ key: "a", value: "1" }]}
        onChange={onChange}
        actionRef={ref}
      />,
    );

    act(() => ref.current?.add());
    expect(onChange).toHaveBeenLastCalledWith([
      { key: "", value: "" },
      { key: "a", value: "1" },
    ]);

    act(() => ref.current?.clear());
    expect(onChange).toHaveBeenLastCalledWith([]);
  });
});
