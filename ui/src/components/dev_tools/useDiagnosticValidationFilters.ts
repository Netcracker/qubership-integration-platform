import { useMemo } from "react";
import {
  AdvancedFilterConditions,
  EntityFilterModel,
  FilterColumn,
  IdFilterConditions,
  ListFilterConditions,
  ListValue,
} from "../table/filter/filter";
import { useFilter } from "../table/filter/useFilter";
import { ValidationSeverity } from "../../api/apiTypes";
import { useElementTypes } from "../../hooks/useElementTypes";

export const validationSeverityValues: ListValue[] = [
  { value: ValidationSeverity.WARNING, label: "Warning" },
  { value: ValidationSeverity.ERROR, label: "Error" },
];

export const useDiagnosticValidationFilters = (): {
  filters: EntityFilterModel[];
  filterButton: JSX.Element;
} => {
  const { buildFilterValues } = useElementTypes();

  const filterColumns: FilterColumn[] = useMemo(
    () => [
      {
        id: "CHAIN_ID",
        name: "Chain Id",
        conditions: IdFilterConditions,
      },
      {
        id: "CHAIN_NAME",
        name: "Chain Name",
        conditions: AdvancedFilterConditions,
      },
      {
        id: "ELEMENT_ID",
        name: "Chain Element Id",
        conditions: IdFilterConditions,
      },
      {
        id: "ELEMENT_NAME",
        name: "Chain Element Name",
        conditions: AdvancedFilterConditions,
      },
      {
        id: "ELEMENT_TYPE",
        name: "Chain Element Type",
        conditions: ListFilterConditions,
        allowedValues: buildFilterValues(),
      },
      {
        id: "VALIDATION_SEVERITY",
        name: "Validation Severity",
        conditions: ListFilterConditions,
        allowedValues: validationSeverityValues,
      },
    ],
    [buildFilterValues],
  );
  return useFilter(filterColumns);
};
