import React, { useState } from "react";
import { FilterItemState } from "./FilterItem";
import { Filter } from "./Filter.tsx";
import { useModalsContext } from "../../../Modals";
import { FilterButton } from "./FilterButton";
import { EntityFilterModel, FilterColumn } from "./filter";

export const useFilter = (
  filterColumns: FilterColumn[],
): {
  filters: EntityFilterModel[];
  filterButton: JSX.Element;
  resetFilters: () => void;
  filterColumns: FilterColumn[];
  filterItemStates: FilterItemState[];
  setFilterItemStates: React.Dispatch<React.SetStateAction<FilterItemState[]>>;
  applyFilters: (filterItems: FilterItemState[]) => void;
} => {
  const { showModal } = useModalsContext();
  const [filters, setFilters] = useState<EntityFilterModel[]>([]);
  const [filterItemStates, setFilterItemStates] = useState<FilterItemState[]>(
    [],
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

    const f = filterItems.map(
      (filterItem): EntityFilterModel => ({
        column: filterItem.columnValue!,
        condition: filterItem.conditionValue!,
        value: filterItem.value,
      }),
    );
    setFilters(f);
  };

  const filterButton = (
    <FilterButton count={filterItemStates.length} onClick={addFilter} />
  );

  const resetFilters = () => {
    setFilters([]);
    setFilterItemStates([]);
  };

  return {
    filters,
    filterButton,
    resetFilters,
    filterColumns,
    filterItemStates,
    setFilterItemStates,
    applyFilters,
  };
};
