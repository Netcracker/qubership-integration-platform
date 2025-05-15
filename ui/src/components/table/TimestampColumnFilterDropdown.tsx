import { FilterDropdownProps } from "antd/lib/table/interface";
import React, { ReactNode } from "react";
import { Button, Col, DatePicker, Row, Select } from "antd";
import type { AnyObject } from "antd/lib/_util/type";
import dayjs, { Dayjs } from "dayjs";

type TimestampFilter = {
  condition: string;
  value: number[];
};

function toEpochMillis(v: Dayjs | null): number {
  return (v?.unix() ?? 0) * 1000;
}

function toDayjs(v: number | null | undefined): Dayjs | undefined {
  return v ? dayjs(v) : undefined;
}

function getTimestampFilterFunction(
  condition: string,
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
    const filter: TimestampFilter = JSON.parse(value.toString());
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
    { label: "Is within", value: "is-within" }
  ];

  const getFilter = () => {
    return selectedKeys[0]
      ? (JSON.parse(selectedKeys[0].toString()) as TimestampFilter)
      : undefined;
  };

  const updateFilter = (changes: Partial<TimestampFilter>) => {
    setSelectedKeys([
      JSON.stringify({ condition: "is-before", ...getFilter(), ...changes }),
    ]);
  };

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
          <Select
            style={{ width: 150 }}
            onChange={(value) => updateFilter({ condition: value })}
            options={options}
            value={getFilter()?.condition ?? "is-before"}
            defaultValue="is-before"
          />
        </Col>
        <Col>
          {
            getFilter()?.condition === 'is-within' ?
              <DatePicker.RangePicker
                showTime={{ format: 'HH:mm' }}
                format="YYYY-MM-DD HH:mm"
                value={[toDayjs(getFilter()?.value?.[0]), toDayjs(getFilter()?.value?.[1])]}
                onChange={(value) => {
                  updateFilter({ value: value?.map(v => toEpochMillis(v)) ?? [] });
                }}
              />
              : <DatePicker
                showTime={{ format: 'HH:mm' }}
                format="YYYY-MM-DD HH:mm"
                value={toDayjs(getFilter()?.value?.[0])}
                onChange={(value) => {
                  updateFilter({ value: [toEpochMillis(value)] });
                }}
              />
          }
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
