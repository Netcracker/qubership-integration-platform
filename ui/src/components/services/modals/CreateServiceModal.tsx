import React, { useCallback, useState } from "react";
import { Modal, Input, Button, Form, Alert } from "antd";
import { getErrorMessage } from "../../../misc/error-utils";
import { useModalContext } from "../../../ModalContextProvider.tsx";
import { IntegrationSystemType } from "../../../api/apiTypes.ts";

interface CreateServiceModalProps {
  serviceType: IntegrationSystemType;
  defaultName?: string;
  onSubmit: (
    name: string,
    description: string,
    properties: Record<string, string>,
  ) => Promise<void>;
}

type CreateServiceFormValues = {
  name: string;
  identifier?: string; // MCP service identifier
  description: string;
};

export const CreateServiceModal: React.FC<CreateServiceModalProps> = ({
  serviceType,
  defaultName,
  onSubmit,
}) => {
  const { closeContainingModal } = useModalContext();
  const [form] = Form.useForm<CreateServiceFormValues>();
  const [isLoading, setIsLoading] = useState<boolean>(false);
  const [errorText, setErrorText] = useState<string | null>(null);

  const handleOk = useCallback(
    async ({ name, description, identifier }: CreateServiceFormValues) => {
      try {
        setIsLoading(true);
        setErrorText(null);
        const properties: Record<string, string> = identifier
          ? { identifier }
          : {};
        await onSubmit(name, description, properties);
        closeContainingModal();
      } catch (e) {
        setErrorText(getErrorMessage(e));
      } finally {
        setIsLoading(false);
      }
    },
    [closeContainingModal, onSubmit],
  );

  return (
    <Modal
      open={true}
      title="Create service"
      onCancel={closeContainingModal}
      footer={
        <>
          <Button onClick={closeContainingModal} disabled={isLoading}>
            Cancel
          </Button>
          <Button
            form={"createServiceForm"}
            type="primary"
            htmlType="submit"
            loading={isLoading}
            disabled={isLoading}
          >
            Create
          </Button>
        </>
      }
    >
      <Form<CreateServiceFormValues>
        id={"createServiceForm"}
        form={form}
        layout="vertical"
        disabled={isLoading}
        onFinish={(values) => void handleOk(values)}
        initialValues={{ name: defaultName }}
      >
        <Form.Item
          label="Name"
          name="name"
          rules={[{ required: true, message: "Enter service name" }]}
        >
          <Input autoFocus maxLength={128} />
        </Form.Item>
        {serviceType === IntegrationSystemType.MCP ? (
          <Form.Item
            label="Identifier"
            name="identifier"
            rules={[{ required: true, message: "Enter service identifier" }]}
          >
            <Input maxLength={128} />
          </Form.Item>
        ) : null}
        <Form.Item label="Description" name="description">
          <Input.TextArea maxLength={512} />
        </Form.Item>
        {errorText && <Alert title={errorText} type="error" showIcon />}
      </Form>
    </Modal>
  );
};
