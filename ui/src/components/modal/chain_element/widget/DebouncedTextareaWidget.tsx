import React, { useEffect, useRef, useState } from 'react';
import { Input } from 'antd';
import type { WidgetProps } from '@rjsf/utils';

const { TextArea } = Input;

export const DebouncedTextareaWidget: React.FC<WidgetProps> = (props) => {
  const {
    id,
    value,
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

  const [localValue, setLocalValue] = useState(value || '');
  const debounceTimerRef = useRef<NodeJS.Timeout | null>(null);

  useEffect(() => {
    setLocalValue(value || '');
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

    debounceTimerRef.current = setTimeout(() => {
      onChange(newValue === '' ? options.emptyValue : newValue);
    }, 300);
  };

  const handleBlur = () => {
    if (debounceTimerRef.current) {
      clearTimeout(debounceTimerRef.current);
      debounceTimerRef.current = null;
    }
    onChange(localValue === '' ? options.emptyValue : localValue);
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
      rows={options.rows || 4}
      required={required}
    />
  );
};
