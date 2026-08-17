import React from "react";
import { Form, Input, Switch } from "antd";
import { useTestCaseEditor } from "../../../pages/testing/TestCasePage.tsx";

export const TestCaseGeneralTab: React.FC = () => {
  const { testCase, readonly, onChange } = useTestCaseEditor();

  return (
    <Form layout="vertical" disabled={readonly} style={{ maxWidth: 720 }}>
      <Form.Item
        label="Name"
        required
        validateStatus={testCase.name.trim() ? undefined : "error"}
        help={
          testCase.name.trim() ? undefined : "Enter a name for the test case."
        }
      >
        <Input
          aria-label="Name"
          value={testCase.name}
          onChange={(event) => onChange({ name: event.target.value })}
        />
      </Form.Item>
      <Form.Item label="Enabled">
        <Switch
          aria-label="Enabled"
          checked={testCase.enabled}
          onChange={(enabled) => onChange({ enabled })}
        />
      </Form.Item>
      <Form.Item label="Description">
        <Input.TextArea
          aria-label="Description"
          className="fixed-textarea"
          rows={5}
          value={testCase.description}
          onChange={(event) => onChange({ description: event.target.value })}
        />
      </Form.Item>
    </Form>
  );
};
