import React, { useEffect, useState } from "react";
import { Button, Form, Input, Modal, Select } from "antd";
import { api } from "../../../api/api.ts";
import { EndpointMock, EndpointMockRequest } from "../../../api/apiTypes.ts";
import { useChainElements } from "../../../hooks/testing/useChainElements.ts";
import { useNotificationService } from "../../../hooks/useNotificationService.tsx";
import { useModalContext } from "../../../ModalContextProvider.tsx";
import { isHttpEndpoint } from "../../testing/testingElements.ts";

const FORM_ID = "createEndpointMockForm";

/** Creation defaults; a test case uses different ones, so neither set is shared. */
const DEFAULT_ENABLED = true;
const DEFAULT_STATUS = 200;
const DEFAULT_DELAY = 0;

export type CreateEndpointMockModalProps = {
  chainId: string;
  onCreated: (endpointMock: EndpointMock) => void;
};

type FormData = {
  name: string;
  elementId?: string;
  description?: string;
};

export const CreateEndpointMockModal: React.FC<
  CreateEndpointMockModalProps
> = ({ chainId, onCreated }) => {
  const [form] = Form.useForm<FormData>();
  const { closeContainingModal } = useModalContext();
  const notificationService = useNotificationService();
  const [saving, setSaving] = useState(false);

  const {
    elements: endpoints,
    isLoading: endpointsLoading,
    options: endpointOptions,
  } = useChainElements(chainId, isHttpEndpoint);

  // The first endpoint is preselected, which is what the mock answers for unless
  // the user picks another.
  useEffect(() => {
    if (endpoints.length > 0) {
      form.setFieldValue("elementId", endpoints[0].id);
    }
  }, [endpoints, form]);

  const submit = async (values: FormData) => {
    setSaving(true);
    const request: EndpointMockRequest = {
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
    try {
      const created = await api.createEndpointMock(request);
      closeContainingModal();
      onCreated(created);
    } catch (error) {
      notificationService.requestFailed(
        "Failed to create an endpoint mock",
        error,
      );
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal
      title="Create Endpoint Mock"
      open={true}
      onCancel={closeContainingModal}
      footer={[
        <Button key="cancel" disabled={saving} onClick={closeContainingModal}>
          Cancel
        </Button>,
        <Button
          key="submit"
          type="primary"
          form={FORM_ID}
          htmlType="submit"
          loading={saving}
        >
          Save
        </Button>,
      ]}
    >
      <Form<FormData>
        id={FORM_ID}
        form={form}
        disabled={saving}
        layout="vertical"
        onFinish={(values) => void submit(values)}
      >
        <Form.Item
          label="Name"
          name="name"
          rules={[
            { required: true, message: "Enter a name for the endpoint mock." },
          ]}
        >
          <Input data-testid="endpoint-mock-name" autoFocus />
        </Form.Item>
        <Form.Item label="Endpoint" name="elementId">
          <Select
            allowClear
            loading={endpointsLoading}
            options={endpointOptions}
            placeholder="Select an HTTP endpoint"
          />
        </Form.Item>
        <Form.Item label="Description" name="description">
          <Input.TextArea className="fixed-textarea" />
        </Form.Item>
      </Form>
    </Modal>
  );
};
