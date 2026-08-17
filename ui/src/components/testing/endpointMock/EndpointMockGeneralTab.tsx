import React, { useEffect, useMemo, useState } from "react";
import { Form, Input, Select, Switch } from "antd";
import { api } from "../../../api/api.ts";
import { Element } from "../../../api/apiTypes.ts";
import { useNotificationService } from "../../../hooks/useNotificationService.tsx";
import { useEndpointMockEditor } from "../../../pages/testing/EndpointMockPage.tsx";
import { flattenElements, isHttpEndpoint } from "../testingElements.ts";

export const EndpointMockGeneralTab: React.FC = () => {
  const { endpointMock, chainId, readonly, onChange } = useEndpointMockEditor();
  const notificationService = useNotificationService();
  const [endpoints, setEndpoints] = useState<Element[]>([]);
  const [endpointsLoading, setEndpointsLoading] = useState(false);
  const [chainName, setChainName] = useState<string>();

  const reference = endpointMock.endpointReference;
  const referenceChainId = reference?.chainId;

  useEffect(() => {
    if (!referenceChainId) {
      setEndpoints([]);
      return;
    }
    let cancelled = false;
    setEndpointsLoading(true);
    void (async () => {
      try {
        const elements = await api.getElements(referenceChainId);
        if (!cancelled) {
          setEndpoints(flattenElements(elements).filter(isHttpEndpoint));
        }
      } catch (error) {
        if (!cancelled) {
          setEndpoints([]);
          notificationService.requestFailed(
            "Failed to load chain elements",
            error,
          );
        }
      } finally {
        if (!cancelled) {
          setEndpointsLoading(false);
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [referenceChainId, notificationService]);

  // The admin scope has no chain in the route, so the name comes off the mock itself.
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

  const endpointOptions = useMemo(
    () =>
      endpoints.map((endpoint) => ({
        value: endpoint.id,
        label: endpoint.name,
      })),
    [endpoints],
  );

  return (
    <Form layout="vertical" disabled={readonly} style={{ maxWidth: 720 }}>
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
            <a href={`/chains/${referenceChainId}`}>
              {chainName ?? referenceChainId}
            </a>
          ) : (
            "-"
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
      <Form.Item label="Description">
        <Input.TextArea
          aria-label="Description"
          className="fixed-textarea"
          rows={5}
          value={endpointMock.description}
          onChange={(event) => onChange({ description: event.target.value })}
        />
      </Form.Item>
    </Form>
  );
};

export default EndpointMockGeneralTab;
