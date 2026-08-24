import { ReactNode, useMemo } from "react";
import {
  DateFilterConditions,
  EntityFilterModel,
  FilterColumn,
  IdFilterConditions,
  ListFilterConditions,
  StringFilterConditions,
} from "../components/table/filter/filterTypes";
import { useFilter } from "../components/table/filter/useFilter";
import { capitalize, formatSnakeCased } from "../misc/format-utils";
import { formatEntityType } from "../misc/entityTypeLabels";
import { EntityType, LogOperation } from "../api/apiTypes";

const operationFilterOptions = Object.values(LogOperation).map((value) => ({
  label: formatSnakeCased(capitalize(value)),
  value,
}));

const entityTypeFilterOptions = Object.values(EntityType).map((value) => ({
  label: formatEntityType(value),
  value,
}));

export const useActionLogFilter = (): {
  filters: EntityFilterModel[];
  filterButton: ReactNode;
} => {
  const filterColumns: FilterColumn[] = useMemo(
    () => [
      {
        id: "ACTION_TIME",
        name: "Action Time",
        conditions: DateFilterConditions,
      },
      {
        id: "INITIATOR",
        name: "Initiator",
        conditions: StringFilterConditions,
      },
      {
        id: "OPERATION",
        name: "Operation",
        conditions: ListFilterConditions,
        allowedValues: operationFilterOptions,
      },
      {
        id: "ENTITY_TYPE",
        name: "Entity Type",
        conditions: ListFilterConditions,
        allowedValues: entityTypeFilterOptions,
      },
      {
        id: "ENTITY_NAME",
        name: "Entity Name",
        conditions: StringFilterConditions,
      },
      {
        id: "PARENT_NAME",
        name: "Parent Name",
        conditions: StringFilterConditions,
      },
      {
        id: "ENTITY_ID",
        name: "Entity Id",
        conditions: IdFilterConditions,
      },
      {
        id: "PARENT_ID",
        name: "Parent Id",
        conditions: IdFilterConditions,
      },
      {
        id: "REQUEST_ID",
        name: "Request Id",
        conditions: IdFilterConditions,
      },
    ],
    [],
  );

  return useFilter(filterColumns);
};
