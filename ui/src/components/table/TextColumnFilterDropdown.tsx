import { FilterDropdownProps } from "antd/lib/table/interface";
import React, { ReactNode, useCallback, useMemo } from "react";
import { Button, Col, Input, Row, Select, SelectProps } from "antd";
import type { AnyObject } from "antd/lib/_util/type";
import { parseJson } from "../../misc/json-helper.ts";

export type TextFilterCondition =
  | "contains"
  | "not-contains"
  | "starts-with"
  | "ends-with"
  | "is"
  | "is-not";

export type TextFilter = {
  condition: TextFilterCondition;
  value: string;
};

export function isTextFilter(obj: unknown): obj is TextFilter {
  return (
    typeof obj === "object" &&
    obj !== null &&
    "condition" in obj &&
    typeof obj.condition === "string" &&
    (obj.condition as TextFilterCondition) !== undefined &&
    (!("value" in obj) || typeof obj.value === "string")
  );
}

function getTextFilterFunction(
  condition: TextFilterCondition,
): (filter: string, value: string) => boolean {
  switch (condition) {
    case "contains":
      return (f, v) => !f || v?.toLowerCase().includes(f.toLowerCase());
    case "not-contains":
      return (f, v) => !f || !v?.toLowerCase().includes(f.toLowerCase());
    case "starts-with":
      return (f, v) => !f || v?.toLowerCase().startsWith(f.toLowerCase());
    case "ends-with":
      return (f, v) => !f || v?.toLowerCase().endsWith(f.toLowerCase());
    case "is":
      return (f, v) => !f || f.toLowerCase() === v?.toLowerCase();
    case "is-not":
      return (f, v) => !f || f.toLowerCase() !== v?.toLowerCase();
    default:
      return () => true;
  }
}

export function getTextFilterPredicate(
  filter: TextFilter,
): (value: string) => boolean {
  const filterFunction = getTextFilterFunction(filter.condition);
  return (v) => filterFunction(filter.value, v);
}

export function getTextColumnFilterFn<RecordType = AnyObject>(
  keyGetter: (r: RecordType) => string,
): (value: React.Key | boolean, record: RecordType) => boolean {
  return (value, record) => {
    const filter = parseJson<TextFilter>(value.toString(), isTextFilter);
    const predicate = getTextFilterPredicate(filter);
    const key = keyGetter(record);
    return predicate(key);
  };
}

export function getTextListColumnFilterFn<RecordType = AnyObject>(
  keysGetter: (r: RecordType) => string[],
): (value: React.Key | boolean, record: RecordType) => boolean {
  return (value, record) => {
    const filter = parseJson<TextFilter>(value.toString(), isTextFilter);
    const predicate = getTextFilterPredicate(filter);
    const keys = keysGetter(record);
    return keys.some((key) => predicate(key));
  };
}

export type TextColumnFilterDropdownProps = {
  enableExact?: boolean;
};

export const TextColumnFilterDropdown: React.FC<
  FilterDropdownProps & TextColumnFilterDropdownProps
> = ({
  confirm,
  clearFilters,
  selectedKeys,
  setSelectedKeys,
  enableExact,
}): ReactNode => {
  const options: SelectProps<TextFilterCondition>["options"] = useMemo(
    () => [
      ...(enableExact
        ? [
            { label: "Is", value: "is" },
            { label: "Is not", value: "is-not" },
          ]
        : []),
      { label: "Contains", value: "contains" },
      { label: "Does not contain", value: "not-contains" },
      { label: "Starts with", value: "starts-with" },
      { label: "Ends with", value: "ends-with" },
    ],
    [enableExact],
  );

  const getTextFilter = useCallback(() => {
    return selectedKeys[0]
      ? parseJson<TextFilter>(selectedKeys[0].toString(), isTextFilter)
      : undefined;
  }, [selectedKeys]);

  const updateTextFilter = useCallback(
    (changes: Partial<TextFilter>) => {
      setSelectedKeys([
        JSON.stringify({
          condition: options[0].value,
          ...getTextFilter(),
          ...changes,
        }),
      ]);
    },
    [getTextFilter, options, setSelectedKeys],
  );

  return (
    <div
      style={{
        padding: 8,
        display: "flex",
        flexDirection: "column",
        rowGap: "8px",
      }}
      onKeyDown={(event) => event.stopPropagation()}
    >
      <Row gutter={[8, 16]}>
        <Col>
          <Select<TextFilterCondition>
            style={{ width: 150 }}
            onChange={(value) => updateTextFilter({ condition: value })}
            options={options}
            value={
              getTextFilter()?.condition ??
              (options[0].value as TextFilterCondition)
            }
            defaultValue={options[0].value as TextFilterCondition}
          />
        </Col>
        <Col>
          <Input
            value={getTextFilter()?.value}
            onChange={(event) =>
              event.target.value
                ? updateTextFilter({ value: event.target.value })
                : setSelectedKeys([])
            }
            onPressEnter={() => confirm()}
          />
        </Col>
      </Row>
      <Row gutter={[8, 8]} justify="end">
        <Col>
          <Button
            size="small"
            onClick={() => {
              clearFilters?.();
              confirm();
            }}
          >
            Reset
          </Button>
        </Col>
        <Col>
          <Button size="small" type="primary" onClick={() => confirm()}>
            Filter
          </Button>
        </Col>
      </Row>
    </div>
  );
};
