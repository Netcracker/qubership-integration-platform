import { ReactNode, useMemo } from "react";
import {
  ExtendedStringFilterConditions,
  FilterColumn,
} from "../../components/table/filter/filterTypes";
import { useFilter } from "../../components/table/filter/useFilter";

export const useVariableFilter = (enableValueFilter: boolean = true): {
  filterButton: ReactNode;
  matchFilters: (object: unknown) => boolean;
} => {
  const filterColumns: FilterColumn[] = useMemo(
    () => [
      {
        id: "key",
        name: "Key",
        conditions: ExtendedStringFilterConditions,
      },
      ...(enableValueFilter ? [{
        id: "value",
        name: "Value",
        conditions: ExtendedStringFilterConditions,
      }] : []),
    ],
    [enableValueFilter],
  );

  return useFilter(filterColumns);
};
