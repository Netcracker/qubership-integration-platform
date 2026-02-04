import React, { useContext, useRef } from "react";
import { Form, Select, SelectProps } from "antd";
import { BaseSelectRef } from "rc-select";
import { InlineEditContext } from "../InlineEdit.tsx";

export type SelectEditProps<ValueType = unknown> = {
  name: string;
  options: SelectProps<ValueType>["options"];
  multiple?: boolean;
  selectProps?: Omit<SelectProps<ValueType>, "onChange" | "onBlur">;
};

export function SelectEdit<ValueType = unknown>({
  name,
  options,
  multiple,
  selectProps,
}: SelectEditProps<ValueType>): React.ReactNode {
  const inlineEditContext = useContext(InlineEditContext);
  const form = Form.useFormInstance();
  const ref = useRef<BaseSelectRef>(null);

  return (
    <Form.Item name={name} style={{ marginBottom: 0 }}>
      <Select<ValueType>
        ref={ref}
        autoFocus
        mode={multiple ? "multiple" : undefined}
        style={{ width: "100%" }}
        onChange={() => form.submit()}
        options={options}
        onBlur={(event) => {
          if (
            event.relatedTarget &&
            !ref.current?.nativeElement?.contains(event.relatedTarget)
          ) {
            inlineEditContext?.toggle();
          }
        }}
        {...selectProps}
      />
    </Form.Item>
  );
}
