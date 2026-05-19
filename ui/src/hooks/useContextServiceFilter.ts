import { ReactNode, useMemo } from "react";
import {
  DateFilterConditions,
  EntityFilterModel,
  FilterColumn,
  IdFilterConditions,
  StringFilterConditions,
} from "../components/table/filter/filter";
import { useFilter } from "../components/table/filter/useFilter";
import { LabelsStringTableFilter } from "./useChainFilter";

export const useContextServiceFilters = (): {
  filters: EntityFilterModel[];
  filterButton: ReactNode;
  resetFilters: () => void;
} => {
  const filterColumns: FilterColumn[] = useMemo(
    () => [
      { id: "ID", name: "ID", conditions: IdFilterConditions },
      { id: "NAME", name: "Name", conditions: StringFilterConditions },
      { id: "LABELS", name: "Labels", conditions: LabelsStringTableFilter },
      { id: "CREATED", name: "Created", conditions: DateFilterConditions },
    ],
    [],
  );

  return useFilter(filterColumns);
};
