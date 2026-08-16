import React, { useEffect, useMemo, useState } from "react";
import { Flex, Form, InputNumber, Select } from "antd";
import { api } from "../../../api/api.ts";
import {
  Element,
  TestingMessage,
  TestingNamedParameter,
  TestingRequestSettings,
} from "../../../api/apiTypes.ts";
import { Script } from "../../Script.tsx";
import { useNotificationService } from "../../../hooks/useNotificationService.tsx";
import { useTestCaseEditor } from "../../../pages/testing/TestCasePage.tsx";
import { NameValueTable } from "../NameValueTable.tsx";
import { getHttpMethods, isHttpTrigger } from "../testingElements.ts";

/** Settings a case saved before it named a trigger has none of yet. */
const EMPTY_REQUEST_SETTINGS: TestingRequestSettings = {
  queryParameters: [],
  pathParameters: [],
  message: { body: null, headers: [] },
  method: "",
  timeout: 0,
};

const EMPTY_MESSAGE: TestingMessage = { body: null, headers: [] };

export const TestCaseRequestTab: React.FC = () => {
  const { testCase, chainId, readonly, onChange } = useTestCaseEditor();
  const notificationService = useNotificationService();
  const [triggers, setTriggers] = useState<Element[]>([]);
  const [triggersLoading, setTriggersLoading] = useState(false);
  const [chainName, setChainName] = useState<string>();

  const reference = testCase.triggerReference;
  const referenceChainId = reference?.chainId;
  const settings = testCase.requestSettings ?? EMPTY_REQUEST_SETTINGS;
  const message = settings.message ?? EMPTY_MESSAGE;

  useEffect(() => {
    if (!referenceChainId) {
      setTriggers([]);
      return;
    }
    let cancelled = false;
    setTriggersLoading(true);
    void (async () => {
      try {
        const elements = await api.getElements(referenceChainId);
        if (!cancelled) {
          setTriggers(elements.filter(isHttpTrigger));
        }
      } catch (error) {
        if (!cancelled) {
          setTriggers([]);
          notificationService.requestFailed(
            "Failed to load chain elements",
            error,
          );
        }
      } finally {
        if (!cancelled) {
          setTriggersLoading(false);
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [referenceChainId, notificationService]);

  // The admin scope has no chain in the route, so the name comes off the case itself.
  useEffect(() => {
    if (chainId || !referenceChainId) {
      setChainName(undefined);
      return;
    }
    let cancelled = false;
    void (async () => {
      try {
        const chain = await api.getChain(referenceChainId);
        if (!cancelled) {
          setChainName(chain.name);
        }
      } catch {
        if (!cancelled) {
          setChainName(undefined);
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [chainId, referenceChainId]);

  const triggerOptions = useMemo(
    () =>
      triggers.map((trigger) => ({ value: trigger.id, label: trigger.name })),
    [triggers],
  );

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

  const updateMessage = (changes: Partial<TestingMessage>) =>
    updateSettings({ message: { ...message, ...changes } });

  return (
    <Flex vertical gap={16} style={{ flex: 1, minWidth: 0 }}>
      <Form layout="vertical" disabled={readonly} style={{ maxWidth: 720 }}>
        {chainId ? null : (
          <Form.Item label="Chain" required>
            {referenceChainId ? (
              <a href={`/chains/${referenceChainId}`}>
                {chainName ?? referenceChainId}
              </a>
            ) : (
              "-"
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
            style={{ width: "100%" }}
            value={settings.timeout}
            onChange={(timeout) => updateSettings({ timeout: timeout ?? 0 })}
          />
        </Form.Item>
      </Form>
      <NameValueTable
        data-testid="path-parameters"
        title="Path Parameters"
        values={settings.pathParameters}
        readonly={readonly}
        onChange={(pathParameters: TestingNamedParameter[]) =>
          updateSettings({ pathParameters })
        }
      />
      <NameValueTable
        data-testid="query-parameters"
        title="Query Parameters"
        values={settings.queryParameters}
        readonly={readonly}
        onChange={(queryParameters: TestingNamedParameter[]) =>
          updateSettings({ queryParameters })
        }
      />
      <NameValueTable
        data-testid="headers"
        title="Headers"
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

export default TestCaseRequestTab;
