import React, { ReactNode, useEffect, useState } from "react";
import { Flex, Switch, Table } from "antd";
import { TableProps } from "antd/lib/table";
import { PLACEHOLDER } from "../../misc/format-utils.ts";

type ValueRenderer<ValueType = any> = (value: ValueType) => ReactNode;

type Comparator<ValueType = any> = (
  v1: ValueType | undefined,
  v2: ValueType | undefined,
) => number;

export type ColumnName = "name" | "typeBefore" | "typeAfter" | "valueBefore" | "valueAfter";

type SessionElementKVChangesProps<ValueType = any> = {
  before?: Record<string, ValueType>;
  after?: Record<string, ValueType>;
  addTypeColumns?: boolean;
  comparator?: Comparator<ValueType>;
  typeRenderer?: ValueRenderer<ValueType>;
  valueRenderer?: ValueRenderer<ValueType>;
  onColumnClick?: (item: KVChangesTableItem<ValueType>, column: ColumnName) => void;
};

export type KVChangesTableItem<ValueType = any> = {
  name: string;
  before: ValueType | undefined;
  after: ValueType | undefined;
};

function buildItems<ValueType = any>(
  before: Record<string, ValueType> | undefined,
  after: Record<string, ValueType> | undefined,
  onlyModified: boolean,
  comparator?: Comparator<ValueType>,
): KVChangesTableItem[] {
  const keys = new Set([
    ...Object.keys(before ?? {}),
    ...Object.keys(after ?? {}),
  ]);
  return Array.from(keys.values())
    .map((key) => ({
      name: key,
      before: before?.[key],
      after: after?.[key],
    }))
    .filter(
      (item) =>
        !onlyModified ||
        (comparator && comparator(item.before, item.after) !== 0) ||
        (!comparator && item.before !== item.after),
    );
}

function getCellContent<ValueType = any>(
  value: ValueType | undefined,
  renderer: ValueRenderer<ValueType> | undefined,
): ReactNode {
  return value !== undefined
    ? renderer
      ? renderer(value)
      : `${value}`
    : PLACEHOLDER;
}

export const SessionElementKVChanges = <ValueType = any,>({
  before,
  after,
  addTypeColumns,
  comparator,
  typeRenderer,
  valueRenderer,
  onColumnClick,
  ...rest
}: SessionElementKVChangesProps<ValueType> &
  React.HTMLAttributes<HTMLElement>): ReactNode => {
  const [items, setItems] = useState<KVChangesTableItem[]>([]);
  const [onlyModified, setOnlyModified] = useState(false);

  useEffect(() => {
    setItems(buildItems(before, after, onlyModified, comparator));
  }, [before, after, comparator, onlyModified]);

  const columns: TableProps<KVChangesTableItem<ValueType>>["columns"] = [
    {
      title: "Name",
      dataIndex: "name",
      key: "name",
      onCell: (item) => ({
        onClick: () => onColumnClick?.(item,  "name"),
      }),
    },
    ...(addTypeColumns
      ? [
          {
            title: "Type Before",
            key: "typeBefore",
            ellipsis: true,
            onCell: (item: KVChangesTableItem) => ({
              onClick: () => onColumnClick?.(item,  "typeBefore"),
            }),
            render: (_: any, item: KVChangesTableItem<ValueType>) =>
              getCellContent(item.before, typeRenderer),
          },
        ]
      : []),
    {
      title: "Value Before",
      dataIndex: "before",
      key: "before",
      ellipsis: true,
      onCell: (item) => ({
        onClick: () => onColumnClick?.(item,  "valueBefore"),
      }),
      render: (_, item) => getCellContent(item.before, valueRenderer),
    },
    ...(addTypeColumns
      ? [
          {
            title: "Type After",
            key: "typeAfter",
            ellipsis: true,
            onCell: (item: KVChangesTableItem) => ({
              onClick: () => onColumnClick?.(item,  "typeAfter"),
            }),
            render: (_: any, item: KVChangesTableItem<ValueType>) =>
              getCellContent(item.after, typeRenderer),
          },
        ]
      : []),
    {
      title: "Value After",
      dataIndex: "after",
      key: "after",
      ellipsis: true,
      onCell: (item) => ({
        onClick: () => onColumnClick?.(item,  "valueAfter"),
      }),
      render: (_, item) => getCellContent(item.after, valueRenderer),
    },
  ];

  return (
    <Flex {...rest} vertical gap={16}>
      <Flex gap={8} vertical={false} justify="right" align="center">
        <Switch
          value={onlyModified}
          onClick={(checked) => setOnlyModified(checked)}
        />
        <span>Only modified</span>
      </Flex>
      <Table<KVChangesTableItem<ValueType>>
        size="small"
        pagination={false}
        rowKey="name"
        columns={columns}
        dataSource={items}
        className="flex-table"
        scroll={{ y: "" }}
      ></Table>
    </Flex>
  );
};
