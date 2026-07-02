import React, { useCallback, useEffect, useMemo, useState } from "react";
import { Button, Checkbox } from "antd";
import clsx from "clsx";
import { ReactSortable } from "react-sortablejs";
import { parseJsonOrDefault } from "../../misc/json-helper";
import { ACTIONS_COLUMN_KEY } from "./actionsColumn";
import {
  clearColumnMetadata,
  isColumnVisibilityLocked,
} from "./useColumnSettingsButton";
import styles from "./ColumnsFilter.module.css";

const COLUMN_KEYS_EXCLUDED_FROM_PICKER = new Set<string>([ACTIONS_COLUMN_KEY]);

const NON_TOGGLEABLE_VISIBILITY_KEYS = new Set<string>(["name"]);

function isVisibilityToggleDisabled(columnKey: string): boolean {
  return (
    NON_TOGGLEABLE_VISIBILITY_KEYS.has(columnKey) ||
    isColumnVisibilityLocked(columnKey)
  );
}

type SortablePickerItem = { id: unknown };

function buildOrderLockedPrefix(
  allColumns: string[],
  orderLockedKeySet: ReadonlySet<string>,
): string[] {
  return allColumns.filter((key) => orderLockedKeySet.has(key));
}

function normalizePickerColumnOrder(
  order: string[],
  allColumns: string[],
  orderLockedKeySet: ReadonlySet<string>,
): string[] {
  const lockedPrefix = buildOrderLockedPrefix(allColumns, orderLockedKeySet);
  if (lockedPrefix.length === 0) {
    return order;
  }
  const tail = order.filter((key) => COLUMN_KEYS_EXCLUDED_FROM_PICKER.has(key));
  const movable = order.filter(
    (key) =>
      !orderLockedKeySet.has(key) && !COLUMN_KEYS_EXCLUDED_FROM_PICKER.has(key),
  );
  return [...lockedPrefix, ...movable, ...tail];
}

function applyPickerColumnReorder(
  setColumnsOrder: React.Dispatch<React.SetStateAction<string[]>>,
  newList: readonly SortablePickerItem[],
  allColumns: string[],
  orderLockedKeySet: ReadonlySet<string>,
): void {
  const reordered = newList.map((item) => String(item.id));
  setColumnsOrder((prev) => {
    const tail = prev.filter((key) =>
      COLUMN_KEYS_EXCLUDED_FROM_PICKER.has(key),
    );
    const lockedPrefix = buildOrderLockedPrefix(allColumns, orderLockedKeySet);
    const movable = reordered.filter((key) => !orderLockedKeySet.has(key));
    return [...lockedPrefix, ...movable, ...tail];
  });
}

export interface ColumnFilterProps {
  allColumns: string[];
  defaultColumns?: string[];
  storageKey: string;
  onChange: (columnsOrder: string[], visibleColumns: string[]) => void;
  labelsByKey?: Record<string, string>;
  orderLockedKeys?: string[];
}

export const getColumnsOrderKey = (storageKey: string): string => {
  return `${storageKey}_columnsOrder`;
};

export const getColumnsVisibleKey = (storageKey: string): string => {
  return `${storageKey}_columnsVisible`;
};

export const ColumnsFilter: React.FC<ColumnFilterProps> = ({
  allColumns,
  defaultColumns,
  storageKey,
  onChange,
  labelsByKey,
  orderLockedKeys = [],
}) => {
  const orderLockedKeySet = useMemo(
    () => new Set(orderLockedKeys),
    [orderLockedKeys],
  );
  const orderLockedPrefix = useMemo(
    () => buildOrderLockedPrefix(allColumns, orderLockedKeySet),
    [allColumns, orderLockedKeySet],
  );
  const initialColumns =
    defaultColumns && defaultColumns.length > 0 ? defaultColumns : allColumns;

  const [columnsOrder, setColumnsOrder] = useState<string[]>(() => {
    const stored = localStorage.getItem(getColumnsOrderKey(storageKey));
    const order = stored
      ? parseJsonOrDefault<string[]>(stored, [])
      : allColumns;
    return normalizePickerColumnOrder(order, allColumns, orderLockedKeySet);
  });

  const handlePickerMove = useCallback(
    (evt: { newIndex?: number; related?: Element }) => {
      if (orderLockedPrefix.length === 0) {
        return true;
      }
      if (
        evt.newIndex !== undefined &&
        evt.newIndex < orderLockedPrefix.length
      ) {
        return false;
      }
      if (evt.related?.classList.contains("filtered")) {
        return false;
      }
      return true;
    },
    [orderLockedPrefix.length],
  );

  const [visibleColumns, setVisibleColumns] = useState<string[]>(() => {
    const stored = localStorage.getItem(getColumnsVisibleKey(storageKey));
    return stored ? parseJsonOrDefault<string[]>(stored, []) : initialColumns;
  });
  useEffect(() => {
    localStorage.setItem(
      getColumnsOrderKey(storageKey),
      JSON.stringify(columnsOrder),
    );
    localStorage.setItem(
      getColumnsVisibleKey(storageKey),
      JSON.stringify(visibleColumns),
    );
    onChange?.(columnsOrder, visibleColumns);
  }, [columnsOrder, onChange, storageKey, visibleColumns]);

  const handleReset = () => {
    localStorage.removeItem(getColumnsOrderKey(storageKey));
    localStorage.removeItem(getColumnsVisibleKey(storageKey));
    setColumnsOrder(allColumns);
    setVisibleColumns(initialColumns);
  };

  const humanizeKey = (key: string): string => {
    const withSpaces = clearColumnMetadata(key)
      .replace(/[_-]+/g, " ")
      .replace(/([a-z0-9])([A-Z])/g, "$1 $2");
    return withSpaces
      .split(" ")
      .filter(Boolean)
      .map((part) => {
        const lower = part.toLowerCase();
        if (lower === "id") return "ID";
        if (lower === "url") return "URL";
        if (lower === "api") return "API";
        return part.charAt(0).toUpperCase() + part.slice(1);
      })
      .join(" ");
  };

  const getLabel = (key: string): string => {
    const fromMap = labelsByKey?.[key];
    if (typeof fromMap === "string" && fromMap.length > 0) return fromMap;
    return humanizeKey(key);
  };

  const columnOrderForPicker = columnsOrder.filter(
    (key) => !COLUMN_KEYS_EXCLUDED_FROM_PICKER.has(key),
  );

  return (
    <div className={styles.panel}>
      <ReactSortable
        list={columnOrderForPicker.map((id) => ({ id }))}
        setList={(newList) =>
          applyPickerColumnReorder(
            setColumnsOrder,
            newList,
            allColumns,
            orderLockedKeySet,
          )
        }
        animation={150}
        handle=".drag-handle"
        filter=".filtered"
        onMove={handlePickerMove}
      >
        {columnOrderForPicker.map((key) => {
          const isOrderLocked = orderLockedKeySet.has(key);
          return (
            <div
              key={key}
              className={clsx(
                styles.row,
                isOrderLocked && "filtered",
                isOrderLocked && styles.rowOrderLocked,
              )}
            >
              <span
                className={clsx(
                  styles.grip,
                  isOrderLocked ? "filtered" : "drag-handle",
                  isOrderLocked && styles.gripOrderLocked,
                )}
                aria-hidden={isOrderLocked}
                tabIndex={isOrderLocked ? -1 : 0}
              >
                ≡
              </span>
              <Checkbox
                disabled={isVisibilityToggleDisabled(key)}
                checked={visibleColumns.includes(key)}
                onChange={(e) => {
                  if (e.target.checked) {
                    setVisibleColumns((prev) => [...prev, key]);
                  } else {
                    setVisibleColumns((prev) => prev.filter((k) => k !== key));
                  }
                }}
                className={styles.checkbox}
              >
                {getLabel(key)}
              </Checkbox>
            </div>
          );
        })}
      </ReactSortable>
      <Button size="small" onClick={handleReset} className={styles.resetButton}>
        Reset
      </Button>
    </div>
  );
};
