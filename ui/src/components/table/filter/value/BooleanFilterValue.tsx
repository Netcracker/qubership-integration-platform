import { Select, SelectProps } from "antd";
import { FilterValueProps } from "./FilterValue";
import { useState } from "react";
import { ListValue } from "../filter";

const booleanValues: ListValue[] = [
  { value: "true", label: "True" },
  { value: "false", label: "False" },
];
export const BooleanFilterValue = (props: FilterValueProps) => {
  const [value, setValue] = useState<string | undefined>(props.value);

  const handleChange = (value: string) => {
    props.handleStringValue(value);
    setValue(value);
  };

  const options: SelectProps["options"] = booleanValues.map(
    (value: ListValue) => {
      return { ...value };
    },
  );

  return (
    <Select
      placeholder="Value"
      onChange={handleChange}
      options={options}
      value={value}
    />
  );
};
