import React, { useEffect, useState } from "react";
import { Button, Checkbox } from "antd";
import { ReactSortable } from "react-sortablejs";
import { parseJsonOrDefault } from "../../misc/json-helper";
import { ACTIONS_COLUMN_KEY } from "./actionsColumn";
import { clearColumnMetadata, isColumnVisibilityLocked } from "./useColumnSettingsButton";

const COLUMN_KEYS_EXCLUDED_FROM_PICKER = new Set<string>([
  ACTIONS_COLUMN_KEY,
]);

const NON_TOGGLEABLE_VISIBILITY_KEYS = new Set<string>(["name"]);

function isVisibilityToggleDisabled(columnKey: string): boolean {
  return (
    NON_TOGGLEABLE_VISIBILITY_KEYS.has(columnKey) ||
    isColumnVisibilityLocked(columnKey)
  );
}

type SortablePickerItem = { id: unknown };

function applyPickerColumnReorder(
  setColumnsOrder: React.Dispatch<React.SetStateAction<string[]>>,
  newList: readonly SortablePickerItem[],
): void {
  const reordered = newList.map((item) => String(item.id));
  setColumnsOrder((prev) => {
    const tail = prev.filter((k) =>
      COLUMN_KEYS_EXCLUDED_FROM_PICKER.has(k),
    );
    return [...reordered, ...tail];
  });
}

export interface ColumnFilterProps {
  allColumns: string[];
  defaultColumns?: string[];
  storageKey: string;
  onChange: (columnsOrder: string[], visibleColumns: string[]) => void;
  labelsByKey?: Record<string, string>;
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
}) => {
  const initialColumns =
    defaultColumns && defaultColumns.length > 0 ? defaultColumns : allColumns;

  const [columnsOrder, setColumnsOrder] = useState<string[]>(() => {
    const stored = localStorage.getItem(getColumnsOrderKey(storageKey));
    return stored ? parseJsonOrDefault<string[]>(stored, []) : allColumns;
  });

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
    <div
      style={{
        padding: 12,
        background: "var(--vscode-editor-background, #ffffff)",
        border: "1px solid var(--vscode-border, #d9d9d9)",
        boxShadow: "var(--vscode-widget-shadow, 0 4px 12px rgba(0, 0, 0, 0.1))",
        borderRadius: 8,
        minWidth: 180,
      }}
    >
      <ReactSortable
        list={columnOrderForPicker.map((id) => ({ id }))}
        setList={(newList) =>
          applyPickerColumnReorder(setColumnsOrder, newList)
        }
        animation={150}
        handle=".drag-handle"
      >
        {columnOrderForPicker.map((key) => (
          <div
            key={key}
            style={{
              display: "flex",
              alignItems: "center",
              gap: 8,
              marginBottom: 6,
              cursor: "grab",
            }}
          >
            <span
              className="drag-handle"
              style={{
                cursor: "grab",
                display: "inline-flex",
                alignItems: "center",
                justifyContent: "center",
                width: 20,
                height: 20,
                fontSize: 20,
                marginRight: 4,
                userSelect: "none",
                borderRadius: 4,
                transition: "background 0.2s",
              }}
              tabIndex={0}
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
              style={{ flex: 1 }}
            >
              {getLabel(key)}
            </Checkbox>
          </div>
        ))}
      </ReactSortable>
      <Button
        size="small"
        onClick={handleReset}
        style={{ marginTop: 8, alignSelf: "flex-end" }}
      >
        Reset
      </Button>
    </div>
  );
};
