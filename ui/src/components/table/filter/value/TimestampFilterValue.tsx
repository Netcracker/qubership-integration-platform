import { FilterValueProps } from "./FilterValue";
import React from "react";
import { FilterCondition } from "../filter";
import { DatePicker } from "antd";
import { useEffect, useState } from "react";
import { toDayjs, toEpochMillis } from "../../../../misc/date-utils";

export const TimestampFilterValue: React.FC<FilterValueProps> = (
  props: FilterValueProps,
) => {
  const [value, setValue] = useState<number[]>(buildInitialValue());
  const isRange = props.condition?.id === FilterCondition.IS_WITHIN.id;
  const handleStringValue = props.handleStringValue;

  useEffect(() => {
    if ((isRange && value.length === 1) || (!isRange && value.length === 2)) {
      setValue([]);
      handleStringValue(undefined);
    }
  }, [isRange, value.length, handleStringValue]);

  function buildInitialValue(): number[] {
    return props.value
      ? props.value.split(",").map((stringValue) => Number(stringValue))
      : [];
  }

  if (isRange) {
    return (
      <DatePicker.RangePicker
        format="YYYY-MM-DD"
        value={[toDayjs(value[0]), toDayjs(value[1])]}
        onChange={(newValue) => {
          const newState = newValue?.map((v) => toEpochMillis(v)) ?? [];
          handleStringValue(newState.join());
          setValue(newState);
        }}
      />
    );
  } else {
    return (
      <DatePicker
        showTime={{ format: "HH:mm" }}
        format="YYYY-MM-DD HH:mm"
        value={toDayjs(value[0])}
        style={{ width: "100%" }}
        onChange={(newValue) => {
          const newState = [toEpochMillis(newValue)];
          handleStringValue(newState.join());
          setValue(newState);
        }}
      />
    );
  }
};
