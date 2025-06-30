import React, { useState } from "react";
import { Button, Form, Modal, Select, SelectProps } from "antd";
import { useModalContext } from "../../ModalContextProvider.tsx";
import { useDomains } from "../../hooks/useDomains.tsx";

export type SaveAndDeployProps = {
  chainId?: string;
  onSubmit?: (domain: string) => void | Promise<void>;
};

type SaveAndDeployFormData = {
  domain: string;
};

export const SaveAndDeploy: React.FC<SaveAndDeployProps> = ({
  chainId,
  onSubmit,
}) => {
  const [form] = Form.useForm();
  const [confirmLoading, setConfirmLoading] = useState(false);
  const { closeContainingModal } = useModalContext();
  const { isLoading: domainsLoading, domains } = useDomains();

  const domainOptions: SelectProps["options"] = domains
    ?.sort((d1, d2) => d1.name.localeCompare(d2.name))
    .map((domain) => ({
      label: domain.name,
      value: domain.id,
    }));

  const handleSubmit = (data: SaveAndDeployFormData) => {
    if (!chainId) {
      return;
    }
    setConfirmLoading(true);
    try {
      const result = onSubmit?.(data.domain);
      if (result instanceof Promise) {
        void result.finally(() => {
          setConfirmLoading(false);
          closeContainingModal();
        });
      } else {
        setConfirmLoading(false);
        closeContainingModal();
      }
      // eslint-disable-next-line @typescript-eslint/no-unused-vars
    } catch (error) {
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
        disabled={domainsLoading}
        labelWrap
        onFinish={(values) => handleSubmit(values)}
      >
        <Form.Item
          label="Domain"
          name="domain"
          rules={[{ required: true, message: "Please specify a domain" }]}
        >
          <Select options={domainOptions} loading={domainsLoading} />
        </Form.Item>
      </Form>
    </Modal>
  );
};
