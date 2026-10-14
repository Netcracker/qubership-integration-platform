import React from "react";
import { Form, Input, InputNumber, Select, Switch } from "antd";
import { TestingResponseSettings } from "../../../api/apiTypes.ts";
import { useChainElements } from "../../../hooks/testing/useChainElements.ts";
import { useChainName } from "../../../hooks/testing/useChainName.ts";
import { PLACEHOLDER } from "../../../misc/format-utils.ts";
import { useEndpointMockEditor } from "../../../pages/testing/EndpointMockPage.tsx";
import { NUMBER_FIELD_WIDTH, editorFormLayout } from "../editorForm.ts";
import styles from "../editorForm.module.css";
import { getResponseStatusError } from "../endpointMocks.ts";
import { isHttpEndpoint } from "../testingElements.ts";
import { RowLink } from "../../table/RowLink.tsx";

/** Settings a mock saved before it named a response has none of yet. */
const EMPTY_RESPONSE_SETTINGS: TestingResponseSettings = {
  message: { body: null, headers: [] },
  status: 200,
  delay: 0,
};

export const EndpointMockGeneralTab: React.FC = () => {
  const { endpointMock, chainId, readonly, onChange } = useEndpointMockEditor();

  const reference = endpointMock.endpointReference;
  const referenceChainId = reference?.chainId;
  const settings = endpointMock.responseSettings ?? EMPTY_RESPONSE_SETTINGS;

  const { isLoading: endpointsLoading, options: endpointOptions } =
    useChainElements(referenceChainId, isHttpEndpoint);
  // The admin scope has no chain in the route, so the name comes off the mock itself.
  const chainName = useChainName(chainId ? undefined : referenceChainId);

  const updateSettings = (changes: Partial<TestingResponseSettings>) =>
    onChange({ responseSettings: { ...settings, ...changes } });

  // Bounding the field instead would rewrite a status stored before the range
  // was enforced: antd clamps an out-of-range value as soon as the field is
  // left. The status the mock carries is shown as it stands, and the editor
  // shuts Save over a status broken here.
  const statusError = getResponseStatusError(settings.status);

  return (
    <div className={styles.pageContainer}>
      <div className={styles.formContent}>
        <Form {...editorFormLayout} disabled={readonly}>
          <Form.Item
            label="Name"
            required
            validateStatus={endpointMock.name.trim() ? undefined : "error"}
            help={
              endpointMock.name.trim()
                ? undefined
                : "Enter a name for the endpoint mock."
            }
          >
            <Input
              aria-label="Name"
              value={endpointMock.name}
              onChange={(event) => onChange({ name: event.target.value })}
            />
          </Form.Item>
          <Form.Item label="Description">
            <Input.TextArea
              aria-label="Description"
              className="fixed-textarea"
              rows={4}
              value={endpointMock.description}
              onChange={(event) =>
                onChange({ description: event.target.value })
              }
            />
          </Form.Item>
          <Form.Item label="Enabled">
            <Switch
              aria-label="Enabled"
              checked={endpointMock.enabled}
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
            label="Endpoint"
            required
            validateStatus={reference?.elementId ? undefined : "error"}
            help={reference?.elementId ? undefined : "Select an HTTP endpoint."}
          >
            <Select
              aria-label="Endpoint"
              allowClear
              loading={endpointsLoading}
              options={endpointOptions}
              placeholder="Select an HTTP endpoint"
              value={reference?.elementId || undefined}
              onChange={(elementId: string | undefined) =>
                onChange({
                  endpointReference: {
                    chainId: referenceChainId ?? chainId ?? "",
                    elementId: elementId ?? "",
                  },
                })
              }
            />
          </Form.Item>
          <Form.Item
            label="Status Code"
            required
            validateStatus={statusError ? "error" : undefined}
            help={statusError}
          >
            <InputNumber
              aria-label="Status Code"
              style={{ width: NUMBER_FIELD_WIDTH }}
              value={settings.status}
              // Clearing the field reports null. Mapping that to zero would store
              // "unset", under which the mock answers 200 instead of what it said.
              onChange={(status) =>
                updateSettings({ status: status ?? settings.status })
              }
            />
          </Form.Item>
          <Form.Item label="Delay, ms" required>
            <InputNumber
              aria-label="Delay, ms"
              min={0}
              style={{ width: NUMBER_FIELD_WIDTH }}
              value={settings.delay}
              onChange={(delay) => updateSettings({ delay: delay ?? 0 })}
            />
          </Form.Item>
        </Form>
      </div>
    </div>
  );
};
