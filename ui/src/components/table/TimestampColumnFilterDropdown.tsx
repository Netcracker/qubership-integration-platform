import { FilterDropdownProps } from "antd/lib/table/interface";
import React, { ReactNode, useCallback } from "react";
import { Button, Col, DatePicker, Row, Select } from "antd";
import type { AnyObject } from "antd/lib/_util/type";
import { toDayjs, toEpochMillis } from "../../misc/date-utils";
import { parseJson } from "../../misc/json-helper.ts";

export type TimestampFilterCondition = "is-before" | "is-after" | "is-within";

export type TimestampFilter = {
  condition: TimestampFilterCondition;
  value: number[];
};

export function isTimestampFilter(obj: unknown): obj is TimestampFilter {
  return (
    typeof obj === "object" &&
    obj !== null &&
    "condition" in obj &&
    typeof obj.condition === "string" &&
    (obj.condition as TimestampFilterCondition) !== undefined &&
    (!("value" in obj) || Array.isArray(obj.value))
  );
}

function getTimestampFilterFunction(
  condition: TimestampFilterCondition,
): (filter: number[], value: number) => boolean {
  switch (condition) {
    case "is-before":
      return (f, v) => !f?.[0] || v < f[0];
    case "is-after":
      return (f, v) => !f?.[0] || v > f[0];
    case "is-within":
      return (f, v) => !f?.[0] || !f?.[1] || (v > f[0] && v < f[1]);
    default:
      return () => true;
  }
}

function getTimestampFilterPredicate(
  filter: TimestampFilter,
): (value: number) => boolean {
  const filterFunction = getTimestampFilterFunction(filter.condition);
  return (v) => filterFunction(filter.value, v);
}

export function getTimestampColumnFilterFn<RecordType = AnyObject>(
  keyGetter: (r: RecordType) => number,
): (value: React.Key | boolean, record: RecordType) => boolean {
  return (value, record) => {
    const filter: TimestampFilter = parseJson<TimestampFilter>(
      value.toString(),
      isTimestampFilter,
    );
    const predicate = getTimestampFilterPredicate(filter);
    const key = keyGetter(record);
    return predicate(key);
  };
}

export const TimestampColumnFilterDropdown: React.FC<FilterDropdownProps> = ({
  confirm,
  clearFilters,
  selectedKeys,
  setSelectedKeys,
}): ReactNode => {
  const options = [
    { label: "Is before", value: "is-before" },
    { label: "Is after", value: "is-after" },
    { label: "Is within", value: "is-within" },
  ];

  const getFilter = useCallback(() => {
    return selectedKeys[0]
      ? parseJson<TimestampFilter>(
          selectedKeys[0].toString(),
          isTimestampFilter,
        )
      : undefined;
  }, [selectedKeys]);

  const updateFilter = useCallback(
    (changes: Partial<TimestampFilter>) => {
      setSelectedKeys([
        JSON.stringify({ condition: "is-before", ...getFilter(), ...changes }),
      ]);
    },
    [getFilter, setSelectedKeys],
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
          <Select<TimestampFilterCondition>
            style={{ width: 150 }}
            onChange={(value) => updateFilter({ condition: value })}
            options={options}
            value={getFilter()?.condition ?? "is-before"}
            defaultValue="is-before"
          />
        </Col>
        <Col>
          {getFilter()?.condition === "is-within" ? (
            <DatePicker.RangePicker
              showTime={{ format: "HH:mm" }}
              format="YYYY-MM-DD HH:mm"
              value={[
                toDayjs(getFilter()?.value?.[0]),
                toDayjs(getFilter()?.value?.[1]),
              ]}
              onChange={(value) => {
                updateFilter({
                  value: value?.map((v) => toEpochMillis(v)) ?? [],
                });
              }}
            />
          ) : (
            <DatePicker
              showTime={{ format: "HH:mm" }}
              format="YYYY-MM-DD HH:mm"
              value={toDayjs(getFilter()?.value?.[0])}
              onChange={(value) => {
                updateFilter({ value: [toEpochMillis(value)] });
              }}
            />
          )}
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
