import React, { useCallback, useState } from "react";
import { Modal, Input, Button, Form, Alert } from "antd";
import { getErrorMessage } from "../../../misc/error-utils";
import { useModalContext } from "../../../ModalContextProvider.tsx";

interface CreateServiceModalProps {
  defaultName?: string;
  onSubmit: (name: string, description: string) => Promise<void>;
}

type CreateServiceFormValues = {
  name: string;
  description: string;
};

export const CreateServiceModal: React.FC<CreateServiceModalProps> = ({
  defaultName,
  onSubmit,
}) => {
  const { closeContainingModal } = useModalContext();
  const [form] = Form.useForm<CreateServiceFormValues>();
  const [isLoading, setIsLoading] = useState<boolean>(false);
  const [errorText, setErrorText] = useState<string | null>(null);

  const handleOk = useCallback(
    async ({ name, description }: CreateServiceFormValues) => {
      try {
        setIsLoading(true);
        setErrorText(null);
        await onSubmit(name, description);
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
        <Form.Item label="Description" name="description">
          <Input.TextArea maxLength={512} />
        </Form.Item>
        {errorText && <Alert message={errorText} type="error" showIcon />}
      </Form>
    </Modal>
  );
};
