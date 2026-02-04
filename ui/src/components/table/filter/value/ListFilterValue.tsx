import { Select, SelectProps } from "antd";
import { FilterValueProps } from "./FilterValue";
import { ListValue } from "../filter";
import { useState } from "react";

export const ListFilterValue = (props: FilterValueProps) => {
  const [value, setValue] = useState<string[]>(buildInitialValue());

  function buildInitialValue(): string[] {
    return props.value ? props.value.split(",") : [];
  }

  const changeValue = (value: string[]) => {
    props.handleStringValue(value.join());
    setValue(value);
  };

  const options: SelectProps["options"] = props.allowedValues!.map(
    (value: ListValue) => {
      return { ...value };
    },
  );

  return (
    <Select
      mode="multiple"
      placeholder="Value"
      onChange={changeValue}
      options={options}
      value={value}
    />
  );
};
