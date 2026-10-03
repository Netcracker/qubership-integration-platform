import React, { ReactNode, useCallback, useMemo } from "react";
import { useTableSetting } from "../useTableSetting";
import { FilterItemState } from "./FilterItem";
import { Filter } from "./Filter.tsx";
import { useModalsContext } from "../../../Modals";
import { FilterButton } from "./FilterButton";
import {
  EntityFilterModel,
  FilterColumn,
  FilterCondition,
} from "./filterTypes";

export const useFilter = (
  filterColumns: FilterColumn[],
  storageKey?: string,
): {
  filters: EntityFilterModel[];
  filterButton: ReactNode;
  resetFilters: () => void;
  filterColumns: FilterColumn[];
  filterItemStates: FilterItemState[];
  setFilterItemStates: React.Dispatch<React.SetStateAction<FilterItemState[]>>;
  applyFilters: (filterItems: FilterItemState[]) => void;
  matchFilters: (object: unknown) => boolean;
} => {
  const { showModal } = useModalsContext();
  const [filterItemStates, setFilterItemStates] = useTableSetting<
    FilterItemState[]
  >(storageKey, "filters", []);
  const filters = useMemo(
    () =>
      filterItemStates.map(
        (item): EntityFilterModel => ({
          column: item.columnValue!,
          condition: item.conditionValue!,
          value: item.value,
        }),
      ),
    [filterItemStates],
  );
  const addFilter = () => {
    showModal({
      component: (
        <Filter
          filterColumns={filterColumns}
          filterItemStates={filterItemStates}
          onApplyFilters={applyFilters}
        />
      ),
    });
  };

  const applyFilters = (filterItems: FilterItemState[]) => {
    setFilterItemStates(filterItems);
  };

  const filterButton = (
    <FilterButton
      key="filterButton"
      count={filterItemStates.length}
      onClick={addFilter}
    />
  );

  const resetFilters = () => {
    setFilterItemStates([]);
  };

  const matchFilters = useCallback(
    (object: unknown): boolean => {
      return filters.every((filter) =>
        FilterCondition.getById(filter.condition)?.func(
          filter.value,
          (object as Record<string, unknown>)[filter.column]?.toString(),
        ),
      );
    },
    [filters],
  );

  return {
    filters,
    filterButton,
    resetFilters,
    filterColumns,
    filterItemStates,
    setFilterItemStates,
    applyFilters,
    matchFilters,
  };
};
