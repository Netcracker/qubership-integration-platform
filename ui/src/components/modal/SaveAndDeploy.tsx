import React, { useState } from "react";
import { Button, Form, Modal } from "antd";
import { useModalContext } from "../../ModalContextProvider.tsx";
import { Domain, SelectDomains } from "../SelectDomains.tsx";
import { DomainType } from "../../api/apiTypes.ts";

export type SaveAndDeployProps = {
  chainId?: string;
  onSubmit?: (domains: Domain[]) => void | Promise<void>;
};

type SaveAndDeployFormData = {
  domains: Domain[];
};

export const SaveAndDeploy: React.FC<SaveAndDeployProps> = ({
  chainId,
  onSubmit,
}) => {
  const [form] = Form.useForm();
  const [confirmLoading, setConfirmLoading] = useState(false);
  const { closeContainingModal } = useModalContext();

  const handleSubmit = (data: SaveAndDeployFormData) => {
    if (!chainId) {
      return;
    }
    setConfirmLoading(true);
    try {
      const result = onSubmit?.(data.domains);
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
        initialValues={{
          domains: [{ name: "default", type: DomainType.NATIVE }],
        }}
        labelCol={{ span: 4 }}
        style={{ maxWidth: 600 }}
        labelWrap
        onFinish={(values) => handleSubmit(values)}
      >
        <Form.Item
          label="Domains"
          name="domains"
          rules={[
            { required: true, message: "Please specify at least one domain" },
          ]}
        >
          <SelectDomains />
        </Form.Item>
      </Form>
    </Modal>
  );
};
