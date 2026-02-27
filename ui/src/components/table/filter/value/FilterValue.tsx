import { ListFilterValue } from "./ListFilterValue";
import { StringFilterValue } from "./StringFilterValue";
import { FilterCondition, FilterValueType, ListValue } from "../filter";
import { TimestampFilterValue } from "./TimestampFilterValue";
import { NumberFilterValue } from "./NumberFilterValue";
import { BooleanFilterValue } from "./BooleanFilterValue";

export type FilterValueProps = {
  condition?: FilterCondition;
  handleStringValue: (value: string | undefined) => void;
  type: FilterValueType;
  allowedValues?: ListValue[];
  value: string | undefined;
};

type FilterValueMap = {
  [key in FilterValueType]: React.ElementType;
};

const map: FilterValueMap = {
  [FilterValueType.LIST]: ListFilterValue,
  [FilterValueType.DATE]: TimestampFilterValue,
  [FilterValueType.NUMBER]: NumberFilterValue,
  [FilterValueType.STRING]: StringFilterValue,
  [FilterValueType.BOOLEAN]: BooleanFilterValue,
};

export const FilterValue = (props: FilterValueProps) => {
  const Component = map[props.type];

  return <Component {...props} />;
};
