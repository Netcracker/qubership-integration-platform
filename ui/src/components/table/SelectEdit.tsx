import React, { useContext, useRef } from "react";
import { Form, Select, SelectProps } from "antd";
import { BaseSelectRef } from "rc-select";
import { InlineEditContext } from "../InlineEdit.tsx";

export type SelectEditProps<ValueType = unknown> = {
  name: string;
  options: SelectProps<ValueType>["options"];
  multiple?: boolean;
  selectProps?: Omit<SelectProps<ValueType>, "onChange" | "onBlur">;
  onChangeSideEffect?: (value: ValueType) => void;
  /** When false, skip form submit and close editor via toggle. Use when submit must wait (e.g. Override needs Overridden by first). */
  shouldSubmitOnChange?: (value: ValueType) => boolean;
};

export function SelectEdit<ValueType = unknown>({
  name,
  options,
  multiple,
  selectProps,
  onChangeSideEffect,
  shouldSubmitOnChange,
}: Readonly<SelectEditProps<ValueType>>): React.ReactNode {
  const inlineEditContext = useContext(InlineEditContext);
  const form = Form.useFormInstance();
  const ref = useRef<BaseSelectRef>(null);

  return (
    <Form.Item name={name} style={{ marginBottom: 0 }}>
      <Select<ValueType>
        ref={ref}
        autoFocus
        defaultOpen
        mode={multiple ? "multiple" : undefined}
        style={{ width: "100%" }}
        onChange={(value) => {
          onChangeSideEffect?.(value);
          if (shouldSubmitOnChange?.(value) === false) {
            inlineEditContext?.toggle();
          } else if (shouldSubmitOnChange?.(value) === true) {
            form?.submit();
          }
        }}
        options={options}
        {...selectProps}
      />
    </Form.Item>
  );
}
