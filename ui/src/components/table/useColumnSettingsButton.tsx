import { useEffect, useMemo, useState } from "react";
import { ColumnSettingsButton } from "./ColumnSettingsButton";
import { ColumnsType, ColumnType } from "antd/lib/table";
import { getColumnsOrderKey, getColumnsVisibleKey } from "./ColumnsFilter";
import { parseJsonOrDefault } from "../../misc/json-helper";
import { ACTIONS_COLUMN_KEY } from "./actionsColumn.ts";

export type ColumnsTypeWithSettings<T> = ColumnTypeWithSettings<T>[];

export type ColumnSettings = {
  visibilityLocked?: boolean;
};

export type ColumnTypeWithSettings<T> = ColumnType<T> & {
  settings?: ColumnSettings;
};

const COLUMN_VISIBILITY_LOCKED_SUFFIX = "*";

export const isColumnVisibilityLocked = (key: string) => {
  return key.endsWith(COLUMN_VISIBILITY_LOCKED_SUFFIX);
};

export const clearColumnMetadata = (key: string) => {
  return isColumnVisibilityLocked(key) ? key.slice(0, -1) : key;
};

const buildKeyWithMetadata = (columnType: ColumnTypeWithSettings<T>): string => {
  const columnString = columnType.key!.toString();
  return columnType.settings?.visibilityLocked
    ? columnString + COLUMN_VISIBILITY_LOCKED_SUFFIX
    : columnString;
};

export const useColumnSettingsButton = <T,>(
  storageKey: string,
  allColumnKeys: string[],
  visibleKeys: string[],
  tableColumnDefinitions: ColumnsType<T>,
) => {
  const allColumnKeysMemo = useMemo(() => {
    const storedOrder = localStorage.getItem(getColumnsOrderKey(storageKey));
    return storedOrder
      ? parseJsonOrDefault<string[]>(storedOrder, [])
      : allColumnKeys;

    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [storageKey]);

  const visibleKeysMemo = useMemo(() => {
    const storedVisible = localStorage.getItem(
      getColumnsVisibleKey(storageKey),
    );
    return storedVisible
      ? parseJsonOrDefault<string[]>(storedVisible, [])
      : visibleKeys;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [storageKey]);

  const [columnsOrder, setColumnsOrder] = useState<string[]>(allColumnKeysMemo);
  const [visibleColumns, setVisibleColumns] =
    useState<string[]>(visibleKeysMemo);
  const hasActions = allColumnKeys.includes(ACTIONS_COLUMN_KEY);

  useEffect(() => {
    setColumnsOrder(allColumnKeysMemo);
    setVisibleColumns(visibleKeysMemo);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [storageKey]);

  const handleColumnsChange = (order: string[], visible: string[]) => {
    setColumnsOrder(order);
    setVisibleColumns(visible);
  };

  const orderedColumns = useMemo(() => {
    const ordered = columnsOrder
      .filter((key) => visibleColumns.includes(key))
      .map((key) => {
        return tableColumnDefinitions.find(
          (col) => col.key === clearColumnMetadata(key),
        );
      })
      .filter(Boolean)
      .map((columnType) => ({
        ...columnType,
        hidden: false,
      })) as ColumnsType<T>;
    if (
      hasActions &&
      !ordered.some((column) => column.key === ACTIONS_COLUMN_KEY)
    ) {
      ordered.push(
        tableColumnDefinitions.find((col) => col.key === ACTIONS_COLUMN_KEY)!,
      );
    }
    return ordered;
  }, [columnsOrder, visibleColumns, tableColumnDefinitions, hasActions]);

  const columnSettingsButton = (
    <ColumnSettingsButton
      key={storageKey + "SettingsButton"}
      allColumns={allColumnKeysMemo.filter((key) => key !== ACTIONS_COLUMN_KEY)}
      defaultColumns={visibleKeys}
      storageKey={storageKey}
      labelsByKey={Object.fromEntries(
        tableColumnDefinitions.map((c) => [c.key as string, c.title as string]),
      )}
      onChange={handleColumnsChange}
    />
  );
  return { orderedColumns, columnSettingsButton };
};

export const useColumnSettingsBasedOnColumnsType = <T,>(
  storageKey: string,
  tableColumnDefinitions: ColumnsTypeWithSettings<T>,
) => {
  const allColumnKeys: string[] = tableColumnDefinitions
    .filter((columnType) => columnType.key !== undefined)
    .map((columnType) => buildKeyWithMetadata(columnType));

  const visibleColumns: string[] = tableColumnDefinitions
    .filter((columnType) => columnType.key && columnType.hidden !== true)
    .map((columnType) => buildKeyWithMetadata(columnType));

  return useColumnSettingsButton(
    storageKey,
    allColumnKeys,
    visibleColumns,
    tableColumnDefinitions,
  );
};
