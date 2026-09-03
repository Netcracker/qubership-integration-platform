import { ReactNode, useMemo } from "react";
import {
  DateFilterConditions,
  FilterColumn,
  ListFilterConditions,
  ListValue,
  StringFilterConditions,
} from "../../components/table/filter/filterTypes";
import { useFilter } from "../../components/table/filter/useFilter";

const TYPE_FILTER_OPTIONS: ListValue[] = [
  { label: "Built-in", value: "Built-in" },
  { label: "Custom", value: "Custom" },
];

export const useDesignTemplatesFilter = (): {
  filterButton: ReactNode;
  matchFilters: (object: unknown) => boolean;
} => {
  const filterColumns: FilterColumn[] = useMemo(
    () => [
      {
        id: "name",
        name: "Name",
        conditions: StringFilterConditions,
      },
      {
        id: "typeLabel",
        name: "Type",
        conditions: ListFilterConditions,
        allowedValues: TYPE_FILTER_OPTIONS,
      },
      {
        id: "createdWhen",
        name: "Created At",
        conditions: DateFilterConditions,
      },
    ],
    [],
  );

  return useFilter(filterColumns);
};
