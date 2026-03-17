import React from "react";
import { Form, Input } from "antd";
import { NON_WHITESPACE_PATTERN } from "./types.ts";

export const NamespaceField: React.FC = () => {
  return (
    <Form.Item
      label="Namespace"
      name="namespace"
      required
      rules={[
        { required: true, message: "Namespace is required" },
        { pattern: NON_WHITESPACE_PATTERN, message: "Namespace cannot be empty" },
      ]}
    >
      <Input />
    </Form.Item>
  );
};
