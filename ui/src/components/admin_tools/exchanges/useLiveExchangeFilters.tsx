import { ReactNode, useMemo } from "react";
import {
  BooleanFilterConditions,
  DateFilterConditions,
  EntityFilterModel,
  FilterColumn,
  IdFilterConditions,
  NumberFilterConditions,
  StringFilterConditions,
} from "../../table/filter/filter";
import { useFilter } from "../../table/filter/useFilter";

export const useLiveExchangeFilters = (): {
  filters: EntityFilterModel[];
  filterButton: ReactNode;
} => {
  const filterColumns: FilterColumn[] = useMemo(
    () => [
      {
        id: "SESSION_ID",
        name: "Session Id",
        conditions: IdFilterConditions,
      },
      {
        id: "CHAIN_NAME",
        name: "Chain",
        conditions: StringFilterConditions,
      },
      {
        id: "SESSION_DURATION",
        name: "Session Duration (ms)",
        conditions: NumberFilterConditions,
      },
      {
        id: "EXCHANGE_DURATION",
        name: "Exchange Duration (ms)",
        conditions: NumberFilterConditions,
      },
      {
        id: "SESSION_STARTED",
        name: "Session Started",
        conditions: DateFilterConditions,
      },
      {
        id: "MAIN_THREAD",
        name: "Main Thread",
        conditions: BooleanFilterConditions,
      },
      {
        id: "POD_IP",
        name: "Pod IP",
        conditions: StringFilterConditions,
      },
    ],
    [],
  );
  return useFilter(filterColumns);
};
