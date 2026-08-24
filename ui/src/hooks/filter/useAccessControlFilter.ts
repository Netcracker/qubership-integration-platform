import { ReactNode, useMemo } from "react";
import {
  AdvancedFilterConditions,
  EntityFilterModel,
  FilterColumn,
  FilterCondition,
  FilterConditions,
  FilterValueType,
  ListFilterConditions,
  ListValue,
  StringFilterConditions,
} from "../../components/table/filter/filterTypes";
import { useFilter } from "../../components/table/filter/useFilter";
import { AccessControlType } from "../../api/apiTypes";
import { LabelsStringTableFilter } from "../useChainFilter";

const typeOptions: ListValue[] = [
  { label: "External", value: "External" },
  { label: "Private", value: "Private" },
  { label: "Internal", value: "Internal" },
  { label: "External, Private", value: "External, Private" },
];

const chainStatusOptions: ListValue[] = [
  { label: "Draft", value: "DRAFT" },
  { label: "Deployed", value: "DEPLOYED" },
  { label: "Failed", value: "FAILED" },
  { label: "Processing", value: "PROCESSING" },
];

const accessControlTypeOptions: ListValue[] = Object.values(
  AccessControlType,
).map((value) => ({
  label: value,
  value,
}));

const AccessControlTypeFilterConditions: FilterConditions = {
  defaultCondition: FilterCondition.IS,
  allowedConditions: [FilterCondition.IS, FilterCondition.IS_NOT],
  valueType: FilterValueType.LIST,
};

export const useAccessControlFilter = (): {
  filters: EntityFilterModel[];
  filterButton: ReactNode;
} => {
  const filterColumns: FilterColumn[] = useMemo(
    () => [
      {
        id: "ENDPOINT",
        name: "Endpoint",
        conditions: AdvancedFilterConditions,
      },
      {
        id: "TYPE",
        name: "Type",
        conditions: ListFilterConditions,
        allowedValues: typeOptions,
      },
      {
        id: "ACCESS_CONTROL_TYPE",
        name: "Access Control Type",
        conditions: AccessControlTypeFilterConditions,
        allowedValues: accessControlTypeOptions,
      },
      {
        id: "ROLES",
        name: "Roles",
        conditions: LabelsStringTableFilter,
      },
      {
        id: "CHAIN",
        name: "Chain",
        conditions: StringFilterConditions,
      },
      {
        id: "CHAIN_STATUS",
        name: "Chain Status",
        conditions: ListFilterConditions,
        allowedValues: chainStatusOptions,
      },
    ],
    [],
  );

  return useFilter(filterColumns);
};
