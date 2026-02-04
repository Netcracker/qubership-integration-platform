import { ListFilterValue } from "./ListFilterValue";
import { StringFilterValue } from "./StringFilterValue";
import { FilterCondition, FilterValueType, ListValue } from "../filter";
import { TimestampFilterValue } from "./TimestampFilterValue";

export type FilterValueProps = {
  condition?: FilterCondition;
  handleStringValue: (value: string | undefined) => void;
  type: FilterValueType;
  allowedValues?: ListValue[];
  value: string | undefined;
};

export const FilterValue = (props: FilterValueProps) => {
  if (props.type === FilterValueType.LIST) {
    return <ListFilterValue {...props} />;
  }
  if (props.type === FilterValueType.DATE) {
    return <TimestampFilterValue {...props} />;
  }

  return <StringFilterValue {...props} />;
};
