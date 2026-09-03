/**
 * @jest-environment jsdom
 */

import { render, screen, fireEvent, within } from "@testing-library/react";
import "@testing-library/jest-dom";
import { PLACEHOLDER } from "../../../src/misc/format-utils.ts";
import {
  type KVChangesTableItem,
  SessionElementKVChanges,
} from "../../../src/components/sessions/SessionElementKVChanges.tsx";
import { LightweightTable as mockLightweightTable } from "../../__mocks__/LightweightTable.tsx";

/** A column as the component hands it to the table, before the table reads it. */
type SortableColumn<ValueType> = {
  title?: string;
  defaultSortOrder?: string;
  sorter?: (
    item1: KVChangesTableItem<ValueType>,
    item2: KVChangesTableItem<ValueType>,
  ) => number;
};

/** Columns of the last rendered table, held for the sorting assertions. */
const mockCapturedColumns: { current: unknown[] } = { current: [] };

jest.mock(
  "../../../src/components/sessions/SessionElementKVChanges.module.css",
  () => ({
    valueChanged: "valueChanged",
  }),
);

jest.mock("antd", () => {
  const react = jest.requireActual<typeof import("react")>("react");
  const { createChainPageAntdMock } = jest.requireActual<{
    createChainPageAntdMock: (
      extraOverrides?: Record<string, unknown>,
    ) => Record<string, unknown>;
  }>("tests/helpers/chainPageAntdJestMock");
  return createChainPageAntdMock({
    Table: (props: Parameters<typeof mockLightweightTable>[0]) => {
      mockCapturedColumns.current = props.columns ?? [];
      return react.createElement(mockLightweightTable, props);
    },
  });
});

jest.mock("antd/lib/table", () => ({}));
jest.mock("antd/lib/table/interface", () => ({}));
jest.mock("antd/es/table/interface", () => ({}));

/** The column the component supplied under this title, so no test types a key. */
function sortableColumn<ValueType>(title: string): SortableColumn<ValueType> {
  const column = (
    mockCapturedColumns.current as SortableColumn<ValueType>[]
  ).find((entry) => entry.title === title);
  expect(column).toBeDefined();
  return column as SortableColumn<ValueType>;
}

function item<ValueType>(
  name: string,
  before?: ValueType,
  after?: ValueType,
): KVChangesTableItem<ValueType> {
  return { name, before, after };
}

describe("SessionElementKVChanges", () => {
  test("wraps differing string values with highlight class", () => {
    render(<SessionElementKVChanges before={{ k: "a" }} after={{ k: "b" }} />);
    expect(screen.getByText("a")).toHaveClass("valueChanged");
    expect(screen.getByText("b")).toHaveClass("valueChanged");
  });

  test("Only modified switch hides unchanged keys", () => {
    render(
      <SessionElementKVChanges
        before={{ same: "x", diff: "1" }}
        after={{ same: "x", diff: "2" }}
      />,
    );
    expect(screen.getByText("same")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("switch"));
    expect(screen.queryByText("same")).not.toBeInTheDocument();
    expect(screen.getByText("diff")).toBeInTheDocument();
  });

  test("properties comparator treats undefined before as modified (value highlight)", () => {
    type Prop = { type: string; value: string };
    const comparator = (p1: Prop | undefined, p2: Prop | undefined) => {
      if (p1 === p2) return 0;
      if (p1 === undefined || p2 === undefined) return 1;
      const byType = (p1.type ?? "").localeCompare(p2.type ?? "");
      if (byType !== 0) return byType;
      return (p1.value ?? "").localeCompare(p2.value ?? "");
    };

    render(
      <SessionElementKVChanges<Prop>
        addTypeColumns
        before={{}}
        after={{ p: { type: "t", value: "v" } }}
        comparator={comparator}
        typeRenderer={(p) => p.type}
        valueRenderer={(p) => p.value}
      />,
    );

    const row = document.querySelector('tr[data-row-key="p"]') as HTMLElement;
    expect(row).toBeTruthy();
    expect(within(row).getByText("t")).toHaveClass("valueChanged");
    expect(within(row).getByText("v")).toHaveClass("valueChanged");
  });

  test("onColumnClick receives column name for name cell", () => {
    const onColumnClick = jest.fn();
    render(
      <SessionElementKVChanges
        before={{ key1: "a" }}
        after={{ key1: "b" }}
        onColumnClick={onColumnClick}
      />,
    );

    const row = screen.getByRole("row", { name: /key1/i });
    fireEvent.click(within(row).getByText("key1"));
    expect(onColumnClick).toHaveBeenCalledWith(
      expect.objectContaining({ name: "key1" }),
      "name",
    );
  });

  test("onColumnClick for value before column", () => {
    const onColumnClick = jest.fn();
    render(
      <SessionElementKVChanges
        before={{ k: "x" }}
        after={{ k: "y" }}
        onColumnClick={onColumnClick}
      />,
    );

    const row = screen.getByRole("row", { name: /k/i });
    fireEvent.click(within(row).getByText("x"));
    expect(onColumnClick).toHaveBeenCalledWith(
      expect.objectContaining({ name: "k" }),
      "valueBefore",
    );
  });

  test("name column sorts case-insensitively and ascends by default", () => {
    render(
      <SessionElementKVChanges before={{ Beta: "1" }} after={{ alpha: "2" }} />,
    );

    const name = sortableColumn<string>("Name");
    expect(name.defaultSortOrder).toBe("ascend");
    expect(name.sorter?.(item("alpha"), item("Beta"))).toBeLessThan(0);
    expect(name.sorter?.(item("Beta"), item("alpha"))).toBeGreaterThan(0);
    expect(name.sorter?.(item("Same"), item("same"))).toBe(0);
  });

  test("value columns sort by the raw value when no getter is given", () => {
    render(<SessionElementKVChanges before={{ k: "a" }} after={{ k: "b" }} />);

    const before = sortableColumn<string>("Value Before");
    expect(before.sorter?.(item("k1", "Apple"), item("k2", "apple"))).toBe(0);
    expect(before.sorter?.(item<string>("k1"), item("k2", "a"))).toBeLessThan(
      0,
    );

    const after = sortableColumn<string>("Value After");
    expect(
      after.sorter?.(item("k1", "x", "b"), item("k2", "x", "a")),
    ).toBeGreaterThan(0);
  });

  test("type and value columns sort through the getters when provided", () => {
    type Prop = { type: string; value: string };
    render(
      <SessionElementKVChanges<Prop>
        addTypeColumns
        before={{ p: { type: "string", value: "v" } }}
        after={{ p: { type: "number", value: "1" } }}
        typeRenderer={(p) => p.type}
        valueRenderer={(p) => p.value}
        typeGetter={(p) => p?.type ?? ""}
        valueGetter={(p) => p?.value ?? ""}
      />,
    );

    expect(
      (mockCapturedColumns.current as SortableColumn<Prop>[])
        .filter((column) => column.sorter)
        .map((column) => column.title),
    ).toEqual([
      "Name",
      "Type Before",
      "Value Before",
      "Type After",
      "Value After",
    ]);

    const prop = (type: string, value: string) => ({ type, value });
    const typeBefore = sortableColumn<Prop>("Type Before");
    expect(
      typeBefore.sorter?.(
        item("k1", prop("Number", "1")),
        item("k2", prop("number", "2")),
      ),
    ).toBe(0);
    expect(
      typeBefore.sorter?.(item<Prop>("k1"), item("k2", prop("a", "1"))),
    ).toBeLessThan(0);

    const typeAfter = sortableColumn<Prop>("Type After");
    expect(
      typeAfter.sorter?.(
        item("k1", undefined, prop("string", "v")),
        item("k2", undefined, prop("number", "1")),
      ),
    ).toBeGreaterThan(0);

    const valueBefore = sortableColumn<Prop>("Value Before");
    expect(
      valueBefore.sorter?.(
        item("k1", prop("string", "a")),
        item("k2", prop("string", "B")),
      ),
    ).toBeLessThan(0);

    const valueAfter = sortableColumn<Prop>("Value After");
    expect(
      valueAfter.sorter?.(
        item("k1", undefined, prop("string", "b")),
        item("k2", undefined, prop("string", "a")),
      ),
    ).toBeGreaterThan(0);
  });

  test("shows PLACEHOLDER for missing before or after side", () => {
    render(
      <SessionElementKVChanges
        before={{ onlyBefore: "a" }}
        after={{ onlyAfter: "b" }}
      />,
    );
    const placeholders = screen.getAllByText(PLACEHOLDER);
    expect(placeholders.length).toBeGreaterThanOrEqual(2);
    expect(screen.getByText("a")).toBeInTheDocument();
    expect(screen.getByText("b")).toBeInTheDocument();
  });
});
