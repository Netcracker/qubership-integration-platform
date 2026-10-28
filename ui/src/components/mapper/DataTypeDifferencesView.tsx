import { Difference, DifferenceDetails } from "../../mapper/util/compare.ts";
import React, { ReactNode, useEffect, useState } from "react";
import { Table, TableProps, Tag, TagProps } from "antd";

export type DataTypeDifferencesViewProps = {
  differences: Difference[];
  style?: React.CSSProperties;
};

type TableItem = Difference & { id: number };

function getChangeActionText(difference: Difference): string {
  return difference.first
    ? difference.second
      ? "Changed"
      : "Removed"
    : "Added";
}

function getChangeActionColor(difference: Difference): TagProps["color"] {
  return difference.first ? (difference.second ? "orange" : "red") : "green";
}

const ChangeAction: React.FC<Difference> = (difference) => {
  return (
    <Tag color={getChangeActionColor(difference)}>
      {getChangeActionText(difference)}
    </Tag>
  );
};

function getDetails(details: DifferenceDetails): string {
  return `${details.feature}: ${details.first} → ${details.second}`;
}

export const DataTypeDifferencesView: React.FC<
  DataTypeDifferencesViewProps
> = ({ differences, style }): ReactNode => {
  const [tableItems, setTableItems] = useState<TableItem[]>();

  useEffect(() => {
    setTableItems(differences.map((d, i) => ({ id: i, ...d })));
  }, [differences]);

  const columns: TableProps<TableItem>["columns"] = [
    {
      key: "path",
      title: "Path",
      render: (_value, item) => item.path.join("."),
      sorter: (a, b) => a.path.join(".").localeCompare(b.path.join(".")),
      defaultSortOrder: "ascend",
    },
    {
      key: "action",
      title: "Action",
      render: (_value, item) => <ChangeAction {...item} />,
      sorter: (a, b) =>
        getChangeActionText(a).localeCompare(getChangeActionText(b)),
    },
    {
      key: "details",
      title: "Details",
      render: (_value, item) => (item.details ? getDetails(item.details) : ""),
      sorter: (a, b) => {
        const s1 = a.details ? getDetails(a.details) : "";
        const s2 = b.details ? getDetails(b.details) : "";
        return s1.localeCompare(s2);
      },
    },
  ];
  return (
    <Table<TableItem>
      style={style}
      className="flex-table"
      size="small"
      rowKey="id"
      scroll={{ y: "" }}
      pagination={false}
      dataSource={tableItems}
      columns={columns}
    />
  );
};
