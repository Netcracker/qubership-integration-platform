import React, { useEffect, useRef, useState } from "react";
import { Input } from "antd";
import type { WidgetProps } from "@rjsf/utils";

const { TextArea } = Input;

const toStringValue = (raw: unknown): string => {
  if (typeof raw === "string") {
    return raw;
  }
  if (typeof raw === "number" || typeof raw === "boolean") {
    return String(raw);
  }
  if (raw === null || raw === undefined) {
    return "";
  }
  return "";
};

type TextareaOptions = Record<string, unknown> & {
  rows?: number;
  emptyValue?: string;
};

interface DebouncedTextareaWidgetProps {
  id: string;
  value?: string;
  disabled?: boolean;
  readonly?: boolean;
  autofocus?: boolean;
  onChange: (value?: string) => void;
  onBlur: (id: string, value?: string) => void;
  onFocus: (id: string, value?: string) => void;
  options?: TextareaOptions;
  placeholder?: string;
  required?: boolean;
}

const getEmptyValue = (options?: TextareaOptions): string | undefined => {
  return typeof options?.emptyValue === "string"
    ? options.emptyValue
    : undefined;
};

export const DebouncedTextareaWidget: React.FC<WidgetProps> = (props) => {
  const widgetProps = props as DebouncedTextareaWidgetProps;
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
  } = widgetProps;
  const value = widgetProps.value;

  const [localValue, setLocalValue] = useState<string>(() =>
    toStringValue(value),
  );
  const debounceTimerRef = useRef<NodeJS.Timeout | null>(null);

  useEffect(() => {
    setLocalValue(toStringValue(value));
  }, [value]);

  useEffect(() => {
    return () => {
      if (debounceTimerRef.current) {
        clearTimeout(debounceTimerRef.current);
      }
    };
  }, []);

  const handleChange = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
    const newValue = e.target.value;
    setLocalValue(newValue);

    if (debounceTimerRef.current) {
      clearTimeout(debounceTimerRef.current);
    }

    const emptyValue = getEmptyValue(options);
    debounceTimerRef.current = setTimeout(() => {
      onChange(newValue === "" ? emptyValue : newValue);
    }, 300);
  };

  const handleBlur = () => {
    if (debounceTimerRef.current) {
      clearTimeout(debounceTimerRef.current);
      debounceTimerRef.current = null;
    }
    const emptyValue = getEmptyValue(options);
    onChange(localValue === "" ? emptyValue : localValue);
    onBlur(id, localValue);
  };

  const handleFocus = () => {
    onFocus(id, localValue);
  };

  return (
    <TextArea
      id={id}
      value={localValue}
      placeholder={placeholder}
      disabled={disabled || readonly}
      autoFocus={autofocus}
      onChange={handleChange}
      onBlur={handleBlur}
      onFocus={handleFocus}
      rows={options?.rows ?? 4}
      required={required}
    />
  );
};
