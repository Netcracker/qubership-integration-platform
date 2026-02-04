import { ReactNode } from "react";
import { Form, TreeSelect, TreeSelectProps } from "antd";

type TreeSelectEditProps<ValueType = unknown> = {
  name: string;
  selectProps?: Omit<TreeSelectProps<ValueType>, "onBlur" | "autoFocus">;
};

export function TreeSelectEdit<ValueType = unknown>({
  name,
  selectProps,
}: TreeSelectEditProps<ValueType>): ReactNode {
  const form = Form.useFormInstance();
  return (
    <Form.Item name={name} style={{ marginBottom: 0 }}>
      <TreeSelect<ValueType>
        autoFocus
        style={{ width: "100%" }}
        onBlur={() => form.submit()}
        {...selectProps}
      />
    </Form.Item>
  );
}
