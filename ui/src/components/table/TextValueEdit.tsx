import React, { useContext, useRef } from "react";
import { InlineEditContext } from "../InlineEdit.tsx";
import { Form, Input, InputProps, InputRef, Space } from "antd";
import { Rule } from "antd/lib/form/index";

const InputWithSuffix = React.forwardRef<
  InputRef,
  InputProps & { suffixAction: React.ReactNode }
>(({ suffixAction, ...inputProps }, ref) => (
  <Space.Compact style={{ display: "flex", width: "100%" }}>
    <Input ref={ref} {...inputProps} />
    {suffixAction}
  </Space.Compact>
));
InputWithSuffix.displayName = "InputWithSuffix";

export type TextValueEditorProps = {
  name: string;
  rules?: Rule[];
  inputProps?: Omit<InputProps, "onBlur" | "onPressEnter" | "addonAfter">;
  suffixAction?: React.ReactNode;
};

export const TextValueEdit: React.FC<TextValueEditorProps> = ({
  name,
  rules,
  inputProps,
  suffixAction,
}) => {
  const inlineEditContext = useContext(InlineEditContext);
  const form = Form.useFormInstance();
  const ref = useRef<InputRef>(null);

  const commonProps = {
    ref,
    autoFocus: true,
    onPressEnter: () => form.submit(),
    onBlur: (event: React.FocusEvent<HTMLInputElement>) => {
      if (
        event.relatedTarget &&
        !ref.current?.nativeElement?.contains(event.relatedTarget)
      ) {
        inlineEditContext?.toggle();
      }
    },
    ...inputProps,
  };

  return (
    <Form.Item
      name={name}
      rules={rules ?? [{ required: true, message: "Value is required." }]}
      style={{ marginBottom: 0 }}
    >
      {suffixAction ? (
        <InputWithSuffix suffixAction={suffixAction} {...commonProps} />
      ) : (
        <Input {...commonProps} />
      )}
    </Form.Item>
  );
};
