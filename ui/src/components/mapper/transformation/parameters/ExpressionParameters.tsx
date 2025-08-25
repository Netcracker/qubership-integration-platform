import { Form } from "antd";
import { ExpressionEditor } from "./ExpressionEditor";
import React from "react";

export type ExpressionParametersProps = {
    label?: string;
    offset?: number;
}

export const ExpressionParameters: React.FC<ExpressionParametersProps> = ({
    label,
    offset = 0,
}) => {
  return (
    <Form.Item
      className={"flex-form-item"}
      name={["parameters", offset]}
      label={label}
      layout="vertical"
      labelCol={{ flex: "0 0 auto" }}
      // TODO expression validation
      rules={[{ required: true, message: "Expression is required" }]}
    >
      <ExpressionEditor />
    </Form.Item>
  );
}
