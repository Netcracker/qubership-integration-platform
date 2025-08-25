import { FilterDropdownProps } from "antd/lib/table/interface";
import React, { ReactNode, useCallback } from "react";
import { Button, Col, Input, Row, Select } from "antd";
import {
  getTextFilterPredicate,
  isTextFilter,
  TextFilter,
  TextFilterCondition,
} from "../table/TextColumnFilterDropdown.tsx";
import {
  EnumFilter,
  EnumFilterCondition,
  getEnumFilterPredicate,
  isEnumFilter,
} from "../table/EnumColumnFilterDropdown.tsx";
import { DefaultOptionType } from "rc-select/lib/Select";
import { TRANSFORMATIONS } from "../../mapper/model/transformations.ts";
import { capitalize } from "../../misc/format-utils.ts";
import { Transformation } from "../../mapper/model/model.ts";
import { parseJson } from "../../misc/json-helper.ts";
import { AnyObject } from "antd/lib/_util/type";

export type TransformationFilter = {
  typeFilter?: EnumFilter;
  parametersFilter?: TextFilter;
};

export function isTransformationFilter(
  obj: unknown,
): obj is TransformationFilter {
  return (
    typeof obj === "object" &&
    obj !== null &&
    (!("typeFilter" in obj) || isEnumFilter(obj.typeFilter)) &&
    (!("parametersFilter" in obj) || isTextFilter(obj.parametersFilter))
  );
}

function getTransformationFilterPredicate(
  filter: TransformationFilter,
): (value: Transformation | undefined) => boolean {
  const parameterPredicate = filter.parametersFilter
    ? getTextFilterPredicate(filter.parametersFilter)
    : undefined;
  const typePredicate = filter.typeFilter
    ? getEnumFilterPredicate(filter.typeFilter)
    : () => true;
  return (v) =>
    typePredicate(v?.name ?? "") &&
    (!parameterPredicate ||
      (v?.parameters?.some((p) => parameterPredicate(p)) ?? false));
}

export function getTransformationColumnFilterFn<RecordType = AnyObject>(
  keysGetter: (r: RecordType) => (Transformation | undefined)[],
): (value: React.Key | boolean, record: RecordType) => boolean {
  return (value, record) => {
    const filter = parseJson<TransformationFilter>(
      value.toString(),
      isTransformationFilter,
    );
    const predicate = getTransformationFilterPredicate(filter);
    const keys = keysGetter(record);
    return keys.some((key) => predicate(key));
  };
}

export const TransformationColumnFilterDropdown: React.FC<
  FilterDropdownProps
> = ({ confirm, clearFilters, selectedKeys, setSelectedKeys }): ReactNode => {
  const getFilter = useCallback(() => {
    return selectedKeys[0]
      ? parseJson<TransformationFilter>(
          selectedKeys[0].toString(),
          isTransformationFilter,
        )
      : undefined;
  }, [selectedKeys]);

  const updateParametersFilter = useCallback(
    (changes: Partial<TextFilter>) => {
      const filter = getFilter();
      setSelectedKeys([
        JSON.stringify({
          ...filter,
          parametersFilter: {
            condition: "contains",
            ...filter?.parametersFilter,
            ...changes,
          },
        }),
      ]);
    },
    [getFilter, setSelectedKeys],
  );

  const updateTypeFilter = useCallback(
    (changes: Partial<EnumFilter>) => {
      const filter = getFilter();
      setSelectedKeys([
        JSON.stringify({
          ...filter,
          typeFilter: {
            condition: "in",
            ...filter?.typeFilter,
            ...changes,
          },
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
        <Col span={8} style={{ display: "flex", alignItems: "center" }}>
          Transformation
        </Col>
        <Col span={8}>
          <Select<EnumFilterCondition>
            style={{ width: "100%" }}
            onChange={(value) => updateTypeFilter({ condition: value })}
            options={[
              { value: "in", label: "In" },
              { value: "not-in", label: "Not in" },
            ]}
            value={getFilter()?.typeFilter?.condition ?? "in"}
            defaultValue={"in"}
          />
        </Col>
        <Col span={8}>
          <Select<DefaultOptionType["value"]>
            style={{ width: "100%" }}
            mode="multiple"
            allowClear
            placeholder="Select values..."
            onChange={(_value: unknown, selectedOptions) =>
              updateTypeFilter({
                values: (selectedOptions as DefaultOptionType[]).map((option) =>
                  String(option.value),
                ),
              })
            }
            options={[
              {
                value: "",
                label: "None",
              },
              ...TRANSFORMATIONS.map((transformationInfo) => ({
                value: transformationInfo.name,
                label: capitalize(transformationInfo.title),
              })),
            ]}
            // @ts-expect-error Property has a wrong type in "multiple" mode.
            value={getFilter()?.typeFilter?.values ?? []}
            // @ts-expect-error Property has a wrong type in "multiple" mode.
            defaultValue={getFilter()?.typeFilter?.values ?? []}
          />
        </Col>
      </Row>
      <Row gutter={[8, 16]}>
        <Col span={8} style={{ display: "flex", alignItems: "center" }}>
          Parameters
        </Col>
        <Col span={8}>
          <Select<TextFilterCondition>
            style={{ width: "100%" }}
            onChange={(value) => updateParametersFilter({ condition: value })}
            options={[
              { label: "Contains", value: "contains" },
              { label: "Does not contain", value: "not-contains" },
              { label: "Starts with", value: "starts-with" },
              { label: "Ends with", value: "ends-with" },
              { label: "Is", value: "is" },
              { label: "Is not", value: "is-not" },
            ]}
            value={getFilter()?.parametersFilter?.condition ?? "contains"}
            defaultValue={"contains"}
          />
        </Col>
        <Col span={8}>
          <Input
            style={{ width: "100%" }}
            allowClear
            value={getFilter()?.parametersFilter?.value}
            onChange={(event) =>
              event.target.value
                ? updateParametersFilter({ value: event.target.value })
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
