import React, { useContext, useRef } from "react";
import { InlineEditContext } from "../InlineEdit.tsx";
import { Form, InputNumber, InputNumberProps } from "antd";
import { Rule } from "antd/lib/form/index";
import { InputNumberRef } from "rc-input-number";

export type NumberValueEditorProps = {
  name: string;
  rules?: Rule[];
  inputProps?: Omit<InputNumberProps<string>, "onBlur" | "onPressEnter">;
};

export const NumberValueEdit: React.FC<NumberValueEditorProps> = ({
  name,
  rules,
  inputProps,
}) => {
  const inlineEditContext = useContext(InlineEditContext);
  const form = Form.useFormInstance();
  const ref = useRef<InputNumberRef>(null)

  return (
    <Form.Item
      name={name}
      rules={rules ?? [{ required: true, message: "Value is required." }]}
      style={{ marginBottom: 0 }}
    >
      <InputNumber<string>
        ref={ref}
        style={{ width: "100%" }}
        stringMode
        autoFocus
        onPressEnter={() => form.submit()}
        onBlur={(event) => {
          if (event.relatedTarget && !ref.current?.nativeElement?.contains(event.relatedTarget)) {
            inlineEditContext?.toggle();
          }
        }}
        {...inputProps}
      />
    </Form.Item>
  );
};
