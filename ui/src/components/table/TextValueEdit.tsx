import React, { useContext } from "react";
import { InlineEditContext } from "../InlineEdit.tsx";
import { Form, Input } from "antd";

export type TextValueEditorProps = {
  name: string;
};

export const TextValueEdit: React.FC<TextValueEditorProps> = ({ name }) => {
  const inlineEditContext = useContext(InlineEditContext);
  const form = Form.useFormInstance();

  return (
    <Form.Item
      name={name}
      rules={[{ required: true, message: "Value is required." }]}
      style={{ marginBottom: 0 }}
    >
      <Input
        autoFocus
        onPressEnter={() => form.submit()}
        onBlur={() => inlineEditContext?.toggle()}
      />
    </Form.Item>
  );
};
