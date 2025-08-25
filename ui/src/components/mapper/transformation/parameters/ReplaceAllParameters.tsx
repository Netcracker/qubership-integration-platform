import React from "react";
import { Form, Input } from "antd";

function isRegex(value: string): boolean {
  try {
    new RegExp(value);
    return true;
  } catch {
    return false;
  }
}

function isValidReplacementString(value: string): boolean {
  return /^(\\.|\$\d+|[^\\$])*$/.test(value);
}

export const ReplaceAllParameters: React.FC = () => {
  return (
    <>
      <Form.Item
        name={["parameters", 0]}
        label="Regular expression"
        rules={[
          { required: true, message: "Regular expression is required" },
          () => ({
            validator(_, value: string) {
              return isRegex(value)
                ? Promise.resolve()
                : Promise.reject(
                    new Error("Must be a valid regular expression"),
                  );
            },
          }),
        ]}
      >
        <Input />
      </Form.Item>
      <Form.Item
        name={["parameters", 1]}
        label="Replacement"
        rules={[
          () => ({
            validator(_, value: string) {
              return isValidReplacementString(value)
                ? Promise.resolve()
                : Promise.reject(
                    new Error(
                      "Must be a valid replacement string (placeholders in $\\d format)",
                    ),
                  );
            },
          }),
        ]}
      >
        <Input />
      </Form.Item>
    </>
  );
};
