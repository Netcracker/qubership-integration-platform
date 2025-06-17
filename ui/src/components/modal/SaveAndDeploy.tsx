import React, { useState } from "react";
import { Button, Form, Modal, Select, SelectProps } from "antd";
import { useModalContext } from "../../ModalContextProvider.tsx";
import { useDomains } from "../../hooks/useDomains.tsx";

export type SaveAndDeployProps = {
  chainId?: string;
  onSubmit?: (domain: string) => void;
};

type SaveAndDeployFormData = {
  domain: string;
}

export const SaveAndDeploy: React.FC<SaveAndDeployProps> = ({ chainId, onSubmit }) => {
  const [form] = Form.useForm();
  const [confirmLoading, setConfirmLoading] = useState(false);
  const { closeContainingModal } = useModalContext();
  const domainsState = useDomains();

  const domainOptions: SelectProps["options"] = domainsState.domains
    ?.sort((d1, d2) => d1.name.localeCompare(d2.name))
    .map((domain) => ({
      label: domain.name,
      value: domain.id,
    }));

  const handleSubmit = async (data: SaveAndDeployFormData) => {
    if (!chainId) {
      return;
    }
    setConfirmLoading(true);
    try {
      console.log({ data });
      onSubmit?.(data.domain);
    } finally {
      setConfirmLoading(false);
      closeContainingModal();
    }
  };

  return (
    <Modal
      title={"Save and deploy"}
      open={true}
      onCancel={closeContainingModal}
      footer={[
        <Button key="cancel" onClick={closeContainingModal}>
          Cancel
        </Button>,
        <Button
          key="submit"
          type="primary"
          form="saveAndDeployForm"
          htmlType={"submit"}
          loading={confirmLoading}
        >
          Deploy
        </Button>,
      ]}
    >
      <Form<SaveAndDeployFormData>
        id="saveAndDeployForm"
        form={form}
        layout="horizontal"
        labelCol={{ span: 4 }}
        style={{ maxWidth: 600 }}
        disabled={domainsState.isLoading}
        labelWrap
        onFinish={(values) => handleSubmit(values)}
      >
        <Form.Item
          label="Domain"
          name="domain"
          rules={[{ required: true, message: "Please specify a domain" }]}
        >
          <Select
            options={domainOptions}
            loading={domainsState.isLoading}
          />
        </Form.Item>
      </Form>
    </Modal>
  );
}
