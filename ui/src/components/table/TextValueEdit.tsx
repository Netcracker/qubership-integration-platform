import React, { useContext, useRef } from "react";
import { InlineEditContext } from "../InlineEdit.tsx";
import { Form, Input, InputProps, InputRef } from "antd";
import { Rule } from "antd/lib/form/index";

export type TextValueEditorProps = {
  name: string;
  rules?: Rule[];
  inputProps?: Omit<InputProps, "onBlur" | "onPressEnter">;
};

export const TextValueEdit: React.FC<TextValueEditorProps> = ({
  name,
  rules,
  inputProps,
}) => {
  const inlineEditContext = useContext(InlineEditContext);
  const form = Form.useFormInstance();
  const ref = useRef<InputRef>(null);

  return (
    <Form.Item
      name={name}
      rules={rules ?? [{ required: true, message: "Value is required." }]}
      style={{ marginBottom: 0 }}
    >
      <Input
        ref={ref}
        autoFocus
        onPressEnter={() => form.submit()}
        onBlur={(event) => {
          if (
            event.relatedTarget &&
            !ref.current?.nativeElement?.contains(event.relatedTarget)
          ) {
            inlineEditContext?.toggle();
          }
        }}
        {...inputProps}
      />
    </Form.Item>
  );
};
