import { ReactNode, useMemo } from "react";
import {
  ContainsAndDoesNotFilterConditions,
  DateFilterConditions,
  EntityFilterModel,
  FilterColumn,
  ListFilterConditions,
  ListValue,
  StringFilterConditions,
} from "../../components/table/filter/filterTypes";
import { useFilter } from "../../components/table/filter/useFilter";
import { ExecutionStatus } from "../../api/apiTypes";
import { formatSnakeCased } from "../../misc/format-utils";

const STATUS_FILTER_OPTIONS: ListValue[] = Object.values(ExecutionStatus).map(
  (value) => ({ value, label: formatSnakeCased(value) }),
);

export const useSessionsFilter = (
  chainId: string | undefined,
): {
  filters: EntityFilterModel[];
  filterButton: ReactNode;
} => {
  const filterColumns: FilterColumn[] = useMemo(
    () => [
      ...(chainId
        ? []
        : [
            {
              id: "CHAIN_NAME",
              name: "Chain",
              conditions: StringFilterConditions,
            },
          ]),
      {
        id: "STATUS",
        name: "Status",
        conditions: ListFilterConditions,
        allowedValues: STATUS_FILTER_OPTIONS,
      },
      {
        id: "START_TIME",
        name: "Start Time",
        conditions: DateFilterConditions,
      },
      {
        id: "FINISH_TIME",
        name: "Finish Time",
        conditions: DateFilterConditions,
      },
      {
        id: "ENGINE",
        name: "Engine",
        conditions: ContainsAndDoesNotFilterConditions,
      },
    ],
    [chainId],
  );

  return useFilter(filterColumns);
};
