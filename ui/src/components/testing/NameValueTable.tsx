import React, { useMemo } from "react";
import { Button, Flex, Input, Table, Tooltip, Typography } from "antd";
import type { TableProps } from "antd/lib/table";
import { TestingNamedParameter } from "../../api/apiTypes.ts";
import { OverridableIcon } from "../../icons/IconProvider.tsx";
import { tableEmpty } from "../table/tableEmpty.tsx";

export type NameValueTableProps = {
  title: string;
  values: TestingNamedParameter[] | null;
  onChange: (values: TestingNamedParameter[]) => void;
  readonly?: boolean;
  "data-testid"?: string;
};

/** Name and value pairs of a request: path parameters, query parameters or headers. */
export const NameValueTable: React.FC<NameValueTableProps> = ({
  title,
  values,
  onChange,
  readonly = false,
  "data-testid": dataTestId,
}) => {
  const rows = useMemo(() => values ?? [], [values]);

  const replaceRow = (index: number, changes: Partial<TestingNamedParameter>) =>
    onChange(
      rows.map((row, i) => (i === index ? { ...row, ...changes } : row)),
    );

  const removeRow = (index: number) =>
    onChange(rows.filter((_, i) => i !== index));

  const columns: TableProps<TestingNamedParameter>["columns"] = [
    {
      title: "Name",
      key: "name",
      render: (_, row, index) =>
        readonly ? (
          <>{row.name || "-"}</>
        ) : (
          <Input
            value={row.name}
            aria-label="Name"
            onChange={(event) =>
              replaceRow(index, { name: event.target.value })
            }
          />
        ),
    },
    {
      title: "Value",
      key: "value",
      render: (_, row, index) =>
        readonly ? (
          <>{row.value || "-"}</>
        ) : (
          <Input
            value={row.value}
            aria-label="Value"
            onChange={(event) =>
              replaceRow(index, { value: event.target.value })
            }
          />
        ),
    },
    ...(readonly
      ? []
      : [
          {
            title: "",
            key: "actions",
            width: 48,
            className: "actions-column",
            render: (_: unknown, __: TestingNamedParameter, index: number) => (
              <Tooltip title="Delete">
                <Button
                  type="text"
                  aria-label="Delete"
                  icon={<OverridableIcon name="delete" />}
                  onClick={() => removeRow(index)}
                />
              </Tooltip>
            ),
          },
        ]),
  ];

  return (
    <div data-testid={dataTestId}>
      <Flex align="center" justify="space-between" gap={8}>
        <Typography.Text strong>{title}</Typography.Text>
        {readonly ? null : (
          <Button
            size="small"
            icon={<OverridableIcon name="plus" />}
            onClick={() => onChange([...rows, { name: "", value: "" }])}
          >
            Add
          </Button>
        )}
      </Flex>
      <Table<TestingNamedParameter>
        size="small"
        columns={columns}
        dataSource={rows}
        pagination={false}
        rowKey={(_, index) => index ?? 0}
        locale={{ emptyText: tableEmpty(`No ${title.toLowerCase()}`) }}
      />
    </div>
  );
};

export default NameValueTable;
