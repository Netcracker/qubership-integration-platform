import type { ColumnType, ColumnsType } from "antd/lib/table";
import type { CSSProperties } from "react";

export const ACTIONS_COLUMN_KEY = "actions";
export const ACTIONS_COLUMN_CLASS = "actions-column";
export const DEFAULT_ACTIONS_COLUMN_WIDTH = 40;

function createFixedCellStyle(width: number): CSSProperties {
  return {
    width,
    minWidth: width,
    maxWidth: width,
  };
}

/**
 * Fixed-size sizing for actions column.
 * Keep this column out of table resize state (`columnWidths`) to avoid resize handle.
 */
export function createActionsSizing<T>(
  width = DEFAULT_ACTIONS_COLUMN_WIDTH,
): Pick<ColumnType<T>, "width" | "onHeaderCell" | "onCell"> {
  return {
    width,
    onHeaderCell: () => ({ style: createFixedCellStyle(width) }),
    onCell: () => ({ style: createFixedCellStyle(width) }),
  };
}

export function createActionsColumnBase<T>(
  width = DEFAULT_ACTIONS_COLUMN_WIDTH,
): Pick<ColumnType<T>, "key" | "title" | "className" | "align"> &
  Pick<ColumnType<T>, "width" | "onHeaderCell" | "onCell"> {
  return {
    key: ACTIONS_COLUMN_KEY,
    title: "",
    className: ACTIONS_COLUMN_CLASS,
    align: "center",
    ...createActionsSizing<T>(width),
  };
}

/**
 * Disables resize handle on the right edge of the column before actions.
 * This prevents dragging on the boundary near the actions column.
 */
export function disableResizeBeforeActions<T>(
  columns: ColumnsType<T>,
): ColumnsType<T> {
  const actionsIndex = columns.findIndex(
    (column) => String(column.key ?? "") === ACTIONS_COLUMN_KEY,
  );
  if (actionsIndex <= 0) {
    return columns;
  }

  return columns.map((column, index): ColumnType<T> => {
    if (index !== actionsIndex - 1) {
      return column;
    }
    const prevOnHeaderCell = column.onHeaderCell;
    return {
      ...column,
      onHeaderCell: (col) => {
        const base =
          typeof prevOnHeaderCell === "function" ? prevOnHeaderCell(col) : {};
        const sanitized: Record<string, unknown> = {};
        if (base && typeof base === "object") {
          Object.assign(sanitized, base);
        }
        delete sanitized.onResize;
        delete sanitized.onResizeStop;
        delete sanitized.minResizeWidth;
        delete sanitized.resizeHandleZIndex;
        return sanitized;
      },
    };
  });
}
