import { Input } from "antd";
import { FilterValueProps } from "./FilterValue";
import { useState } from "react";

export const StringFilterValue = (props: FilterValueProps) => {
  const [value, setValue] = useState<string | undefined>(props.value);

  const onChange = (value: any) => {
    props.handleStringValue(disabled ? undefined : value.target.value);
    setValue(value);
  }

  const disabled = props.condition?.valueRequired === false;

  return <Input placeholder="Value" onChange={onChange} disabled={disabled} defaultValue={value}/>;
}
