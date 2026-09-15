import { useCallback, useMemo } from "react";
import { disableResizeBeforeActions } from "./actionsColumn";
import {
  attachResizeToColumns,
  ColumnWidthsState,
  sumScrollXForColumns,
  useTableColumnResize,
} from "./useTableColumnResize";
import type { TableProps } from "antd";
import type { ColumnsType } from "antd/lib/table";
import type {
  ColumnType,
  FilterValue,
  SortOrder,
} from "antd/es/table/interface";
import { useTableSetting } from "./useTableSetting";

type PersistedTableSort = {
  key: string;
  order: SortOrder;
};

export type ControlledTableSort = {
  key?: string;
  order?: SortOrder;
};

export type UseTableConfigurationOptions<T> = {
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
  /** Sorting owned by a server-backed list. It is displayed but not persisted here. */
  controlledSort?: ControlledTableSort;
  /** Page-specific change handler, called after client table settings are stored. */
  onChange?: TableProps<T>["onChange"];
};

/**
 * Single source of truth for the table resize + horizontal-scroll plumbing:
 * attaches resize handles, disables the handle before a fixed actions column,
 * and sums scroll.x including the injected selection/expand columns.
 */
export const useTableConfiguration = <T extends object>(
  orderedColumns: ColumnsType<T> | undefined,
  initialWidths: ColumnWidthsState,
  options: UseTableConfigurationOptions<T> = {},
  storageKey?: string,
) => {
  const {
    selectionColumnWidth,
    expandColumnWidth,
    minWidth = 80,
    applyDisableResizeBeforeActions = true,
    controlledSort,
    onChange,
  } = options;
  const columnResize = useTableColumnResize(initialWidths, storageKey);
  const [persistedSort, setPersistedSort] = useTableSetting<
    PersistedTableSort[]
  >(
    controlledSort === undefined ? storageKey : undefined,
    "sort",
    (orderedColumns ?? []).flatMap((column) => {
      const c = column as ColumnType<T>;
      return c.defaultSortOrder
        ? [{ key: String(c.key), order: c.defaultSortOrder }]
        : [];
    }),
  );
  const [columnFilters, setColumnFilters] = useTableSetting<
    Record<string, FilterValue | null>
  >(storageKey, "columnFilters", {});
  const activeSort = useMemo(
    () =>
      controlledSort === undefined
        ? persistedSort
        : controlledSort.key && controlledSort.order
          ? [{ key: controlledSort.key, order: controlledSort.order }]
          : [],
    [controlledSort, persistedSort],
  );

  const columnsWithResize = useMemo<ColumnsType<T>>(() => {
    const resized = attachResizeToColumns(
      orderedColumns,
      columnResize.columnWidths,
      columnResize.createResizeHandlers,
      { minWidth },
    );
    const columns = applyDisableResizeBeforeActions
      ? disableResizeBeforeActions(resized)
      : resized;
    return columns.map((column) => {
      const c = column as ColumnType<T>;
      const key = String(c.key);
      return {
        ...c,
        ...(c.sorter && c.sortOrder === undefined
          ? {
              sortOrder:
                activeSort.find((sort) => sort.key === key)?.order ?? null,
            }
          : {}),
        ...(c.filteredValue === undefined && (c.filters || c.filterDropdown)
          ? {
              filteredValue:
                columnFilters[key] ?? c.defaultFilteredValue ?? null,
            }
          : {}),
      };
    });
  }, [
    orderedColumns,
    columnResize.columnWidths,
    columnResize.createResizeHandlers,
    minWidth,
    applyDisableResizeBeforeActions,
    activeSort,
    columnFilters,
  ]);

  const handleTableChange = useCallback<NonNullable<TableProps<T>["onChange"]>>(
    (pagination, filters, sorter, extra) => {
      setColumnFilters(filters);
      if (controlledSort === undefined) {
        const sorts = Array.isArray(sorter) ? sorter : [sorter];
        setPersistedSort(
          sorts
            .filter((sort) => sort.order)
            .map((sort) => ({
              key: String(sort.columnKey),
              order: sort.order!,
            })),
        );
      }
      onChange?.(pagination, filters, sorter, extra);
    },
    [controlledSort, onChange, setColumnFilters, setPersistedSort],
  );

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
    handleTableChange,
  };
};
