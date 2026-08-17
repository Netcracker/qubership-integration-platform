import React from "react";
import { Form, Input, Select, Switch } from "antd";
import { useNavigate } from "react-router";
import { useChainElements } from "../../../hooks/testing/useChainElements.ts";
import { useChainName } from "../../../hooks/testing/useChainName.ts";
import { PLACEHOLDER } from "../../../misc/format-utils.ts";
import { useEndpointMockEditor } from "../../../pages/testing/EndpointMockPage.tsx";
import { isHttpEndpoint } from "../testingElements.ts";

/**
 * The endpoint picker sits here rather than on a Request tab, and the test case
 * editor puts its trigger picker on Request instead. The two tab layouts are what
 * the source ports to, so they stay as they are.
 */
export const EndpointMockGeneralTab: React.FC = () => {
  const { endpointMock, chainId, readonly, onChange } = useEndpointMockEditor();
  const navigate = useNavigate();

  const reference = endpointMock.endpointReference;
  const referenceChainId = reference?.chainId;

  const { isLoading: endpointsLoading, options: endpointOptions } =
    useChainElements(referenceChainId, isHttpEndpoint);
  // The admin scope has no chain in the route, so the name comes off the mock itself.
  const chainName = useChainName(chainId ? undefined : referenceChainId);

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
            <a onClick={() => void navigate(`/chains/${referenceChainId}`)}>
              {chainName ?? referenceChainId}
            </a>
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
