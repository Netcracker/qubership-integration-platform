import { ReactNode, useMemo } from "react";
import {
  DateFilterConditions,
  EntityFilterModel,
  FilterColumn,
  IdFilterConditions,
  ListFilterConditions,
  StringFilterConditions,
} from "../../components/table/filter/filterTypes";
import { useFilter } from "../../components/table/filter/useFilter";
import { ImportInstructionAction } from "../../api/apiTypes";
import { LabelsStringTableFilter } from "../useChainFilter";

const ACTION_FILTER_OPTIONS = [
  { label: "Ignore", value: ImportInstructionAction.IGNORE.toLowerCase() },
  { label: "Override", value: ImportInstructionAction.OVERRIDE.toLowerCase() },
];

export const useImportInstructionsFilter = (): {
  filters: EntityFilterModel[];
  filterButton: ReactNode;
} => {
  const filterColumns: FilterColumn[] = useMemo(
    () => [
      {
        id: "ID",
        name: "Id",
        conditions: IdFilterConditions,
      },
      {
        id: "INSTRUCTION_ACTION",
        name: "Action",
        conditions: ListFilterConditions,
        allowedValues: ACTION_FILTER_OPTIONS,
      },
      {
        id: "OVERRIDDEN_BY",
        name: "Overridden By",
        conditions: StringFilterConditions,
      },
      {
        id: "LABELS",
        name: "Labels",
        conditions: LabelsStringTableFilter,
      },
      {
        id: "MODIFIED_WHEN",
        name: "Modified At",
        conditions: DateFilterConditions,
      },
    ],
    [],
  );

  return useFilter(filterColumns);
};
