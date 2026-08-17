import React, { useMemo } from "react";
import { Button, Flex, Input, Table, Tooltip, Typography } from "antd";
import type { TableProps } from "antd/lib/table";
import { TestingNamedParameter } from "../../api/apiTypes.ts";
import { OverridableIcon } from "../../icons/IconProvider.tsx";
import { formatOptional } from "../../misc/format-utils.ts";
import { createActionsColumnBase } from "../table/actionsColumn.ts";
import { tableEmpty } from "../table/tableEmpty.tsx";

type KeyedParameter = TestingNamedParameter & { key: number };

/** Returns a message when the text is not accepted, and undefined when it is. */
export type ValueValidator = (text: string) => string | undefined;

export type NameValueTableProps = {
  title: string;
  values: TestingNamedParameter[] | null;
  onChange: (values: TestingNamedParameter[]) => void;
  readonly?: boolean;
  validateName?: ValueValidator;
  validateValue?: ValueValidator;
  "data-testid"?: string;
};

/** Name and value pairs of a request: path parameters, query parameters or headers. */
export const NameValueTable: React.FC<NameValueTableProps> = ({
  title,
  values,
  onChange,
  readonly = false,
  validateName,
  validateValue,
  "data-testid": dataTestId,
}) => {
  const rows = useMemo(() => values ?? [], [values]);
  // Pairs carry no id, so the row key comes from a keyed copy rather than from
  // the row index, which antd deprecated on `rowKey`.
  const keyedRows = useMemo<KeyedParameter[]>(
    () => rows.map((row, index) => ({ ...row, key: index })),
    [rows],
  );

  const replaceRow = (index: number, changes: Partial<TestingNamedParameter>) =>
    onChange(
      rows.map((row, i) => (i === index ? { ...row, ...changes } : row)),
    );

  const removeRow = (index: number) =>
    onChange(rows.filter((_, i) => i !== index));

  // A raw Input rather than InlineEdit: a pair is validated as it is typed, and
  // InlineEdit commits on Enter alone, which would hide the message until then.
  const renderEditableCell = (
    field: "name" | "value",
    label: string,
    validate: ValueValidator | undefined,
    row: KeyedParameter,
    index: number,
  ) => {
    const error = validate?.(row[field]);
    return (
      <>
        <Input
          value={row[field]}
          aria-label={label}
          status={error ? "error" : undefined}
          onChange={(event) =>
            replaceRow(index, { [field]: event.target.value })
          }
        />
        {error ? (
          <Typography.Text type="danger">{error}</Typography.Text>
        ) : null}
      </>
    );
  };

  const columns: TableProps<KeyedParameter>["columns"] = [
    {
      title: "Name",
      key: "name",
      render: (_, row, index) =>
        readonly ? (
          <>{formatOptional(row.name)}</>
        ) : (
          renderEditableCell("name", "Name", validateName, row, index)
        ),
    },
    {
      title: "Value",
      key: "value",
      render: (_, row, index) =>
        readonly ? (
          <>{formatOptional(row.value)}</>
        ) : (
          renderEditableCell("value", "Value", validateValue, row, index)
        ),
    },
    ...(readonly
      ? []
      : [
          {
            ...createActionsColumnBase<KeyedParameter>(),
            render: (_: unknown, __: KeyedParameter, index: number) => (
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
      <Table<KeyedParameter>
        size="small"
        columns={columns}
        dataSource={keyedRows}
        pagination={false}
        rowKey="key"
        locale={{ emptyText: tableEmpty(`No ${title.toLowerCase()}`) }}
      />
    </div>
  );
};
