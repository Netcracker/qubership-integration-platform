import { InputNumber } from "antd";
import { FilterValueProps } from "./FilterValue";
import { useEffect, useState } from "react";

export const NumberFilterValue = (props: FilterValueProps) => {
  const [value, setValue] = useState<string | undefined>(props.value);
  const disabled = props.condition?.valueRequired === false;

  const onChange = (value: string | number | null) => {
    const stringValue = value ? String(value) : undefined;
    props.handleStringValue(disabled ? undefined : stringValue);
    setValue(stringValue);
  };

  useEffect(() => {
    if (disabled) {
      setValue(undefined);
    }
  }, [disabled]);

  return (
    <InputNumber
      placeholder="Value"
      onChange={onChange}
      disabled={disabled}
      value={value}
      min="0"
      style={{ width: "100%" }}
    />
  );
};
