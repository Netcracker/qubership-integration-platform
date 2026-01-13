import React from "react";
import { Badge, Collapse, Table } from "antd";
import { TableProps } from "antd/lib/table";

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
  const columns: TableProps<KeyValueRow>["columns"] = [
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
  ];
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
              className="flex-table"
              pagination={false}
              columns={columns}
              dataSource={rows}
            />
          ),
        },
      ]}
    />
  );
};
