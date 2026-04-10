import React, { ReactNode, useEffect, useMemo, useState } from "react";
import { Flex, Switch, Table } from "antd";
import { TableProps } from "antd/lib/table";
import { PLACEHOLDER } from "../../misc/format-utils.ts";
import {
  attachResizeToColumns,
  sumScrollXForColumns,
  useTableColumnResize,
} from "../table/useTableColumnResize.tsx";
import styles from "./SessionElementKVChanges.module.css";

type ValueRenderer<ValueType = unknown> = (value: ValueType) => ReactNode;

type Comparator<ValueType = unknown> = (
  v1: ValueType | undefined,
  v2: ValueType | undefined,
) => number;

export type ColumnName =
  | "name"
  | "typeBefore"
  | "typeAfter"
  | "valueBefore"
  | "valueAfter";

type SessionElementKVChangesProps<ValueType = unknown> = {
  before?: Record<string, ValueType>;
  after?: Record<string, ValueType>;
  addTypeColumns?: boolean;
  comparator?: Comparator<ValueType>;
  typeRenderer?: ValueRenderer<ValueType>;
  valueRenderer?: ValueRenderer<ValueType>;
  onColumnClick?: (
    item: KVChangesTableItem<ValueType>,
    column: ColumnName,
  ) => void;
};

export type KVChangesTableItem<ValueType = unknown> = {
  name: string;
  before: ValueType | undefined;
  after: ValueType | undefined;
};

function buildItems<ValueType = unknown>(
  before: Record<string, ValueType> | undefined,
  after: Record<string, ValueType> | undefined,
  onlyModified: boolean,
  comparator?: Comparator<ValueType>,
): KVChangesTableItem<ValueType>[] {
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

function getCellContent<ValueType = unknown>(
  value: ValueType | undefined,
  renderer: ValueRenderer<ValueType> | undefined,
): ReactNode {
  return value !== undefined
    ? renderer
      ? renderer(value)
      : String(value)
    : PLACEHOLDER;
}

function isItemModified<ValueType>(
  item: KVChangesTableItem<ValueType>,
  comparator?: Comparator<ValueType>,
): boolean {
  if (comparator) {
    return comparator(item.before, item.after) !== 0;
  }
  return item.before !== item.after;
}

/** Compare rendered type strings (uses PLACEHOLDER for missing side). */
function typeDisplayString<ValueType>(
  value: ValueType | undefined,
  typeRenderer: ValueRenderer<ValueType> | undefined,
): string {
  if (value === undefined) {
    return PLACEHOLDER;
  }
  if (typeRenderer) {
    const rendered = typeRenderer(value);
    if (
      typeof rendered === "string" ||
      typeof rendered === "number" ||
      typeof rendered === "boolean"
    ) {
      return String(rendered);
    }
  }
  if (
    typeof value === "string" ||
    typeof value === "number" ||
    typeof value === "boolean"
  ) {
    return String(value);
  }
  return PLACEHOLDER;
}

function sessionElementTypesDiffer<ValueType>(
  item: KVChangesTableItem<ValueType>,
  typeRenderer: ValueRenderer<ValueType> | undefined,
): boolean {
  return (
    typeDisplayString(item.before, typeRenderer) !==
    typeDisplayString(item.after, typeRenderer)
  );
}

function renderTypeColumnCell<ValueType>(
  item: KVChangesTableItem<ValueType>,
  side: "before" | "after",
  typeRenderer: ValueRenderer<ValueType> | undefined,
): ReactNode {
  const value = side === "before" ? item.before : item.after;
  const inner = getCellContent(value, typeRenderer);
  if (!sessionElementTypesDiffer(item, typeRenderer)) {
    return inner;
  }
  return <span className={styles.valueChanged}>{inner}</span>;
}

function renderValueColumnCell<ValueType>(
  item: KVChangesTableItem<ValueType>,
  side: "before" | "after",
  valueRenderer: ValueRenderer<ValueType> | undefined,
  comparator: Comparator<ValueType> | undefined,
): ReactNode {
  const value = side === "before" ? item.before : item.after;
  const inner = getCellContent(value, valueRenderer);
  if (!isItemModified(item, comparator)) {
    return inner;
  }
  return <span className={styles.valueChanged}>{inner}</span>;
}

export const SessionElementKVChanges = <ValueType = unknown,>({
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
  const [items, setItems] = useState<KVChangesTableItem<ValueType>[]>([]);
  const [onlyModified, setOnlyModified] = useState(false);

  useEffect(() => {
    setItems(buildItems(before, after, onlyModified, comparator));
  }, [before, after, comparator, onlyModified]);

  const columns: TableProps<KVChangesTableItem<ValueType>>["columns"] = useMemo(
    () => [
      {
        title: "Name",
        dataIndex: "name",
        key: "name",
        onCell: (item) => ({
          onClick: () => onColumnClick?.(item, "name"),
        }),
      },
      ...(addTypeColumns
        ? [
            {
              title: "Type Before",
              key: "typeBefore",
              ellipsis: true,
              onCell: (item: KVChangesTableItem<ValueType>) => ({
                onClick: () => onColumnClick?.(item, "typeBefore"),
              }),
              render: (_: unknown, item: KVChangesTableItem<ValueType>) =>
                renderTypeColumnCell(item, "before", typeRenderer),
            },
          ]
        : []),
      {
        title: "Value Before",
        dataIndex: "before",
        key: "before",
        ellipsis: true,
        onCell: (item) => ({
          onClick: () => onColumnClick?.(item, "valueBefore"),
        }),
        render: (_, item) =>
          renderValueColumnCell(item, "before", valueRenderer, comparator),
      },
      ...(addTypeColumns
        ? [
            {
              title: "Type After",
              key: "typeAfter",
              ellipsis: true,
              onCell: (item: KVChangesTableItem<ValueType>) => ({
                onClick: () => onColumnClick?.(item, "typeAfter"),
              }),
              render: (_: unknown, item: KVChangesTableItem<ValueType>) =>
                renderTypeColumnCell(item, "after", typeRenderer),
            },
          ]
        : []),
      {
        title: "Value After",
        dataIndex: "after",
        key: "after",
        ellipsis: true,
        onCell: (item) => ({
          onClick: () => onColumnClick?.(item, "valueAfter"),
        }),
        render: (_, item) =>
          renderValueColumnCell(item, "after", valueRenderer, comparator),
      },
    ],
    [addTypeColumns, comparator, onColumnClick, typeRenderer, valueRenderer],
  );

  const kvChangesColumnResize = useTableColumnResize({
    name: 180,
    typeBefore: 140,
    before: 220,
    typeAfter: 140,
    after: 220,
  });

  const columnsWithResize = useMemo(
    () =>
      attachResizeToColumns(
        columns,
        kvChangesColumnResize.columnWidths,
        kvChangesColumnResize.createResizeHandlers,
        { minWidth: 80 },
      ),
    [
      columns,
      kvChangesColumnResize.columnWidths,
      kvChangesColumnResize.createResizeHandlers,
    ],
  );

  const scrollX = useMemo(
    () =>
      sumScrollXForColumns(
        columnsWithResize,
        kvChangesColumnResize.columnWidths,
      ),
    [columnsWithResize, kvChangesColumnResize.columnWidths],
  );

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
        columns={columnsWithResize}
        dataSource={items}
        className="flex-table"
        scroll={items.length > 0 ? { x: scrollX, y: "" } : { x: scrollX }}
        components={kvChangesColumnResize.resizableHeaderComponents}
      ></Table>
    </Flex>
  );
};
