import React, { useContext } from "react";
import { Form, Select, SelectProps } from "antd";
import { InlineEditContext } from "../InlineEdit.tsx";

export type SelectEditProps<ValueType = unknown> = {
  name: string;
  options: SelectProps<ValueType>["options"];
  multiple?: boolean;
};

export function SelectEdit<ValueType = unknown>({
  name,
  options,
  multiple,
}: SelectEditProps<ValueType>): React.ReactNode {
  const inlineEditContext = useContext(InlineEditContext);
  const form = Form.useFormInstance();

  return (
    <Form.Item name={name} style={{ marginBottom: 0 }}>
      <Select<ValueType>
        autoFocus
        mode={multiple ? "multiple" : undefined}
        style={{ width: "100%" }}
        onChange={() => form.submit()}
        options={options}
        onBlur={() => inlineEditContext?.toggle()}
      />
    </Form.Item>
  );
}
