import { FilterDropdownProps } from "antd/lib/table/interface";
import React, { ReactNode, useCallback, useMemo } from "react";
import { Button, Col, InputNumber, Row, Select, SelectProps } from "antd";
import type { AnyObject } from "antd/lib/_util/type";
import { parseJson } from "../../misc/json-helper.ts";

export type NumberFilterCondition =
  | "less-than"
  | "greater-than"
  | "equal"
  | "not-equal";

export type NumberFilter = {
  condition: NumberFilterCondition;
  value: number;
};

export function isNumberFilter(obj: unknown): obj is NumberFilter {
  return (
    typeof obj === "object" &&
    obj !== null &&
    "value" in obj &&
    "condition" in obj &&
    typeof obj.value === "number" &&
    typeof obj.condition === "string"
  );
}

function getNumberFilterFunction(
  condition: NumberFilterCondition,
): (
  filter: number | undefined | null,
  value: number | undefined | null,
) => boolean {
  switch (condition) {
    case "less-than":
      return (f, v) => (v ?? 0) < (f ?? 0);
    case "greater-than":
      return (f, v) => (v ?? 0) > (f ?? 0);
    case "equal":
      return (f, v) => (v ?? 0) === (f ?? 0);
    case "not-equal":
      return (f, v) => (v ?? 0) !== (f ?? 0);
    default:
      return () => true;
  }
}

export function getNumberFilterPredicate(
  filter: NumberFilter,
): (value: number | undefined | null) => boolean {
  const filterFunction = getNumberFilterFunction(filter.condition);
  return (v) => filterFunction(filter.value, v);
}

export function getNumberColumnFilterFn<RecordType = AnyObject>(
  keyGetter: (r: RecordType) => number | undefined | null,
): (value: React.Key | boolean, record: RecordType) => boolean {
  return (value, record) => {
    const filter = parseJson<NumberFilter>(value.toString(), isNumberFilter);
    const predicate = getNumberFilterPredicate(filter);
    const key = keyGetter(record);
    return predicate(key);
  };
}

export function getNumberListColumnFilterFn<RecordType = AnyObject>(
  keysGetter: (r: RecordType) => (number | undefined | null)[],
): (value: React.Key | boolean, record: RecordType) => boolean {
  return (value, record) => {
    const filter = parseJson<NumberFilter>(value.toString(), isNumberFilter);
    const predicate = getNumberFilterPredicate(filter);
    const keys = keysGetter(record);
    return keys.some((key) => predicate(key));
  };
}

export type NumberColumnFilterDropdownProps = {
  minValue?: number;
  maxValue?: number;
  enableExact?: boolean;
};

export const NumberColumnFilterDropdown: React.FC<
  FilterDropdownProps & NumberColumnFilterDropdownProps
> = ({
  confirm,
  clearFilters,
  selectedKeys,
  setSelectedKeys,
  minValue,
  maxValue,
  enableExact,
}): ReactNode => {
  const options: SelectProps<NumberFilterCondition>["options"] = useMemo(
    () => [
      ...(enableExact
        ? [
            { label: "Equals", value: "equal" },
            { label: "Not equals", value: "not-equal" },
          ]
        : []),
      { label: "Less than", value: "less-than" },
      { label: "Greater than", value: "greater-than" },
    ],
    [enableExact],
  );

  const getNumberFilter = useCallback(() => {
    return selectedKeys[0]
      ? parseJson<NumberFilter>(selectedKeys[0].toString(), isNumberFilter)
      : undefined;
  }, [selectedKeys]);

  const updateNumberFilter = useCallback(
    (changes: Partial<NumberFilter>) => {
      setSelectedKeys([
        JSON.stringify({
          condition: options[0].value,
          value: minValue,
          ...getNumberFilter(),
          ...changes,
        }),
      ]);
    },
    [getNumberFilter, minValue, options, setSelectedKeys],
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
          <Select<NumberFilterCondition>
            style={{ width: 150 }}
            onChange={(value) => updateNumberFilter({ condition: value })}
            options={options}
            value={
              getNumberFilter()?.condition ??
              (options[0].value as NumberFilterCondition)
            }
            defaultValue={options[0].value as NumberFilterCondition}
          />
        </Col>
        <Col>
          <InputNumber
            min={minValue}
            max={maxValue}
            value={getNumberFilter()?.value}
            onChange={(value) =>
              value === null
                ? setSelectedKeys([])
                : updateNumberFilter({ value })
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
