import React, { useEffect, useRef, useState } from "react";
import { Input } from "antd";
import type { WidgetProps } from "@rjsf/utils";

export const DebouncedTextWidget: React.FC<WidgetProps> = (props) => {
  const {
    id,
    disabled,
    readonly,
    autofocus,
    onChange,
    onBlur,
    onFocus,
    options,
    placeholder,
    required,
  } = props;

  const rawValue = props.value as unknown;

  const convertToString = (val: unknown): string => {
    if (typeof val === "string") return val;
    if (val == null) return "";
    if (typeof val === "object") return JSON.stringify(val);
    if (
      typeof val === "number" ||
      typeof val === "boolean" ||
      typeof val === "bigint"
    )
      return String(val);
    if (typeof val === "symbol") return val.toString();
    return "";
  };

  const stringValue = convertToString(rawValue);
  const [localValue, setLocalValue] = useState(stringValue);
  const debounceTimerRef = useRef<NodeJS.Timeout | null>(null);

  useEffect(() => {
    const newStringValue = convertToString(rawValue);
    setLocalValue(newStringValue);
  }, [rawValue]);

  useEffect(() => {
    return () => {
      if (debounceTimerRef.current) {
        clearTimeout(debounceTimerRef.current);
      }
    };
  }, []);

  const handleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const newValue = e.target.value;
    setLocalValue(newValue);

    if (debounceTimerRef.current) {
      clearTimeout(debounceTimerRef.current);
    }

    debounceTimerRef.current = setTimeout(() => {
      onChange(newValue === "" ? options.emptyValue : newValue);
    }, 300);
  };

  const handleBlur = () => {
    if (debounceTimerRef.current) {
      clearTimeout(debounceTimerRef.current);
      debounceTimerRef.current = null;
    }
    onChange(localValue === "" ? options.emptyValue : localValue);
    onBlur(id, localValue);
  };

  const handleFocus = () => {
    onFocus(id, localValue);
  };

  return (
    <Input
      id={id}
      value={localValue}
      placeholder={placeholder}
      disabled={disabled || readonly}
      autoFocus={autofocus}
      onChange={handleChange}
      onBlur={handleBlur}
      onFocus={handleFocus}
      required={required}
    />
  );
};
