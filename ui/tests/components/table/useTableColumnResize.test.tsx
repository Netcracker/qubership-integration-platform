/**
 * @jest-environment jsdom
 */
import React from "react";
import { renderHook, act } from "@testing-library/react";
import "@testing-library/jest-dom";
import type { ResizeCallbackData } from "react-resizable";
import type { ColumnsType } from "antd/lib/table";
import {
  attachResizeToColumns,
  sumScrollXForColumns,
  useTableColumnResize,
} from "../../../src/components/table/useTableColumnResize";
import { ACTIONS_COLUMN_KEY } from "../../../src/components/table/actionsColumn";

const resizePayload = (w: number): ResizeCallbackData => ({
  node: document.createElement("div"),
  size: { width: w, height: 0 },
  handle: "e",
});

describe("sumScrollXForColumns", () => {
  it("sums columnWidths entries and numeric width fallback", () => {
    const columns = [
      { key: "a", title: "A" },
      { title: "B", width: 40 },
    ] as ColumnsType<Record<string, unknown>>;
    expect(sumScrollXForColumns(columns, { a: 100 })).toBe(140);
  });

  it("adds expand and selection extras", () => {
    const columns = [{ key: "a", title: "A" }] as ColumnsType<
      Record<string, unknown>
    >;
    expect(
      sumScrollXForColumns(
        columns,
        { a: 10 },
        { expandColumnWidth: 48, selectionColumnWidth: 60 },
      ),
    ).toBe(118);
  });
});

describe("attachResizeToColumns", () => {
  it("returns empty array for undefined columns", () => {
    const { result } = renderHook(() => useTableColumnResize({ x: 1 }));
    expect(
      attachResizeToColumns(
        undefined,
        { x: 1 },
        result.current.createResizeHandlers,
      ),
    ).toEqual([]);
  });

  it("leaves columns not listed in columnWidths unchanged", () => {
    const { result } = renderHook(() => useTableColumnResize({ a: 100 }));
    const columns = [
      { key: "a", title: "A" },
      { key: "b", title: "B" },
    ] as ColumnsType<Record<string, unknown>>;
    const out = attachResizeToColumns(
      columns,
      { a: 100 },
      result.current.createResizeHandlers,
    );
    expect(out[1]).toEqual(columns[1]);
  });

  it("strips right-edge resize when only actions column follows (not in widths)", () => {
    const { result } = renderHook(() => useTableColumnResize({ data: 100 }));
    const columns = [
      { key: "data", title: "Data" },
      { key: ACTIONS_COLUMN_KEY, title: "Act", width: 40 },
    ] as ColumnsType<Record<string, unknown>>;
    const out = attachResizeToColumns(
      columns,
      { data: 100 },
      result.current.createResizeHandlers,
    );
    const col0 = out[0];
    expect(col0?.onHeaderCell).toBeDefined();
    const cell = col0.onHeaderCell(columns[0]!);
    expect(cell).toMatchObject({ width: 100 });
    expect((cell as { onResize?: unknown }).onResize).toBeUndefined();
    expect((cell as { onResizeStop?: unknown }).onResizeStop).toBeUndefined();
  });

  it("forwards options.minWidth to header cell as minResizeWidth", () => {
    const { result } = renderHook(() =>
      useTableColumnResize({ a: 100, b: 100 }),
    );
    const columns = [
      { key: "a", title: "A" },
      { key: "b", title: "B" },
    ] as ColumnsType<Record<string, unknown>>;
    const out = attachResizeToColumns(
      columns,
      { a: 100, b: 100 },
      result.current.createResizeHandlers,
      { minWidth: 95 },
    );
    const cell = out[0]!.onHeaderCell!(columns[0]!);
    expect((cell as { minResizeWidth?: number }).minResizeWidth).toBe(95);
  });

  it("attaches resize handlers when next managed column exists", () => {
    const { result } = renderHook(() =>
      useTableColumnResize({ a: 100, b: 100 }),
    );
    const columns = [
      { key: "a", title: "A" },
      { key: "b", title: "B" },
    ] as ColumnsType<Record<string, unknown>>;
    const out = attachResizeToColumns(
      columns,
      { a: 100, b: 100 },
      result.current.createResizeHandlers,
    );
    const colA = out[0];
    expect(colA?.onHeaderCell).toBeDefined();
    const cell = colA.onHeaderCell(columns[0]!);
    expect(typeof (cell as { onResize: unknown }).onResize).toBe("function");
    expect(typeof (cell as { onResizeStop: unknown }).onResizeStop).toBe(
      "function",
    );
  });
});

describe("useTableColumnResize", () => {
  it("updates widths with east-edge compensation on resize", () => {
    const { result } = renderHook(() =>
      useTableColumnResize({ a: 100, b: 100 }),
    );
    const { onResize } = result.current.createResizeHandlers("a", "b", 80);
    act(() => {
      /* 130 would force b to 70 < min 80 → saturation; 115 keeps b at 85 */
      onResize({} as never, resizePayload(115));
    });
    expect(result.current.columnWidths.a).toBe(115);
    expect(result.current.columnWidths.b).toBe(85);
  });

  it("saturates neighbor at min width", () => {
    const { result } = renderHook(() =>
      useTableColumnResize({ a: 100, b: 100 }),
    );
    const { onResize } = result.current.createResizeHandlers("a", "b", 80);
    act(() => {
      onResize({} as never, resizePayload(200));
    });
    expect(result.current.columnWidths.b).toBe(80);
    expect(result.current.columnWidths.a).toBe(120);
  });

  it("expands single column when there is no right neighbor key", () => {
    const { result } = renderHook(() => useTableColumnResize({ only: 100 }));
    const { onResize } = result.current.createResizeHandlers(
      "only",
      undefined,
      50,
    );
    act(() => {
      onResize({} as never, resizePayload(160));
    });
    expect(result.current.columnWidths.only).toBe(160);
  });

  it("computes totalColumnsWidth", () => {
    const { result } = renderHook(() => useTableColumnResize({ x: 10, y: 20 }));
    expect(result.current.totalColumnsWidth).toBe(30);
  });
});
