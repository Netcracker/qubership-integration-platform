import React, { useContext } from "react";
import { Form, Select, SelectProps } from "antd";
import { InlineEditContext } from "../InlineEdit.tsx";

export type SelectEditProps<ValueType = any> = {
  name: string;
  options: SelectProps<ValueType>["options"];
  multiple?: boolean;
};

export const SelectEdit: React.FC<SelectEditProps> = ({ name, options, multiple }) => {
  const inlineEditContext = useContext(InlineEditContext);
  const form = Form.useFormInstance();

  return (
    <Form.Item name={name} style={{ marginBottom: 0 }}>
      <Select
        autoFocus
        mode={multiple ? "multiple" : undefined}
        style={{ width: "100%" }}
        onChange={() => form.submit()}
        options={options}
        onBlur={() => inlineEditContext?.toggle()}
      />
    </Form.Item>
  );
};
