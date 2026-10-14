import React from "react";
import { api } from "../../../api/api.ts";
import { EndpointMock, EndpointMockRequest } from "../../../api/apiTypes.ts";
import { isHttpEndpoint } from "../../testing/testingElements.ts";
import {
  CreateTestingEntityFormData,
  CreateTestingEntityModal,
} from "./CreateTestingEntityModal.tsx";

/** Creation defaults; a test case uses different ones, so neither set is shared. */
const DEFAULT_ENABLED = true;
const DEFAULT_STATUS = 200;
const DEFAULT_DELAY = 0;

export type CreateEndpointMockModalProps = {
  chainId: string;
  onCreated: (endpointMock: EndpointMock) => void;
};

function buildRequest(
  chainId: string,
  values: CreateTestingEntityFormData,
): EndpointMockRequest {
  return {
    name: values.name.trim(),
    description: values.description ?? "",
    enabled: DEFAULT_ENABLED,
    // The chain reference carries the mock even when no endpoint is picked
    // yet: it is what scopes the mock to the chain in every list.
    endpointReference: { chainId, elementId: values.elementId ?? "" },
    responseSettings: {
      message: { body: null, headers: [] },
      status: DEFAULT_STATUS,
      delay: DEFAULT_DELAY,
    },
    requestMatchers: [],
  };
}

export const CreateEndpointMockModal: React.FC<
  CreateEndpointMockModalProps
> = ({ chainId, onCreated }) => (
  <CreateTestingEntityModal<EndpointMock>
    chainId={chainId}
    onCreated={onCreated}
    formId="createEndpointMockForm"
    title="Create Endpoint Mock"
    nameTestId="endpoint-mock-name"
    nameRequiredMessage="Enter a name for the endpoint mock."
    createFailedMessage="Failed to create an endpoint mock"
    elementLabel="Endpoint"
    elementPlaceholder="Select an HTTP endpoint"
    elementPredicate={isHttpEndpoint}
    create={(values) => api.createEndpointMock(buildRequest(chainId, values))}
  />
);
