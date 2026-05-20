import { useMemo } from "react";
import { disableResizeBeforeActions } from "./actionsColumn";
import {
  attachResizeToColumns,
  ColumnWidthsState,
  sumScrollXForColumns,
  useTableColumnResize,
} from "./useTableColumnResize";
import { ColumnsType } from "antd/lib/table";

export const useColumnsWithResizeAndScroll = <T,>(
  orderedColumns: ColumnsType<T>,
  initialWidths: ColumnWidthsState,
  selectionColumnWidth: number,
) => {
  const columnResize = useTableColumnResize(initialWidths);

  const columnsWithResize = useMemo(() => {
    const resized = attachResizeToColumns(
      orderedColumns,
      columnResize.columnWidths,
      columnResize.createResizeHandlers,
      { minWidth: 80 },
    );
    return disableResizeBeforeActions(resized);
  }, [
    orderedColumns,
    columnResize.columnWidths,
    columnResize.createResizeHandlers,
  ]);

  const scrollX = useMemo(
    () =>
      sumScrollXForColumns(columnsWithResize, columnResize.columnWidths, {
        selectionColumnWidth,
      }),
    [columnsWithResize, columnResize.columnWidths, selectionColumnWidth],
  );

  return { columnResize, columnsWithResize, scrollX };
};
