import { ReactNode } from "react";
import {
  AdvancedFilterConditions,
  DateFilterConditions,
  EntityFilterModel,
  FilterColumn,
  IdFilterConditions,
  ListFilterConditions,
  ListValue,
  StringFilterConditions,
} from "../components/table/filter/filterTypes";
import { useFilter } from "../components/table/filter/useFilter";
import { LabelsStringTableFilter } from "./useChainFilter";

const protocolValues: ListValue[] = [
  { value: "http", label: "http" },
  { value: "graphql", label: "graphql" },
  { value: "grpc", label: "grpc" },
  { value: "kafka", label: "kafka" },
  { value: "amqp", label: "amqp" },
];

// The column ids are the backend FilterFeature values, so a rename here has to follow the enum.
// Module-level because the list is static; useChainFilter keeps its columns in useMemo only because
// they depend on loaded domains and services.
export const SERVICE_FILTER_COLUMNS: FilterColumn[] = [
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
    id: "API_GROUP",
    name: "API Group",
    conditions: StringFilterConditions,
  },
  {
    id: "SPECIFICATION_VERSION",
    name: "Specification Version",
    conditions: StringFilterConditions,
  },
  { id: "URL", name: "URL", conditions: AdvancedFilterConditions },
];

export const useServiceFilters = (): {
  filters: EntityFilterModel[];
  filterButton: ReactNode;
  resetFilters: () => void;
} => {
  return useFilter(SERVICE_FILTER_COLUMNS);
};
