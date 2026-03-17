import { useEffect, useMemo, useState } from "react";
import { ColumnSettingsButton } from "./ColumnSettingsButton";
import { ColumnsType } from "antd/lib/table";

const ACTIONS_COLUMN_NAME = "actions"; //always displayed last and not manageable

export const useColumnSettingsButton = <T,>(
  storageKey: string,
  allColumnKeys: string[],
  visibleKeys: string[],
  tableColumnDefinitions: ColumnsType<T>,
) => {
  const allColumnKeysMemo = useMemo(() => {
    return allColumnKeys;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [storageKey]);

  const visibleKeysMemo = useMemo(() => {
    return visibleKeys;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [storageKey]);

  const [columnsOrder, setColumnsOrder] = useState<string[]>(allColumnKeysMemo);
  const [visibleColumns, setVisibleColumns] =
    useState<string[]>(visibleKeysMemo);
  const hasActions = allColumnKeys.includes(ACTIONS_COLUMN_NAME);

  useEffect(() => {
    setColumnsOrder(allColumnKeys);
    setVisibleColumns(visibleKeys);
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
        return tableColumnDefinitions.find((col) => col.key === key);
      })
      .filter(Boolean)
      .map((columnType) => {
        columnType.hidden = false;
        return columnType;
      }) as ColumnsType<T>;
    if (
      hasActions &&
      !ordered.some((column) => column.key === ACTIONS_COLUMN_NAME)
    ) {
      ordered.push(
        tableColumnDefinitions.find((col) => col.key === ACTIONS_COLUMN_NAME)!,
      );
    }
    return ordered;
  }, [columnsOrder, visibleColumns, tableColumnDefinitions, hasActions]);

  const columnSettingsButton = (
    <ColumnSettingsButton
      key={storageKey + "SettingsButton"}
      allColumns={allColumnKeysMemo.filter(
        (key) => key !== ACTIONS_COLUMN_NAME,
      )}
      defaultColumns={visibleKeysMemo}
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
