import React from "react";
import { Flex, Form, InputNumber } from "antd";
import {
  TestingMessage,
  TestingNamedParameter,
  TestingResponseSettings,
} from "../../../api/apiTypes.ts";
import { Script } from "../../Script.tsx";
import { useEndpointMockEditor } from "../../../pages/testing/EndpointMockPage.tsx";
import {
  getHttpFieldNameError,
  getHttpFieldValueError,
} from "../../../misc/http-field-utils.ts";
import { getResponseStatusError } from "../endpointMocks.ts";
import { NameValueTable } from "../NameValueTable.tsx";

/** Settings a mock saved before it named a response has none of yet. */
const EMPTY_RESPONSE_SETTINGS: TestingResponseSettings = {
  message: { body: null, headers: [] },
  status: 200,
  delay: 0,
};

const EMPTY_MESSAGE: TestingMessage = { body: null, headers: [] };

export const EndpointMockResponseTab: React.FC = () => {
  const { endpointMock, readonly, onChange } = useEndpointMockEditor();

  const settings = endpointMock.responseSettings ?? EMPTY_RESPONSE_SETTINGS;
  const message = settings.message ?? EMPTY_MESSAGE;

  const updateSettings = (changes: Partial<TestingResponseSettings>) =>
    onChange({ responseSettings: { ...settings, ...changes } });

  const updateMessage = (changes: Partial<TestingMessage>) =>
    updateSettings({ message: { ...message, ...changes } });

  // Bounding the field instead would rewrite a status stored before the range
  // was enforced: antd clamps an out-of-range value as soon as the field is
  // left. The status the mock carries is shown as it stands, and the editor
  // shuts Save over a status broken here.
  const statusError = getResponseStatusError(settings.status);

  return (
    <Flex vertical gap={16} style={{ flex: 1, minWidth: 0 }}>
      <Form layout="vertical" disabled={readonly} style={{ maxWidth: 720 }}>
        <Form.Item
          label="Status Code"
          required
          validateStatus={statusError ? "error" : undefined}
          help={statusError}
        >
          <InputNumber
            aria-label="Status Code"
            style={{ width: "100%" }}
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
            style={{ width: "100%" }}
            value={settings.delay}
            onChange={(delay) => updateSettings({ delay: delay ?? 0 })}
          />
        </Form.Item>
      </Form>
      <NameValueTable
        data-testid="response-headers"
        title="Headers"
        values={message.headers}
        readonly={readonly}
        validateName={getHttpFieldNameError}
        validateValue={getHttpFieldValueError}
        onChange={(headers: TestingNamedParameter[]) =>
          updateMessage({ headers })
        }
      />
      <Form layout="vertical">
        <Form.Item label="Body">
          <Script
            data-testid="response-body"
            mode="json"
            readOnly={readonly}
            value={message.body ?? ""}
            onChange={(body: string) => updateMessage({ body })}
          />
        </Form.Item>
      </Form>
    </Flex>
  );
};
