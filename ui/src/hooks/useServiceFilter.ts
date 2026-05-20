import { ReactNode, useMemo } from "react";
import {
  AdvancedFilterConditions,
  DateFilterConditions,
  EntityFilterModel,
  FilterColumn,
  IdFilterConditions,
  ListFilterConditions,
  ListValue,
  StringFilterConditions,
} from "../components/table/filter/filter";
import { useFilter } from "../components/table/filter/useFilter";
import { LabelsStringTableFilter } from "./useChainFilter";

const protocolValues: ListValue[] = [
  { value: "http", label: "http" },
  { value: "graphql", label: "graphql" },
  { value: "grpc", label: "grpc" },
  { value: "kafka", label: "kafka" },
  { value: "amqp", label: "amqp" },
];

export const useServiceFilters = (): {
  filters: EntityFilterModel[];
  filterButton: ReactNode;
  resetFilters: () => void;
} => {
  const filterColumns: FilterColumn[] = useMemo(
    () => [
      { id: "ID", name: "ID", conditions: IdFilterConditions },
      { id: "NAME", name: "Name", conditions: StringFilterConditions },
      {
        id: "PROTOCOL",
        name: "Protocol",
        conditions: ListFilterConditions,
        allowedValues: protocolValues,
      },
      { id: "LABELS", name: "Labels", conditions: LabelsStringTableFilter },
      { id: "CREATED", name: "Created", conditions: DateFilterConditions },
      {
        id: "SPECIFICATION_GROUP",
        name: "Specification Group",
        conditions: StringFilterConditions,
      },
      {
        id: "SPECIFICATION_VERSION",
        name: "Specification Version",
        conditions: StringFilterConditions,
      },
      { id: "URL", name: "URL", conditions: AdvancedFilterConditions },
    ],
    [],
  );

  return useFilter(filterColumns);
};
