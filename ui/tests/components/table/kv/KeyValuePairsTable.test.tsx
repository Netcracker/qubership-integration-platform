/**
 * @jest-environment jsdom
 */
import { createRef } from "react";
import { act, fireEvent, render, screen, within } from "@testing-library/react";
import "@testing-library/jest-dom";
import {
  KeyValuePairsTable,
  KeyValuePairsTableRef,
} from "../../../../src/components/table/kv/KeyValuePairsTable";

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

function rowOf(text: string): HTMLElement {
  return screen.getByText(text).closest("tr") as HTMLElement;
}

function editCell(viewerText: string, newValue: string) {
  fireEvent.click(screen.getByRole("button", { name: viewerText }));
  const input = screen.getByDisplayValue(viewerText);
  fireEvent.change(input, { target: { value: newValue } });
  fireEvent.keyDown(input, { key: "Enter", code: "Enter", keyCode: 13 });
}

describe("KeyValuePairsTable", () => {
  it("should render pairs with default column names", () => {
    render(<KeyValuePairsTable value={[{ key: "a", value: "1" }]} />);

    expect(
      screen.getByRole("columnheader", { name: "Key" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("columnheader", { name: "Value" }),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "a" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "1" })).toBeInTheDocument();
  });

  it("should render custom column names and header", () => {
    render(
      <KeyValuePairsTable
        value={[]}
        columnNames={{ key: "Input", value: "Result" }}
        header={<span>Custom header</span>}
      />,
    );

    expect(
      screen.getByRole("columnheader", { name: "Input" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("columnheader", { name: "Result" }),
    ).toBeInTheDocument();
    expect(screen.getByText("Custom header")).toBeInTheDocument();
  });

  it("should keep the first pair when keys are duplicated", () => {
    render(
      <KeyValuePairsTable
        value={[
          { key: "a", value: "first" },
          { key: "a", value: "second" },
        ]}
      />,
    );

    expect(screen.getByText("first")).toBeInTheDocument();
    expect(screen.queryByText("second")).not.toBeInTheDocument();
  });

  it("should prepend an empty pair when add is called", () => {
    const onChange = jest.fn();
    const ref = createRef<KeyValuePairsTableRef>();
    render(
      <KeyValuePairsTable
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
  });

  it("should not add a second empty pair when one already exists", () => {
    const onChange = jest.fn();
    const ref = createRef<KeyValuePairsTableRef>();
    render(
      <KeyValuePairsTable
        value={[{ key: "", value: "" }]}
        onChange={onChange}
        actionRef={ref}
      />,
    );

    act(() => ref.current?.add());

    expect(onChange).not.toHaveBeenCalled();
  });

  it("should remove all pairs when clear is called", () => {
    const onChange = jest.fn();
    const ref = createRef<KeyValuePairsTableRef>();
    render(
      <KeyValuePairsTable
        value={[{ key: "a", value: "1" }]}
        onChange={onChange}
        actionRef={ref}
      />,
    );

    act(() => ref.current?.clear());

    expect(onChange).toHaveBeenCalledWith([]);
    expect(screen.queryByText("a")).not.toBeInTheDocument();
  });

  it("should remove a pair when its delete button is clicked", () => {
    const onChange = jest.fn();
    render(
      <KeyValuePairsTable
        value={[
          { key: "a", value: "1" },
          { key: "b", value: "2" },
        ]}
        onChange={onChange}
      />,
    );

    const buttons = within(rowOf("a")).getAllByRole("button");
    fireEvent.click(buttons[buttons.length - 1]);

    expect(onChange).toHaveBeenCalledWith([{ key: "b", value: "2" }]);
  });

  it("should update the value when the edit is committed with Enter", async () => {
    const onChange = jest.fn();
    render(
      <KeyValuePairsTable
        value={[{ key: "a", value: "1" }]}
        onChange={onChange}
      />,
    );

    editCell("1", "2");

    expect(
      await screen.findByRole("button", { name: "2" }),
    ).toBeInTheDocument();
    expect(onChange).toHaveBeenCalledWith([{ key: "a", value: "2" }]);
  });

  it("should rename the key when the new key is unique", async () => {
    const onChange = jest.fn();
    render(
      <KeyValuePairsTable
        value={[{ key: "a", value: "1" }]}
        onChange={onChange}
      />,
    );

    editCell("a", "c");

    expect(
      await screen.findByRole("button", { name: "c" }),
    ).toBeInTheDocument();
    expect(onChange).toHaveBeenCalledWith([{ key: "c", value: "1" }]);
  });

  it("should reject the key when another pair already has it", async () => {
    const onChange = jest.fn();
    render(
      <KeyValuePairsTable
        value={[
          { key: "a", value: "1" },
          { key: "b", value: "2" },
        ]}
        onChange={onChange}
      />,
    );

    editCell("a", "b");

    expect(await screen.findByText("Already exists: b")).toBeInTheDocument();
    expect(onChange).not.toHaveBeenCalled();
  });

  it("should show the new pair in edit mode when value changes to include it", () => {
    const { rerender } = render(<KeyValuePairsTable value={[]} />);

    rerender(<KeyValuePairsTable value={[{ key: "", value: "" }]} />);

    expect(screen.getByRole("textbox")).toHaveValue("");
  });
});
