import React, { useMemo } from "react";
import { Form, Input, InputNumber, Select, Switch } from "antd";
import { TestingRequestSettings } from "../../../api/apiTypes.ts";
import { useChainElements } from "../../../hooks/testing/useChainElements.ts";
import { useChainName } from "../../../hooks/testing/useChainName.ts";
import { PLACEHOLDER } from "../../../misc/format-utils.ts";
import { useTestCaseEditor } from "../../../pages/testing/TestCasePage.tsx";
import { NUMBER_FIELD_WIDTH, editorFormLayout } from "../editorForm.ts";
import styles from "../editorForm.module.css";
import { getHttpMethods, isHttpTrigger } from "../testingElements.ts";
import { RowLink } from "../../table/RowLink.tsx";

/** Settings a case saved before it named a trigger has none of yet. */
const EMPTY_REQUEST_SETTINGS: TestingRequestSettings = {
  queryParameters: [],
  pathParameters: [],
  message: { body: null, headers: [] },
  method: "",
  timeout: 0,
};

export const TestCaseGeneralTab: React.FC = () => {
  const { testCase, chainId, readonly, onChange } = useTestCaseEditor();

  const reference = testCase.triggerReference;
  const referenceChainId = reference?.chainId;
  const settings = testCase.requestSettings ?? EMPTY_REQUEST_SETTINGS;

  const {
    elements: triggers,
    isLoading: triggersLoading,
    options: triggerOptions,
  } = useChainElements(referenceChainId, isHttpTrigger);
  // The admin scope has no chain in the route, so the name comes off the case itself.
  const chainName = useChainName(chainId ? undefined : referenceChainId);

  const selectedTrigger = triggers.find(
    (trigger) => trigger.id === reference?.elementId,
  );

  // The stored method stays offered even when the trigger no longer accepts it,
  // so an existing case does not silently lose what it was saved with.
  const methodOptions = useMemo(() => {
    const methods = new Set(getHttpMethods(selectedTrigger));
    if (settings.method) {
      methods.add(settings.method);
    }
    return [...methods].map((method) => ({ value: method, label: method }));
  }, [selectedTrigger, settings.method]);

  const updateSettings = (changes: Partial<TestingRequestSettings>) =>
    onChange({ requestSettings: { ...settings, ...changes } });

  return (
    <div className={styles.pageContainer}>
      <div className={styles.formContent}>
        <Form {...editorFormLayout} disabled={readonly}>
          <Form.Item
            label="Name"
            required
            validateStatus={testCase.name.trim() ? undefined : "error"}
            help={
              testCase.name.trim()
                ? undefined
                : "Enter a name for the test case."
            }
          >
            <Input
              aria-label="Name"
              value={testCase.name}
              onChange={(event) => onChange({ name: event.target.value })}
            />
          </Form.Item>
          <Form.Item label="Description">
            <Input.TextArea
              aria-label="Description"
              className="fixed-textarea"
              rows={4}
              value={testCase.description}
              onChange={(event) =>
                onChange({ description: event.target.value })
              }
            />
          </Form.Item>
          <Form.Item label="Enabled">
            <Switch
              aria-label="Enabled"
              checked={testCase.enabled}
              onChange={(enabled) => onChange({ enabled })}
            />
          </Form.Item>
          {chainId ? null : (
            <Form.Item label="Chain" required>
              {referenceChainId ? (
                <RowLink to={`/chains/${referenceChainId}`}>
                  {chainName ?? referenceChainId}
                </RowLink>
              ) : (
                PLACEHOLDER
              )}
            </Form.Item>
          )}
          <Form.Item
            label="Trigger"
            required
            validateStatus={reference?.elementId ? undefined : "error"}
            help={reference?.elementId ? undefined : "Select an HTTP trigger."}
          >
            <Select
              aria-label="Trigger"
              allowClear
              loading={triggersLoading}
              options={triggerOptions}
              placeholder="Select an HTTP trigger"
              value={reference?.elementId || undefined}
              onChange={(elementId: string | undefined) =>
                onChange({
                  triggerReference: {
                    chainId: referenceChainId ?? chainId ?? "",
                    elementId: elementId ?? "",
                  },
                })
              }
            />
          </Form.Item>
          <Form.Item
            label="Method"
            required
            validateStatus={settings.method ? undefined : "error"}
            help={settings.method ? undefined : "Select a method."}
          >
            <Select
              aria-label="Method"
              options={methodOptions}
              placeholder="Select a method"
              value={settings.method || undefined}
              onChange={(method: string) => updateSettings({ method })}
            />
          </Form.Item>
          <Form.Item label="Timeout, ms" required>
            <InputNumber
              aria-label="Timeout, ms"
              min={0}
              style={{ width: NUMBER_FIELD_WIDTH }}
              value={settings.timeout}
              onChange={(timeout) => updateSettings({ timeout: timeout ?? 0 })}
            />
          </Form.Item>
        </Form>
      </div>
    </div>
  );
};
