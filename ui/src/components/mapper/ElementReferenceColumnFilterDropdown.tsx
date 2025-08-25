import React, { ReactNode, useCallback, useMemo } from "react";
import { FilterDropdownProps } from "antd/lib/table/interface";
import { Button, Col, Input, Row, Select, SelectProps } from "antd";
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
import type { AnyObject } from "antd/lib/_util/type";
import { parseJson } from "../../misc/json-helper.ts";
import {
  AttributeReference,
  ConstantReference,
  MappingDescription,
} from "../../mapper/model/model.ts";
import { MappingActions } from "../../mapper/util/actions.ts";
import { isAttributeDetail } from "../../mapper/util/schema.ts";

export type ElementReferenceFilter = {
  nameFilter?: TextFilter;
  locationFilter?: EnumFilter;
};

export function isElementReferenceFilter(
  obj: unknown,
): obj is ElementReferenceFilter {
  return (
    typeof obj === "object" &&
    obj !== null &&
    (!("nameFilter" in obj) || isTextFilter(obj.nameFilter)) &&
    (!("locationFilter" in obj) || isEnumFilter(obj.locationFilter))
  );
}

function getElementReferenceFilterPredicate(
  filter: ElementReferenceFilter,
  isTarget: boolean,
  mappingDescription: MappingDescription,
): (value: ConstantReference | AttributeReference) => boolean {
  const namePredicate = filter.nameFilter
    ? getTextFilterPredicate(filter.nameFilter)
    : () => true;
  const locationPredicate = filter.locationFilter
    ? getEnumFilterPredicate(filter.locationFilter)
    : () => true;
  return (v) => {
    const element = MappingActions.resolveReference(
      v,
      isTarget,
      mappingDescription,
    );
    if (!element) {
      return true;
    }
    const name = isAttributeDetail(element)
      ? (element.path.slice(-1)?.pop()?.name ?? "")
      : element.name;
    const location = isAttributeDetail(element) ? element.kind : "constant";
    return namePredicate(name) && locationPredicate(location);
  };
}

export function getElementReferenceColumnFilterFn<RecordType = AnyObject>(
  keysGetter: (r: RecordType) => (ConstantReference | AttributeReference)[],
  isTarget: boolean,
  mappingDescription: MappingDescription,
): (value: React.Key | boolean, record: RecordType) => boolean {
  return (value, record) => {
    const filter = parseJson<ElementReferenceFilter>(
      value.toString(),
      isElementReferenceFilter,
    );
    const predicate = getElementReferenceFilterPredicate(
      filter,
      isTarget,
      mappingDescription,
    );
    const keys = keysGetter(record);
    return keys.some((key) => predicate(key));
  };
}

export type ElementReferenceColumnFilterDropdownProps = {
  enableConstLocation?: boolean;
};

export const ElementReferenceColumnFilterDropdown: React.FC<
  FilterDropdownProps & ElementReferenceColumnFilterDropdownProps
> = ({
  confirm,
  clearFilters,
  selectedKeys,
  setSelectedKeys,
  enableConstLocation,
}): ReactNode => {
  const options: SelectProps<string>["options"] = useMemo(
    () => [
      ...(enableConstLocation
        ? [{ label: "Constant", value: "constant" }]
        : []),
      { label: "Header", value: "header" },
      { label: "Property", value: "property" },
      { label: "Body attribute", value: "body" },
    ],
    [enableConstLocation],
  );

  const getFilter = useCallback(() => {
    return selectedKeys[0]
      ? parseJson<ElementReferenceFilter>(
          selectedKeys[0].toString(),
          isElementReferenceFilter,
        )
      : undefined;
  }, [selectedKeys]);

  const updateNameFilter = useCallback(
    (changes: Partial<TextFilter>) => {
      const filter = getFilter();
      setSelectedKeys([
        JSON.stringify({
          ...filter,
          nameFilter: {
            condition: "contains",
            ...filter?.nameFilter,
            ...changes,
          },
        }),
      ]);
    },
    [getFilter, setSelectedKeys],
  );

  const updateLocationFilter = useCallback(
    (changes: Partial<EnumFilter>) => {
      const filter = getFilter();
      setSelectedKeys([
        JSON.stringify({
          ...filter,
          locationFilter: {
            condition: "in",
            ...filter?.locationFilter,
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
        <Col span={4} style={{ display: "flex", alignItems: "center" }}>
          Name
        </Col>
        <Col span={10}>
          <Select<TextFilterCondition>
            style={{ width: "100%" }}
            onChange={(value) => updateNameFilter({ condition: value })}
            options={[
              { label: "Contains", value: "contains" },
              { label: "Does not contain", value: "not-contains" },
              { label: "Starts with", value: "starts-with" },
              { label: "Ends with", value: "ends-with" },
              { label: "Is", value: "is" },
              { label: "Is not", value: "is-not" },
            ]}
            value={getFilter()?.nameFilter?.condition ?? "contains"}
            defaultValue={"contains"}
          />
        </Col>
        <Col span={10}>
          <Input
            style={{ width: "100%" }}
            allowClear
            value={getFilter()?.nameFilter?.value}
            onChange={(event) =>
              event.target.value
                ? updateNameFilter({ value: event.target.value })
                : setSelectedKeys([])
            }
            onPressEnter={() => confirm()}
          />
        </Col>
      </Row>
      <Row gutter={[8, 16]}>
        <Col span={4} style={{ display: "flex", alignItems: "center" }}>
          Location
        </Col>
        <Col span={10}>
          <Select<EnumFilterCondition>
            style={{ width: "100%" }}
            onChange={(value) => updateLocationFilter({ condition: value })}
            options={[
              { value: "in", label: "In" },
              { value: "not-in", label: "Not in" },
            ]}
            value={getFilter()?.locationFilter?.condition ?? "in"}
            defaultValue={"in"}
          />
        </Col>
        <Col span={10}>
          <Select<DefaultOptionType["value"]>
            style={{ width: "100%" }}
            mode="multiple"
            allowClear
            placeholder="Select values..."
            onChange={(_value: unknown, selectedOptions) =>
              updateLocationFilter({
                values: (selectedOptions as DefaultOptionType[]).map((option) =>
                  String(option.value),
                ),
              })
            }
            options={options}
            // @ts-expect-error Property has a wrong type in "multiple" mode.
            value={getFilter()?.locationFilter?.values ?? []}
            // @ts-expect-error Property has a wrong type in "multiple" mode.
            defaultValue={getFilter()?.locationFilter?.values ?? []}
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
