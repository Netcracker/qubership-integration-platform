import { Input } from "antd";
import { FilterValueProps } from "./FilterValue";
import { ChangeEvent, useEffect, useState } from "react";

export const StringFilterValue = (props: FilterValueProps) => {
  const [value, setValue] = useState<string | undefined>(props.value);
  const disabled = props.condition?.valueRequired === false;

  const onChange = ({
    target: { value },
  }: ChangeEvent<HTMLInputElement>) => {
    props.handleStringValue(disabled ? undefined : value);
    setValue(value);
  }

  useEffect(() => {
    if (disabled) {
      setValue(undefined);
    }
  }, [disabled]);

  return <Input placeholder="Value" onChange={onChange} disabled={disabled} value={value} />;
}
