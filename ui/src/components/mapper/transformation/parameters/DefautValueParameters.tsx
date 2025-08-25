import { Form, Input } from "antd";
import React from "react";

export const DefaultValueParameters: React.FC = () => {
  return <Form.Item name={["parameters", 0]} label="Value">
    <Input />
  </Form.Item>;
};
