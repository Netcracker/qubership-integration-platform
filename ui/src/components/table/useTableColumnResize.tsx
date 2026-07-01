import { useCallback, useMemo, useState, type SyntheticEvent } from "react";
import { flushSync } from "react-dom";
import type { ResizeCallbackData, ResizableProps } from "react-resizable";
import "react-resizable/css/styles.css";
import { ResizableTitle } from "./ResizableTitle.tsx";
import type { ColumnType, ColumnsType } from "antd/lib/table";

export type ColumnWidthsState = Record<string, number>;

export type ColumnResizeBundledHandlers = {
  onResize: NonNullable<ResizableProps["onResize"]>;
  onResizeStop: NonNullable<ResizableProps["onResizeStop"]>;
};

export type CreateColumnResizeHandlers = (
  columnKey: string,
  nextColumnKey: string | undefined,
  minColumnWidth: number,
) => ColumnResizeBundledHandlers;

/**
 * East-edge resize: columnKey gains/loses delta; the next managed column to the right
 * gets the opposite delta so the sum of those two widths stays constant. Stops the
 * engine from shrinking unrelated left columns (e.g. Id) when table width is constrained.
 */
function applyEastResizeWithCompensation(
  prev: ColumnWidthsState,
  columnKey: string,
  nextKey: string | undefined,
  requestedWidth: number,
  minW: number,
): ColumnWidthsState {
  if (nextKey == null) {
    return { ...prev, [columnKey]: Math.max(minW, requestedWidth) };
  }
  const oldK = prev[columnKey];
  const oldNext = prev[nextKey];
  let newK = Math.max(minW, requestedWidth);
  let newNext = oldNext - (newK - oldK);
  if (newNext < minW) {
    newNext = minW;
    newK = oldK + (oldNext - newNext);
    newK = Math.max(minW, newK);
  }
  return { ...prev, [columnKey]: newK, [nextKey]: newNext };
}

function findNextManagedColumnKey<T>(
  columns: ColumnsType<T>,
  fromIndex: number,
  columnWidths: ColumnWidthsState,
): string | undefined {
  for (let i = fromIndex + 1; i < columns.length; i++) {
    const k = columns[i]?.key as string | undefined;
    if (k && k in columnWidths) {
      return k;
    }
  }
  return undefined;
}

function stripResizeHeaderProps(headerCell: unknown): Record<string, unknown> {
  const base: Record<string, unknown> = {};
  if (headerCell && typeof headerCell === "object") {
    Object.assign(base, headerCell as Record<string, unknown>);
  }
  delete base.onResize;
  delete base.onResizeStop;
  delete base.minResizeWidth;
  delete base.resizeHandleZIndex;
  return base;
}

export type AttachResizeOptions = {
  /** Minimum width (px) for resizable columns; forwarded to ResizableTitle. Default 80. */
  minWidth?: number;
};

export type SumScrollXExtras = {
  /**
   * rc-table / Ant Design inject an expand icon column that is not in `columns`.
   * Omitting it makes scroll.x smaller than colgroup sum; with table-layout:fixed
   * the browser steals width from the left data columns when resizing.
   */
  expandColumnWidth?: number;
  /** Same for the selection column when not represented in `columns`. */
  selectionColumnWidth?: number;
};

/**
 * Sums horizontal scroll width from visible columns: uses columnWidths for keys in map,
 * otherwise numeric col.width. Add extras for injected expand/selection columns.
 */
export function sumScrollXForColumns<T>(
  columns: ColumnsType<T>,
  columnWidths: ColumnWidthsState,
  extras?: SumScrollXExtras,
): number {
  let sum = 0;
  for (const col of columns) {
    const key = col.key as string | undefined;
    if (key && key in columnWidths) {
      sum += columnWidths[key];
    } else if (typeof col.width === "number") {
      sum += col.width;
    }
  }
  if (extras?.expandColumnWidth != null) {
    sum += extras.expandColumnWidth;
  }
  if (extras?.selectionColumnWidth != null) {
    sum += extras.selectionColumnWidth;
  }
  return sum;
}

/**
 * Table column resize: forwardRef header cell, min constraints,
 * attachResizeToColumns merges existing onHeaderCell (header cell component: ResizableTitle).
 */
export function useTableColumnResize(initialWidths: ColumnWidthsState): {
  columnWidths: ColumnWidthsState;
  createResizeHandlers: CreateColumnResizeHandlers;
  totalColumnsWidth: number;
  resizableHeaderComponents: {
    header: { cell: typeof ResizableTitle };
  };
} {
  const [columnWidths, setColumnWidths] =
    useState<ColumnWidthsState>(initialWidths);

  const createResizeHandlers = useCallback<CreateColumnResizeHandlers>(
    (columnKey, nextColumnKey, minColumnWidth) => {
      const run = (_event: SyntheticEvent, data: ResizeCallbackData) => {
        flushSync(() => {
          setColumnWidths((prev) =>
            applyEastResizeWithCompensation(
              prev,
              columnKey,
              nextColumnKey,
              data.size.width,
              minColumnWidth,
            ),
          );
        });
      };
      return { onResize: run, onResizeStop: run };
    },
    [],
  );

  const totalColumnsWidth = useMemo(
    () => Object.values(columnWidths).reduce((sum, w) => sum + w, 0),
    [columnWidths],
  );

  const resizableHeaderComponents = useMemo(
    () => ({
      header: {
        cell: ResizableTitle,
      },
    }),
    [],
  );

  return {
    columnWidths,
    createResizeHandlers,
    totalColumnsWidth,
    resizableHeaderComponents,
  };
}

export function attachResizeToColumns<T>(
  columns: ColumnsType<T> | undefined,
  columnWidths: ColumnWidthsState,
  createResizeHandlers: CreateColumnResizeHandlers,
  options?: AttachResizeOptions,
): ColumnsType<T> {
  if (!columns) return [];
  const minResizeWidth = options?.minWidth ?? 80;
  // Only keys present in columnWidths are resizable.
  // Keep fixed action columns out of this map to avoid resize handles.

  return columns.map((col, colIndex): ColumnType<T> => {
    const key = col.key as string | undefined;
    if (!key || !(key in columnWidths)) {
      return col;
    }
    const width = columnWidths[key];
    const prevOnHeaderCell = col.onHeaderCell;
    const nextKey = findNextManagedColumnKey(columns, colIndex, columnWidths);
    // Drop the east handle only on the table's rightmost column, where it juts
    // ~5px past the table edge and forces a permanent horizontal scrollbar. A
    // column with any column to its right keeps its handle and stays resizable;
    // the column before a fixed actions column is stripped separately by
    // disableResizeBeforeActions.
    const isLastColumn = colIndex === columns.length - 1;
    if (isLastColumn) {
      return {
        ...col,
        width,
        onHeaderCell: (column) => {
          const prev =
            typeof prevOnHeaderCell === "function"
              ? prevOnHeaderCell(column)
              : {};
          return {
            ...stripResizeHeaderProps(prev),
            width,
          };
        },
      };
    }
    const { onResize, onResizeStop } = createResizeHandlers(
      key,
      nextKey,
      minResizeWidth,
    );

    return {
      ...col,
      width,
      onHeaderCell: (column) => {
        const prev =
          typeof prevOnHeaderCell === "function"
            ? prevOnHeaderCell(column)
            : {};
        return {
          ...prev,
          width,
          minResizeWidth,
          resizeHandleZIndex: 10 + colIndex,
          onResize,
          onResizeStop,
        };
      },
    };
  });
}
