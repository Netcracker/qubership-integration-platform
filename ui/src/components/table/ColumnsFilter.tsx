import React, { useEffect, useState } from "react";
import { Button, Checkbox } from "antd";
import { ReactSortable } from "react-sortablejs";

interface ColumnFilterProps {
  allColumns: string[];
  defaultColumns?: string[];
  storageKey: string;
  onChange: (columnsOrder: string[], visibleColumns: string[]) => void;
}

export const ColumnsFilter: React.FC<ColumnFilterProps> = ({
  allColumns,
  defaultColumns,
  storageKey,
  onChange,
}) => {
  const initialColumns = defaultColumns && defaultColumns.length > 0 ? defaultColumns : allColumns;

  const [columnsOrder, setColumnsOrder] = useState<string[]>(() => {
    const stored = localStorage.getItem(`${storageKey}_columnsOrder`);
    return stored ? (JSON.parse(stored) as string[]) : allColumns;
  });

  const [visibleColumns, setVisibleColumns] = useState<string[]>(() => {
    const stored = localStorage.getItem(`${storageKey}_columnsVisible`);
    console.log("visibleColumns stored " + storageKey, stored);
    return stored ? (JSON.parse(stored) as string[]) : initialColumns;
  });
  useEffect(() => {
    console.log("set visibleColumns " + storageKey, visibleColumns);
    localStorage.setItem(`${storageKey}_columnsOrder`, JSON.stringify(columnsOrder));
    localStorage.setItem(`${storageKey}_columnsVisible`, JSON.stringify(visibleColumns));
    onChange?.(columnsOrder, visibleColumns);
  }, [columnsOrder, onChange, storageKey, visibleColumns]);

  const handleReset = () => {
    localStorage.removeItem(`${storageKey}_columnsOrder`);
    localStorage.removeItem(`${storageKey}_columnsVisible`);
    setColumnsOrder(allColumns);
    setVisibleColumns(initialColumns);
  };

  console.log("visibleColumns " + storageKey, visibleColumns);
  return (
    <div
      style={{
        padding: 12,
        background: '#fff',
        border: '1px solid #ddd',
        boxShadow: '0 4px 12px rgba(0, 0, 0, 0.1)',
        borderRadius: 8,
        minWidth: 180,
      }}>
      <ReactSortable
        list={columnsOrder.map(id => ({ id }))}
        setList={newList => setColumnsOrder(newList.map(item => String(item.id)))}
        animation={150}
        handle=".drag-handle"
      >
        {allColumns.map((key) => (
          <div
            key={key}
            style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 6, cursor: "grab" }}
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
              checked={visibleColumns.includes(key)}
              onChange={e => {
                if (e.target.checked) {
                  setVisibleColumns(prev => [...prev, key]);
                } else {
                  setVisibleColumns(prev => prev.filter(k => k !== key));
                }
              }}
              style={{ flex: 1 }}
            >
              {key.charAt(0).toUpperCase() + key.slice(1)}
            </Checkbox>
          </div>
        ))}
      </ReactSortable>
      <Button
        size="small"
        onClick={handleReset}
        style={{ marginTop: 8, alignSelf: 'flex-end' }}>
        Reset
      </Button>
    </div>
  )

}
