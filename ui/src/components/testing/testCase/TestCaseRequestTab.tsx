import React from "react";
import { Flex, Form } from "antd";
import {
  TestingMessage,
  TestingNamedParameter,
  TestingRequestSettings,
} from "../../../api/apiTypes.ts";
import { Script } from "../../Script.tsx";
import { useTestCaseEditor } from "../../../pages/testing/TestCasePage.tsx";
import { NameValueTable } from "../NameValueTable.tsx";

/** Settings a case saved before it named a trigger has none of yet. */
const EMPTY_REQUEST_SETTINGS: TestingRequestSettings = {
  queryParameters: [],
  pathParameters: [],
  message: { body: null, headers: [] },
  method: "",
  timeout: 0,
};

const EMPTY_MESSAGE: TestingMessage = { body: null, headers: [] };

/** What the case sends. The trigger it is sent to is named on the General tab. */
export const TestCaseRequestTab: React.FC = () => {
  const { testCase, readonly, onChange } = useTestCaseEditor();

  const settings = testCase.requestSettings ?? EMPTY_REQUEST_SETTINGS;
  const message = settings.message ?? EMPTY_MESSAGE;

  const updateSettings = (changes: Partial<TestingRequestSettings>) =>
    onChange({ requestSettings: { ...settings, ...changes } });

  const updateMessage = (changes: Partial<TestingMessage>) =>
    updateSettings({ message: { ...message, ...changes } });

  return (
    <Flex vertical gap={8} style={{ flex: 1, minWidth: 0 }}>
      <NameValueTable
        data-testid="path-parameters"
        title="Path Parameters"
        rowNoun="parameter"
        values={settings.pathParameters}
        readonly={readonly}
        onChange={(pathParameters: TestingNamedParameter[]) =>
          updateSettings({ pathParameters })
        }
      />
      <NameValueTable
        data-testid="query-parameters"
        title="Query Parameters"
        rowNoun="parameter"
        values={settings.queryParameters}
        readonly={readonly}
        onChange={(queryParameters: TestingNamedParameter[]) =>
          updateSettings({ queryParameters })
        }
      />
      <NameValueTable
        data-testid="headers"
        title="Headers"
        rowNoun="header"
        values={message.headers}
        readonly={readonly}
        onChange={(headers: TestingNamedParameter[]) =>
          updateMessage({ headers })
        }
      />
      <Form layout="vertical">
        <Form.Item label="Body">
          <Script
            data-testid="request-body"
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
