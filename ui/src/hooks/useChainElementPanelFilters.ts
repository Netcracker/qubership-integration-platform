import { ReactNode, useMemo } from "react";
import {
  EntityFilterModel,
  FilterColumn,
  ListFilterConditions,
  ListValue,
  StringFilterConditions,
} from "../components/table/filter/filterTypes";
import { useFilter } from "../components/table/filter/useFilter";

export const useChainElementPanelFilters = (
  elementTypeValues: ListValue[],
): {
  filters: EntityFilterModel[];
  filterButton: ReactNode;
  resetFilters: () => void;
} => {
  const filterColumns: FilterColumn[] = useMemo(
    () => [
      {
        id: "ELEMENT_TYPE",
        name: "Element Type",
        conditions: ListFilterConditions,
        allowedValues: elementTypeValues,
      },
      { id: "ELEMENT_NAME", name: "ElementName", conditions: StringFilterConditions },
    ],
    [elementTypeValues],
  );

  return useFilter(filterColumns);
};
