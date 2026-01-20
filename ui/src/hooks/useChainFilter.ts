import { EngineDomain, SessionsLoggingLevel } from "../api/apiTypes";
import { useDomains } from "./useDomains";
import { useElementTypes } from "./useElementTypes";
import {
  AdvancedFilterConditions,
  DescriptionFilterConditions,
  FilterCondition,
  FilterConditions,
  FilterValueType,
  IdFilterConditions,
  ListFilterConditions,
  ListValue,
  StringFilterConditions,
  FilterColumn,
  EntityFilterModel,
} from "../components/table/filter/filter";
import { useFilter } from "../components/table/filter/useFilter";
import { capitalize } from "../misc/format-utils.ts";
import { useMemo } from "react";
import { useServiceFilterValues } from "./useServices.ts";

export const LabelsStringTableFilter: FilterConditions = {
  defaultCondition: FilterCondition.CONTAINS,
  allowedConditions: [
    FilterCondition.IS,
    FilterCondition.IS_NOT,
    FilterCondition.CONTAINS,
    FilterCondition.DOES_NOT_CONTAIN,
    FilterCondition.EMPTY,
    FilterCondition.NOT_EMPTY,
  ],
  valueType: FilterValueType.STRING,
};

export const statusValues: ListValue[] = [
  { value: "Draft", label: "Draft" },
  { value: "Processing", label: "Processing" },
  { value: "Failed", label: "Failed" },
  { value: "Deployed", label: "Deployed" },
];

export const methodValues: ListValue[] = [
  { value: "GET", label: "GET" },
  { value: "POST", label: "POST" },
  { value: "PUT", label: "PUT" },
  { value: "PATCH", label: "PATCH" },
  { value: "DELETE", label: "DELETE" },
  { value: "HEAD", label: "HEAD" },
  { value: "OPTIONS", label: "OPTIONS" },
];

function buildDomainValues(domains: EngineDomain[]): ListValue[] {
  if (!Array.isArray(domains)) {
    return [];
  }
  try {
    return domains.map((domain) => ({ value: domain.id, label: domain.name }));
  } catch {
    return [];
  }
}

function buildLoggingValues(): ListValue[] {
  const result: ListValue[] = [];
  for (const key in SessionsLoggingLevel) {
    const valueLabel = capitalize(key);
    result.push({ value: valueLabel, label: valueLabel });
  }

  return result;
}

export const useChainFilters = (): {
  filters: EntityFilterModel[];
  filterButton: JSX.Element;
} => {
  const { domains } = useDomains();
  const { buildFilterValues } = useElementTypes();
  const { services } = useServiceFilterValues();

  const filterColumns: FilterColumn[] = useMemo(
    () => [
      { id: "NAME", name: "Name", conditions: StringFilterConditions },
      {
        id: "STATUS",
        name: "Status",
        conditions: ListFilterConditions,
        allowedValues: statusValues,
      },
      { id: "LABELS", name: "Labels", conditions: LabelsStringTableFilter },
      { id: "ID", name: "ID", conditions: IdFilterConditions },
      {
        id: "DESCRIPTION",
        name: "Description",
        conditions: DescriptionFilterConditions,
      },
      {
        id: "ELEMENT",
        name: "Element",
        conditions: ListFilterConditions,
        allowedValues: buildFilterValues(),
      },
      {
        id: "DOMAINS",
        name: "Domains",
        conditions: ListFilterConditions,
        allowedValues: buildDomainValues(Array.isArray(domains) ? domains : []),
      },
      {
        id: "LOGGING",
        name: "Logging",
        conditions: ListFilterConditions,
        allowedValues: buildLoggingValues(),
      },
      { id: "PATH", name: "Path", conditions: AdvancedFilterConditions },
      {
        id: "METHOD",
        name: "Method",
        conditions: ListFilterConditions,
        allowedValues: methodValues,
      },
      { id: "TOPIC", name: "Topic", conditions: AdvancedFilterConditions },
      {
        id: "EXCHANGE",
        name: "Exchange",
        conditions: AdvancedFilterConditions,
      },
      { id: "QUEUE", name: "Queue", conditions: AdvancedFilterConditions },
      {
        id: "SERVICE_ID",
        name: "Service",
        allowedValues: services,
        conditions: ListFilterConditions,
      },
    ],
    [buildFilterValues, domains, services],
  );

  return useFilter(filterColumns);
};
