import React, { useMemo } from "react";
import { Breadcrumb, Empty, Flex, Skeleton, Tabs } from "antd";
import { Outlet, useOutletContext, useParams } from "react-router";
import { api } from "../../api/api.ts";
import { EndpointMock, EndpointMockRequest } from "../../api/apiTypes.ts";
import { endpointMockViolations } from "../../components/testing/violations.ts";
import {
  TestingEntityEditorConfig,
  useTestingEntityEditor,
} from "../../hooks/testing/useTestingEntityEditor.tsx";

const TABS = [
  { key: "general", label: "General" },
  { key: "response", label: "Response Parameters" },
  { key: "request-matchers", label: "Request Matchers" },
];

/** State the routed sub-tabs share with the editor that owns the draft. */
export type EndpointMockEditorContext = {
  endpointMock: EndpointMock;
  /** Set when the editor was reached inside a chain; absent in the admin scope. */
  chainId?: string;
  readonly: boolean;
  onChange: (changes: Partial<EndpointMock>) => void;
};

export function useEndpointMockEditor(): EndpointMockEditorContext {
  return useOutletContext<EndpointMockEditorContext>();
}

function toRequest(endpointMock: EndpointMock): EndpointMockRequest {
  return {
    name: endpointMock.name.trim(),
    description: endpointMock.description,
    enabled: endpointMock.enabled,
    endpointReference: endpointMock.endpointReference,
    responseSettings: endpointMock.responseSettings,
    requestMatchers: endpointMock.requestMatchers,
  };
}

/** No method here: a mock answers whatever the endpoint is called with. */
function isValid(endpointMock: EndpointMock): boolean {
  return (
    endpointMock.name.trim().length > 0 &&
    !!endpointMock.endpointReference?.chainId &&
    !!endpointMock.endpointReference?.elementId
  );
}

const EDITOR: TestingEntityEditorConfig<EndpointMock, EndpointMockRequest> = {
  listSegment: "endpoint-mocks",
  tabs: TABS,
  nouns: { singular: "endpoint mock", listTitle: "Endpoint Mocks" },
  saveTestId: "endpoint-mock-save",
  get: (id) => api.getEndpointMock(id),
  update: (id, request) => api.updateEndpointMock(id, request),
  toRequest,
  violations: endpointMockViolations,
  isValid,
};

export const EndpointMockPage: React.FC = () => {
  const { chainId, endpointMockId } = useParams<{
    chainId?: string;
    endpointMockId: string;
  }>();
  const {
    entity: endpointMock,
    loading,
    readonly,
    onChange,
    activeTab,
    onTabChange,
    breadcrumbItems,
  } = useTestingEntityEditor({ ...EDITOR, chainId, entityId: endpointMockId });

  const editorContext = useMemo<EndpointMockEditorContext | null>(
    () => (endpointMock ? { endpointMock, chainId, readonly, onChange } : null),
    [endpointMock, chainId, readonly, onChange],
  );

  if (loading) {
    return <Skeleton active />;
  }
  if (!endpointMock || !editorContext) {
    return <Empty description="Endpoint mock not found" />;
  }

  return (
    <Flex vertical gap={8} style={{ flex: 1, minWidth: 0 }}>
      <Breadcrumb items={breadcrumbItems} />
      <Tabs activeKey={activeTab} items={TABS} onChange={onTabChange} />
      <Outlet context={editorContext} />
    </Flex>
  );
};

export default EndpointMockPage;
