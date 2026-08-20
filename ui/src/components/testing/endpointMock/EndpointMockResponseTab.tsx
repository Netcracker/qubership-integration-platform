import React from "react";
import { Flex, Form } from "antd";
import {
  TestingMessage,
  TestingNamedParameter,
  TestingResponseSettings,
} from "../../../api/apiTypes.ts";
import { Script } from "../../Script.tsx";
import {
  getHttpFieldNameError,
  getHttpFieldValueError,
} from "../../../misc/http-field-utils.ts";
import { useEndpointMockEditor } from "../../../pages/testing/EndpointMockPage.tsx";
import { NameValueTable } from "../NameValueTable.tsx";

/** Settings a mock saved before it named a response has none of yet. */
const EMPTY_RESPONSE_SETTINGS: TestingResponseSettings = {
  message: { body: null, headers: [] },
  status: 200,
  delay: 0,
};

const EMPTY_MESSAGE: TestingMessage = { body: null, headers: [] };

/** What the mock answers with. Its status and delay are on the General tab. */
export const EndpointMockResponseTab: React.FC = () => {
  const { endpointMock, readonly, onChange } = useEndpointMockEditor();

  const settings = endpointMock.responseSettings ?? EMPTY_RESPONSE_SETTINGS;
  const message = settings.message ?? EMPTY_MESSAGE;

  const updateMessage = (changes: Partial<TestingMessage>) =>
    onChange({
      responseSettings: { ...settings, message: { ...message, ...changes } },
    });

  return (
    <Flex vertical gap={8} style={{ flex: 1, minWidth: 0 }}>
      <NameValueTable
        data-testid="response-headers"
        title="Headers"
        rowNoun="header"
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
