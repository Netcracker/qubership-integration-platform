/**
 * @jest-environment jsdom
 */
import React from "react";
import { render, screen, fireEvent } from "@testing-library/react";
import "@testing-library/jest-dom";

jest.mock("react-resizable/css/styles.css", () => ({}));
jest.mock(
  "../../../../src/components/admin_tools/variables/Resizable.css",
  () => ({}),
);

jest.mock("../../../../src/hooks/useNotificationService", () => ({
  useNotificationService: () => ({
    requestFailed: jest.fn(),
    success: jest.fn(),
  }),
}));

jest.mock("../../../../src/components/modal/ErrorDetails.module.css", () => ({
  __esModule: true,
  default: {},
}));

jest.mock(
  "../../../../src/components/admin_tools/variables/VariablesTable.module.css",
  () => ({
    __esModule: true,
    default: {
      "secret-row": "secret-row",
      "value-cell-trigger": "value-cell-trigger",
      "value-text": "value-text",
      "key-text": "key-text",
      "multiline-icon": "multiline-icon",
      "delete-button": "delete-button",
      "editing-wrapper": "editing-wrapper",
      "editing-buttons": "editing-buttons",
      "editable-cell-wrapper": "editable-cell-wrapper",
      "inline-icon": "inline-icon",
    },
  }),
);

jest.mock("../../../../src/components/table/ResizableTitle.tsx", () => ({
  ResizableTitle: React.forwardRef<
    HTMLTableCellElement,
    React.ThHTMLAttributes<HTMLTableCellElement> & {
      onResize?: unknown;
      onResizeStop?: unknown;
      width?: unknown;
      minResizeWidth?: unknown;
      maxResizeWidth?: unknown;
      resizeHandleZIndex?: unknown;
    }
  >((props, ref) => {
    const {
      onResize: _onResize,
      onResizeStop: _onResizeStop,
      width: _width,
      minResizeWidth: _minResizeWidth,
      maxResizeWidth: _maxResizeWidth,
      resizeHandleZIndex: _resizeHandleZIndex,
      ...rest
    } = props;
    return <th ref={ref} {...rest} />;
  }),
}));

jest.mock("antd", () => {
  const React = require("react");
  const {
    antdMockWithLightweightTable,
  } = require("tests/helpers/antdMockWithLightweightTable");
  return antdMockWithLightweightTable({
    Popconfirm: ({
      children,
      onConfirm,
    }: {
      children: React.ReactNode;
      onConfirm?: () => void;
    }) =>
      React.createElement(
        "div",
        null,
        children,
        React.createElement(
          "button",
          { "data-testid": "popconfirm-ok", onClick: onConfirm },
          "OK",
        ),
      ),
  });
});

import VariablesTable from "../../../../src/components/admin_tools/variables/VariablesTable";
import { NEW_VARIABLE_KEY } from "../../../../src/components/admin_tools/variables/useVariablesState";

const defaultColumnsWidth = { key: 200, value: 400 };
const defaultOnResize = () => () => undefined;

const sampleVariables = [
  { key: "var-key-1", value: "val-1" },
  { key: "var-key-2", value: "val-2" },
];

function renderTable(
  overrides: Partial<React.ComponentProps<typeof VariablesTable>> = {},
) {
  const props: React.ComponentProps<typeof VariablesTable> = {
    variables: sampleVariables,
    selectedKeys: [],
    onSelectedChange: jest.fn(),
    isValueHidden: false,
    isAddingNew: false,
    onAdd: jest.fn(),
    onDelete: jest.fn(),
    onStartEditing: jest.fn(),
    onCancelEditing: jest.fn(),
    editingKey: null,
    editingValue: "",
    onChangeEditingValue: jest.fn(),
    onConfirmEdit: jest.fn(),
    columnsWidth: defaultColumnsWidth,
    onResize: defaultOnResize,
    ...overrides,
  };
  return render(<VariablesTable {...props} />);
}

describe("VariablesTable", () => {
  it("renders variable keys and values", () => {
    renderTable();
    expect(screen.getByText("var-key-1")).toBeInTheDocument();
    expect(screen.getByText("var-key-2")).toBeInTheDocument();
    expect(screen.getByText("val-1")).toBeInTheDocument();
    expect(screen.getByText("val-2")).toBeInTheDocument();
  });

  it("renders column headers Key and Value", () => {
    renderTable();
    const keyHeaders = screen.getAllByText("Key");
    expect(keyHeaders.length).toBeGreaterThan(0);
    const valueHeaders = screen.getAllByText("Value");
    expect(valueHeaders.length).toBeGreaterThan(0);
  });

  it("calls onStartEditing when value cell is clicked", () => {
    const onStartEditing = jest.fn();
    renderTable({ onStartEditing });
    const triggers = document.querySelectorAll(".editable-cell-wrapper");
    expect(triggers.length).toBeGreaterThan(0);
    fireEvent.click(triggers[0]);
    expect(onStartEditing).toHaveBeenCalledWith("var-key-1", "val-1");
  });

  it("renders TextArea when editingKey matches a variable", () => {
    renderTable({ editingKey: "var-key-1", editingValue: "new-val" });
    expect(screen.getByRole("textbox")).toBeInTheDocument();
  });

  it("calls onConfirmEdit when Apply button is clicked during editing", () => {
    const onConfirmEdit = jest.fn();
    renderTable({
      editingKey: "var-key-1",
      editingValue: "new-val",
      onConfirmEdit,
    });
    const editingWrapper = document.querySelector(".editing-wrapper");
    expect(editingWrapper).toBeInTheDocument();
    const buttons = screen.getAllByRole("button");
    fireEvent.click(buttons[0]);
    expect(onConfirmEdit).toHaveBeenCalledWith("var-key-1", "new-val");
  });

  it("calls onCancelEditing when Cancel button is clicked during editing", () => {
    const onCancelEditing = jest.fn();
    renderTable({
      editingKey: "var-key-1",
      editingValue: "some-val",
      onCancelEditing,
    });
    const buttons = screen.getAllByRole("button");
    fireEvent.click(buttons[1]);
    expect(onCancelEditing).toHaveBeenCalled();
  });

  it("calls onCancelEditing on Escape key in TextArea during editing", () => {
    const onCancelEditing = jest.fn();
    renderTable({
      editingKey: "var-key-1",
      editingValue: "some-val",
      onCancelEditing,
    });
    const textArea = screen.getByRole("textbox");
    fireEvent.keyDown(textArea, { key: "Escape" });
    expect(onCancelEditing).toHaveBeenCalled();
  });

  it("calls onConfirmEdit on Enter (no Shift) key in TextArea during editing", () => {
    const onConfirmEdit = jest.fn();
    renderTable({
      editingKey: "var-key-1",
      editingValue: "new-val",
      onConfirmEdit,
    });
    const textArea = screen.getByRole("textbox");
    fireEvent.keyDown(textArea, { key: "Enter", shiftKey: false });
    expect(onConfirmEdit).toHaveBeenCalledWith("var-key-1", "new-val");
  });

  it("does not call onConfirmEdit on Shift+Enter in TextArea", () => {
    const onConfirmEdit = jest.fn();
    renderTable({
      editingKey: "var-key-1",
      editingValue: "new-val",
      onConfirmEdit,
    });
    const textArea = screen.getByRole("textbox");
    fireEvent.keyDown(textArea, { key: "Enter", shiftKey: true });
    expect(onConfirmEdit).not.toHaveBeenCalled();
  });

  it("renders new row input fields when isAddingNew=true", () => {
    renderTable({ isAddingNew: true });
    expect(screen.getByPlaceholderText("Key")).toBeInTheDocument();
    expect(screen.getByPlaceholderText("Value")).toBeInTheDocument();
  });

  it("hides value when isValueHidden=true", () => {
    renderTable({ isValueHidden: true });
    expect(screen.getAllByText("*****")).toHaveLength(sampleVariables.length);
  });

  it("shows new variable row with the special NEW_VARIABLE_KEY key", () => {
    renderTable({ isAddingNew: true });
    const keyInput = screen.getByPlaceholderText("Key");
    expect(keyInput).toBeInTheDocument();
  });

  it("renders Apply and Cancel buttons on new variable row", () => {
    renderTable({ isAddingNew: true });
    const editingWrappers = document.querySelectorAll(".editing-wrapper");
    expect(editingWrappers.length).toBeGreaterThan(0);
    const buttons = Array.from(
      editingWrappers[0]?.querySelectorAll("button") ?? [],
    );
    expect(buttons.length).toBeGreaterThanOrEqual(2);
  });

  it("calls onAdd via Enter on key input when value is not filled", () => {
    const onAdd = jest.fn();
    renderTable({ isAddingNew: true, onAdd });
    const keyInput = screen.getByPlaceholderText("Key");
    fireEvent.change(keyInput, { target: { value: "my-new-key" } });
    fireEvent.keyDown(keyInput, { key: "Enter" });
    expect(onAdd).toHaveBeenCalled();
  });

  it("does not include NEW_VARIABLE_KEY row when isAddingNew=false", () => {
    renderTable({ isAddingNew: false });
    expect(screen.queryByPlaceholderText("Key")).not.toBeInTheDocument();
  });

  it("renders variables with NEW_VARIABLE_KEY key correctly as new row", () => {
    const variables = [{ key: NEW_VARIABLE_KEY, value: "" }];
    renderTable({ variables, isAddingNew: false });
    expect(screen.getByPlaceholderText("Key")).toBeInTheDocument();
  });

  it("renders without error when enableKeySort and enableKeyFilter are enabled", () => {
    renderTable({ enableKeySort: true, enableKeyFilter: true });
    const keyHeaders = screen.getAllByText("Key");
    expect(keyHeaders.length).toBeGreaterThan(0);
  });

  it("renders without error when enableValueSort and enableValueFilter are enabled", () => {
    renderTable({ enableValueSort: true, enableValueFilter: true });
    const valueHeaders = screen.getAllByText("Value");
    expect(valueHeaders.length).toBeGreaterThan(0);
  });

  it("onChange TextArea during editing calls onChangeEditingValue", () => {
    const onChangeEditingValue = jest.fn();
    renderTable({
      editingKey: "var-key-1",
      editingValue: "",
      onChangeEditingValue,
    });
    const textarea = screen.getByRole("textbox");
    fireEvent.change(textarea, { target: { value: "new-value" } });
    expect(onChangeEditingValue).toHaveBeenCalledWith("new-value");
  });

  it("new variable row: Apply button calls onAdd when key and value are filled", () => {
    const onAdd = jest.fn();
    renderTable({ isAddingNew: true, onAdd });
    const keyInput = screen.getByPlaceholderText("Key");
    const valueTextArea = screen.getByPlaceholderText("Value");

    fireEvent.change(keyInput, { target: { value: "my-key" } });
    fireEvent.change(valueTextArea, { target: { value: "my-val" } });

    const applyButton = document.querySelector(".editing-buttons button");
    expect(applyButton).toBeTruthy();
    fireEvent.click(applyButton!);

    expect(onAdd).toHaveBeenCalledWith("my-key", "my-val");
  });

  it("new variable row: Enter in value textarea calls onAdd when key and value are filled", () => {
    const onAdd = jest.fn();
    renderTable({ isAddingNew: true, onAdd });
    const keyInput = screen.getByPlaceholderText("Key");
    const valueTextArea = screen.getByPlaceholderText("Value");

    fireEvent.change(keyInput, { target: { value: "enter-key" } });
    fireEvent.change(valueTextArea, { target: { value: "enter-val" } });
    fireEvent.keyDown(valueTextArea, { key: "Enter", shiftKey: false });

    expect(onAdd).toHaveBeenCalledWith("enter-key", "enter-val");
  });

  it("Popconfirm confirm button calls onDelete with the variable key", () => {
    const onDelete = jest.fn();
    renderTable({ onDelete });
    const okButtons = screen.getAllByTestId("popconfirm-ok");
    expect(okButtons.length).toBeGreaterThan(0);
    fireEvent.click(okButtons[0]);
    expect(onDelete).toHaveBeenCalledWith("var-key-1");
  });

  it("clicking row checkbox calls onSelectedChange", () => {
    const onSelectedChange = jest.fn();
    renderTable({ onSelectedChange });
    const checkboxes = document.querySelectorAll(
      'tbody input[type="checkbox"]',
    );
    expect(checkboxes.length).toBeGreaterThan(0);
    fireEvent.click(checkboxes[0]);
    expect(onSelectedChange).toHaveBeenCalled();
  });
});
