import React, { useMemo } from "react";
import { Button, Table, Tooltip, Typography } from "antd";
import type { Rule } from "antd/lib/form/index";
import type { TableProps } from "antd/lib/table";
import { TestingNamedParameter } from "../../api/apiTypes.ts";
import { CollapsibleSection } from "../CollapsibleSection.tsx";
import { InlineEdit } from "../InlineEdit.tsx";
import { OverridableIcon } from "../../icons/IconProvider.tsx";
import { formatOptional } from "../../misc/format-utils.ts";
import { createActionsColumnBase } from "../table/actionsColumn.ts";
import { TextValueEdit } from "../table/TextValueEdit.tsx";

type KeyedParameter = TestingNamedParameter & { key: number };

/** Returns a message when the text is not accepted, and undefined when it is. */
export type ValueValidator = (text: string) => string | undefined;

export type NameValueTableProps = {
  title: string;
  /** Names one row, as the add button says it: "Add header", "Add parameter". */
  rowNoun: string;
  values: TestingNamedParameter[] | null;
  onChange: (values: TestingNamedParameter[]) => void;
  readonly?: boolean;
  validateName?: ValueValidator;
  validateValue?: ValueValidator;
  "data-testid"?: string;
};

/** Turns a plain validator into the form rule the inline editor validates with. */
function toRules(validate: ValueValidator | undefined): Rule[] {
  if (!validate) {
    return [];
  }
  return [
    {
      validator: (_, value: string) => {
        const error = validate(value ?? "");
        return error ? Promise.reject(new Error(error)) : Promise.resolve();
      },
    },
  ];
}

/** Name and value pairs of a request: path parameters, query parameters or headers. */
export const NameValueTable: React.FC<NameValueTableProps> = ({
  title,
  rowNoun,
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

  const renderEditableCell = (
    field: "name" | "value",
    label: string,
    validate: ValueValidator | undefined,
    row: KeyedParameter,
    index: number,
  ) => {
    const error = validate?.(row[field]);
    return (
      <InlineEdit<Record<string, string>>
        values={{ [field]: row[field] }}
        editor={
          <TextValueEdit
            name={field}
            rules={toRules(validate)}
            inputProps={{ "aria-label": label }}
          />
        }
        viewer={
          error ? (
            <Typography.Text type="danger">{error}</Typography.Text>
          ) : (
            <span>{formatOptional(row[field])}</span>
          )
        }
        onSubmit={(submitted) =>
          replaceRow(index, { [field]: submitted[field] })
        }
      />
    );
  };

  const columns: TableProps<KeyedParameter>["columns"] = [
    {
      title: "Name",
      key: "name",
      // The editor of a cell is wider than the text it replaces, so without a
      // width of its own the column would resize the moment a cell is opened.
      width: "50%",
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
      width: "50%",
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
    <CollapsibleSection
      data-testid={dataTestId}
      title={title}
      count={rows.length}
      addLabel={`Add ${rowNoun}`}
      onAdd={
        readonly
          ? undefined
          : () => onChange([...rows, { name: "", value: "" }])
      }
    >
      <Table<KeyedParameter>
        size="small"
        tableLayout="fixed"
        columns={columns}
        dataSource={keyedRows}
        pagination={false}
        rowKey="key"
      />
    </CollapsibleSection>
  );
};
