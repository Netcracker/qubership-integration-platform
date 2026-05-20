import React, { useMemo } from "react";
import { Badge, Collapse, Table } from "antd";
import { TableProps } from "antd/lib/table";
import {
  attachResizeToColumns,
  useTableColumnResize,
} from "../table/useTableColumnResize.tsx";
import tableStyles from "../admin_tools/domains/Tables.module.css";

type KeyValuePropertiesTableProps = {
  rows: KeyValueRow[];
};

export type KeyValueRow = {
  key: string;
  value: string;
};

export const KeyValuePropertiesTable: React.FC<
  KeyValuePropertiesTableProps
> = ({ rows }) => {
  const columns: TableProps<KeyValueRow>["columns"] = useMemo(
    () => [
      {
        title: "Key",
        dataIndex: "key",
        key: "key",
        sorter: (a, b) => a.key.localeCompare(b.key),
      },
      {
        title: "Value",
        dataIndex: "value",
        key: "value",
      },
    ],
    [],
  );

  const kvColumnResize = useTableColumnResize({
    key: 180,
  });

  const columnsWithResize = useMemo(
    () =>
      attachResizeToColumns(
        columns,
        kvColumnResize.columnWidths,
        kvColumnResize.createResizeHandlers,
        { minWidth: 80 },
      ),
    [columns, kvColumnResize.columnWidths, kvColumnResize.createResizeHandlers],
  );

  return (
    <Collapse
      items={[
        {
          label: (
            <>
              <span style={{ marginRight: 8 }}>Parameters</span>{" "}
              <Badge count={rows.length} />
            </>
          ),
          children: (
            <Table<KeyValueRow>
              size="small"
              className={`flex-table ${tableStyles.mainTable}`}
              pagination={false}
              columns={columnsWithResize}
              dataSource={rows}
              components={kvColumnResize.resizableHeaderComponents}
            />
          ),
        },
      ]}
    />
  );
};
