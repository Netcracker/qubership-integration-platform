import { useMemo, useState } from "react";
import { getColumnsOrderKey, getColumnsVisibleKey } from "./ColumnsFilter";
import { ColumnSettingsButton } from "./ColumnSettingsButton";
import { ColumnsType } from "antd/lib/table";
import { parseJsonOrDefault } from "../../misc/json-helper";

const ACTIONS_COLUMN_NAME = "actions"; //always displayed last and not manageable

export const useColumnSettingsButton = <T,>(
  storageKey: string,
  allColumnKeys: string[],
  visibleKeys: string[],
  tableColumnDefinitions: ColumnsType<T>,
) => {
  const [columnsOrder, setColumnsOrder] = useState<string[]>(() => {
    const storedOrder = localStorage.getItem(getColumnsOrderKey(storageKey));
    return storedOrder
      ? parseJsonOrDefault<string[]>(storedOrder, [])
      : allColumnKeys;
  });
  const [visibleColumns, setVisibleColumns] = useState<string[]>(() => {
    const storedVisible = localStorage.getItem(
      getColumnsVisibleKey(storageKey),
    );
    return storedVisible
      ? parseJsonOrDefault<string[]>(storedVisible, [])
      : visibleKeys;
  });
  const hasActions = allColumnKeys.includes(ACTIONS_COLUMN_NAME);

  const handleColumnsChange = (order: string[], visible: string[]) => {
    setColumnsOrder(order);
    setVisibleColumns(visible);
  };

  const orderedColumns = useMemo(() => {
    const ordered = columnsOrder
      .filter((key) => visibleColumns.includes(key))
      .map((key) => {
        return tableColumnDefinitions.find((col) => col.key === key);
      })
      .filter(Boolean)
      .map((columnType) => {
        columnType.hidden = false;
        return columnType;
      }) as ColumnsType<T>;
    if (
      hasActions &&
      !ordered.find((column) => column.key === ACTIONS_COLUMN_NAME)
    ) {
      ordered.push(
        tableColumnDefinitions.find((col) => col.key === ACTIONS_COLUMN_NAME)!,
      );
    }
    return ordered;
  }, [columnsOrder, visibleColumns, tableColumnDefinitions, hasActions]);

  const columnSettingsButton = (
    <ColumnSettingsButton
      allColumns={allColumnKeys.filter((key) => key !== ACTIONS_COLUMN_NAME)}
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
  tableColumnDefinitions: ColumnsType<T>,
) => {
  const allColumnKeys: string[] = tableColumnDefinitions
    .filter((columnType) => columnType.key !== undefined)
    .map((columnType) => columnType.key!.toString());

  const visibleColumns: string[] = tableColumnDefinitions
    .filter((columnType) => columnType.key && columnType.hidden !== true)
    .map((columnType) => columnType.key!.toString());

  return useColumnSettingsButton(
    storageKey,
    allColumnKeys,
    visibleColumns,
    tableColumnDefinitions,
  );
};
