import { useMemo } from "react";
import { disableResizeBeforeActions } from "./actionsColumn";
import {
  attachResizeToColumns,
  ColumnWidthsState,
  sumScrollXForColumns,
  useTableColumnResize,
} from "./useTableColumnResize";
import { ColumnsType } from "antd/lib/table";

export type UseColumnsWithResizeAndScrollOptions = {
  /** Width of the rc-table selection column, when not present in `orderedColumns`. */
  selectionColumnWidth?: number;
  /** Width of the rc-table expand-icon column, when not present in `orderedColumns`. */
  expandColumnWidth?: number;
  /** Minimum width (px) for resizable columns. Default 80. */
  minWidth?: number;
  /**
   * When true (default), strips the resize handle from the column immediately
   * before a fixed actions column. Pass false to keep that handle. Has no effect
   * on tables without an actions column, so only those tables need to set it.
   */
  applyDisableResizeBeforeActions?: boolean;
};

/**
 * Single source of truth for the table resize + horizontal-scroll plumbing:
 * attaches resize handles, disables the handle before a fixed actions column,
 * and sums scroll.x including the injected selection/expand columns.
 */
export const useColumnsWithResizeAndScroll = <T,>(
  orderedColumns: ColumnsType<T> | undefined,
  initialWidths: ColumnWidthsState,
  options: UseColumnsWithResizeAndScrollOptions = {},
) => {
  const {
    selectionColumnWidth,
    expandColumnWidth,
    minWidth = 80,
    applyDisableResizeBeforeActions = true,
  } = options;
  const columnResize = useTableColumnResize(initialWidths);

  const columnsWithResize = useMemo(() => {
    const resized = attachResizeToColumns(
      orderedColumns,
      columnResize.columnWidths,
      columnResize.createResizeHandlers,
      { minWidth },
    );
    return applyDisableResizeBeforeActions
      ? disableResizeBeforeActions(resized)
      : resized;
  }, [
    orderedColumns,
    columnResize.columnWidths,
    columnResize.createResizeHandlers,
    minWidth,
    applyDisableResizeBeforeActions,
  ]);

  const scrollX = useMemo(
    () =>
      sumScrollXForColumns(columnsWithResize, columnResize.columnWidths, {
        selectionColumnWidth,
        expandColumnWidth,
      }),
    [
      columnsWithResize,
      columnResize.columnWidths,
      selectionColumnWidth,
      expandColumnWidth,
    ],
  );

  return {
    columnResize,
    columnsWithResize,
    scrollX,
    components: columnResize.resizableHeaderComponents,
  };
};
