import React, { ReactNode, useCallback } from "react";
import { FilterDropdownProps } from "antd/lib/table/interface";
import { DefaultOptionType } from "rc-select/lib/Select";
import { Button, Col, Row, Select } from "antd";
import type { AnyObject } from "antd/lib/_util/type";
import { parseJson } from "../../misc/json-helper.ts";

export type EnumFilterCondition = "in" | "not-in";

export type EnumFilter = {
  condition: EnumFilterCondition;
  values: string[];
};

export function isEnumFilter(obj: unknown): obj is EnumFilter {
  return (
    typeof obj === "object" &&
    obj !== null &&
    "values" in obj &&
    "condition" in obj &&
    Array.isArray(obj.values) &&
    typeof obj.condition === "string"
  );
}

function getEnumFilterFunction(
  condition: EnumFilterCondition,
): (filter: string[], value: string) => boolean {
  switch (condition) {
    case "in":
      return (f, v) => f.length === 0 || f.includes(v);
    case "not-in":
      return (f, v) => f.length === 0 || !f.includes(v);
    default:
      return () => true;
  }
}

export function getEnumFilterPredicate(
  filter: EnumFilter,
): (value: string) => boolean {
  const filterFunction = getEnumFilterFunction(filter.condition);
  return (v) => filterFunction(filter.values, v);
}

export function getEnumColumnFilterFn<RecordType = AnyObject>(
  keyGetter: (r: RecordType) => string,
): (value: React.Key | boolean, record: RecordType) => boolean {
  return (value, record) => {
    const filter = parseJson<EnumFilter>(value.toString(), isEnumFilter);
    const predicate = getEnumFilterPredicate(filter);
    const key = keyGetter(record);
    return predicate(key);
  };
}

export type EnumColumnFilterDropdownProps<
  OptionType extends DefaultOptionType,
> = {
  options: OptionType[];
};

export const EnumColumnFilterDropdown = <OptionType extends DefaultOptionType>({
  options,
  confirm,
  clearFilters,
  selectedKeys,
  setSelectedKeys,
}: FilterDropdownProps &
  EnumColumnFilterDropdownProps<OptionType>): ReactNode => {
  const getFilter = useCallback(() => {
    return selectedKeys[0]
      ? parseJson<EnumFilter>(selectedKeys[0].toString(), isEnumFilter)
      : undefined;
  }, [selectedKeys]);

  const updateFilter = useCallback(
    (changes: Partial<EnumFilter>) => {
      setSelectedKeys([
        JSON.stringify({
          condition: "in",
          ...getFilter(),
          ...changes,
        }),
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
          <Select<EnumFilterCondition>
            style={{ width: 150 }}
            onChange={(value) => updateFilter({ condition: value })}
            options={[
              { value: "in", label: "In" },
              { value: "not-in", label: "Not in" },
            ]}
            value={getFilter()?.condition ?? "in"}
            defaultValue={"in"}
          />
        </Col>
        <Col>
          <Select<DefaultOptionType["value"]>
            style={{ width: 150 }}
            mode="multiple"
            allowClear
            placeholder="Select values..."
            onChange={(_value: unknown, selectedOptions) =>
              updateFilter({
                values: (selectedOptions as OptionType[]).map((option) =>
                  String(option.value),
                ),
              })
            }
            options={options}
            // @ts-expect-error Property has a wrong type in "multiple" mode.
            value={getFilter()?.values}
            // @ts-expect-error Property has a wrong type in "multiple" mode.
            defaultValue={getFilter()?.values}
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
