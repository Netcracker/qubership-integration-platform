import type { FilterValue } from "antd/es/table/interface";
import { ActionLogFilterRequest } from "../api/apiTypes.ts";
import {
  isTextFilter,
  TextFilter,
  TextFilterCondition,
} from "../components/table/TextColumnFilterDropdown.tsx";
import {
  isTimestampFilter,
  TimestampFilter,
  TimestampFilterCondition,
} from "../components/table/TimestampColumnFilterDropdown.tsx";
import { parseJson } from "./json-helper.ts";

function toApiTextCondition(condition: TextFilterCondition): string {
  switch (condition) {
    case "contains":
      return "CONTAINS";
    case "not-contains":
      return "DOES_NOT_CONTAIN";
    case "starts-with":
      return "STARTS_WITH";
    case "ends-with":
      return "ENDS_WITH";
    case "is":
      return "IS";
    case "is-not":
      return "IS_NOT";
  }
}

function toApiTimestampCondition(condition: TimestampFilterCondition): string {
  switch (condition) {
    case "is-before":
      return "IS_BEFORE";
    case "is-after":
      return "IS_AFTER";
    case "is-within":
      return "IS_WITHIN";
  }
}

function pushTextFilter(
  result: ActionLogFilterRequest[],
  column: string,
  filterValue: FilterValue[number],
): void {
  const filter = parseJson<TextFilter>(filterValue.toString(), isTextFilter);
  if (!filter.value) {
    return;
  }
  result.push({
    column,
    condition: toApiTextCondition(filter.condition),
    value: filter.value,
  });
}

function pushTimestampFilter(
  result: ActionLogFilterRequest[],
  filterValue: FilterValue[number],
): void {
  const filter = parseJson<TimestampFilter>(
    filterValue.toString(),
    isTimestampFilter,
  );
  const condition = toApiTimestampCondition(filter.condition);
  const value =
    filter.condition === "is-within"
      ? `${filter.value[0] ?? ""},${filter.value[1] ?? ""}`
      : String(filter.value[0] ?? "");
  if (!value || value === ",") {
    return;
  }
  result.push({
    column: "ACTION_TIME",
    condition,
    value,
  });
}

export function convertTableFiltersToApi(
  tableFilters: Record<string, FilterValue | null>,
): ActionLogFilterRequest[] {
  const result: ActionLogFilterRequest[] = [];

  if (tableFilters.username?.[0]) {
    pushTextFilter(result, "INITIATOR", tableFilters.username[0]);
  }
  if (tableFilters.entityName?.[0]) {
    pushTextFilter(result, "ENTITY_NAME", tableFilters.entityName[0]);
  }
  if (tableFilters.parentName?.[0]) {
    pushTextFilter(result, "PARENT_NAME", tableFilters.parentName[0]);
  }
  if (tableFilters.actionTime?.[0]) {
    pushTimestampFilter(result, tableFilters.actionTime[0]);
  }
  if (tableFilters.operation?.length) {
    result.push({
      column: "OPERATION",
      condition: "IN",
      value: tableFilters.operation.map(String).join(","),
    });
  }
  if (tableFilters.entityType?.length) {
    result.push({
      column: "ENTITY_TYPE",
      condition: "IN",
      value: tableFilters.entityType.map(String).join(","),
    });
  }

  return result;
}
