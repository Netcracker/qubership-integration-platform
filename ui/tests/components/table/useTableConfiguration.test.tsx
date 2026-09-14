/** @jest-environment jsdom */
import React from "react";
import {
  act,
  fireEvent,
  render,
  renderHook,
  screen,
  within,
} from "@testing-library/react";
import { Table } from "antd";
import {
  type ControlledTableSort,
  useTableConfiguration,
} from "../../../src/components/table/useTableConfiguration";
import { resetTableSettings } from "../../../src/components/table/useTableSetting";
import { useTableColumnResize } from "../../../src/components/table/useTableColumnResize";
import { useFilter } from "../../../src/components/table/filter/useFilter";
import {
  FilterCondition,
  StringFilterConditions,
} from "../../../src/components/table/filter/filterTypes";

jest.mock("../../../src/Modals", () => ({
  useModalsContext: () => ({ showModal: jest.fn() }),
}));

type Row = { id: string; createdWhen: number };
const rows: Row[] = [
  { id: "older", createdWhen: 1 },
  { id: "newer", createdWhen: 2 },
];
const columns = [
  { key: "id", dataIndex: "id", title: "Name" },
  {
    key: "createdWhen",
    dataIndex: "createdWhen",
    title: "Created",
    sorter: (a: Row, b: Row) => a.createdWhen - b.createdWhen,
    filters: [{ text: "Newest", value: 2 }],
    onFilter: (value: unknown, row: Row) => row.createdWhen === value,
  },
];
const firstRow = () =>
  within(screen.getAllByRole("row")[1]).getAllByRole("cell")[0].textContent;
type ConfiguredTableProps = {
  storageKey?: string;
  data?: Row[];
  controlledSort?: ControlledTableSort;
};

const ConfiguredTable = ({
  storageKey = "chains",
  data = rows,
  controlledSort,
}: ConfiguredTableProps) => {
  const { columnsWithResize, components, handleTableChange } =
    useTableConfiguration(
      columns,
      { id: 200, createdWhen: 200 },
      { controlledSort },
      storageKey,
    );
  return (
    <Table
      columns={columnsWithResize}
      components={components}
      onChange={handleTableChange}
      dataSource={data}
      rowKey="id"
      pagination={false}
    />
  );
};

const table = (key = "chains", data = rows) => (
  <ConfiguredTable storageKey={key} data={data} />
);

beforeEach(() => localStorage.clear());

it("should restore descending date sorting after reopening and retain it when data refreshes", () => {
  const first = render(table());
  fireEvent.click(screen.getByText("Created"));
  fireEvent.click(screen.getByText("Created"));
  expect(firstRow()).toBe("newer");
  first.unmount();
  const second = render(table());
  expect(firstRow()).toBe("newer");
  second.rerender(table("chains", [...rows, { id: "latest", createdWhen: 3 }]));
  expect(firstRow()).toBe("latest");
  act(() => resetTableSettings("chains"));
  expect(firstRow()).toBe("older");
});

it("should restore column filters and clear them on reset", () => {
  localStorage.setItem(
    "chains_columnFilters",
    JSON.stringify({ createdWhen: [2] }),
  );
  render(table());
  expect(screen.queryByText("older")).not.toBeInTheDocument();
  expect(screen.getByText("newer")).toBeInTheDocument();
  act(() => resetTableSettings("chains"));
  expect(screen.getByText("older")).toBeInTheDocument();
});

it("should keep settings separate when the table key changes", () => {
  localStorage.setItem(
    "chains_sort",
    JSON.stringify([{ key: "createdWhen", order: "descend" }]),
  );
  const view = render(table());
  expect(firstRow()).toBe("newer");
  view.rerender(table("other"));
  expect(firstRow()).toBe("older");
  view.rerender(table());
  expect(firstRow()).toBe("newer");
});

it("should use controlled sorting without storing a second copy", () => {
  render(
    <ConfiguredTable
      storageKey="testing"
      controlledSort={{ key: "createdWhen", order: "descend" }}
    />,
  );

  expect(firstRow()).toBe("newer");
  expect(localStorage.getItem("testing_sort")).toBeNull();
});

it("should restore resized widths and reset them to defaults", () => {
  const first = renderHook(() =>
    useTableColumnResize({ name: 200, createdWhen: 200 }, "chains"),
  );
  void act(() =>
    first.result.current
      .createResizeHandlers("name", "createdWhen", 80)
      .onResizeStop({} as React.SyntheticEvent, {
        node: document.createElement("div"),
        size: { width: 250, height: 0 },
        handle: "e",
      }),
  );
  first.unmount();
  const second = renderHook(() =>
    useTableColumnResize({ name: 200, createdWhen: 200 }, "chains"),
  );
  expect(second.result.current.columnWidths).toEqual({
    name: 250,
    createdWhen: 150,
  });
  act(() => resetTableSettings("chains"));
  expect(second.result.current.columnWidths).toEqual({
    name: 200,
    createdWhen: 200,
  });
});

it("should restore applied advanced filters and remove them on reset", () => {
  const filterColumns = [
    { id: "name", name: "Name", conditions: StringFilterConditions },
  ];
  const first = renderHook(() => useFilter(filterColumns, "chains"));
  void act(() =>
    first.result.current.applyFilters([
      {
        id: "filter",
        columnValue: "name",
        conditionValue: FilterCondition.CONTAINS.id,
        value: "orders",
      },
    ]),
  );
  first.unmount();
  const second = renderHook(() => useFilter(filterColumns, "chains"));
  expect(second.result.current.filters).toEqual([
    { column: "name", condition: FilterCondition.CONTAINS.id, value: "orders" },
  ]);
  act(() => resetTableSettings("chains"));
  expect(second.result.current.filters).toEqual([]);
  expect(second.result.current.filterItemStates).toEqual([]);
});
