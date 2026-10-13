import React, { useState } from "react";
import { Button, Form, Modal } from "antd";
import { useModalContext } from "../../ModalContextProvider.tsx";
import { useInitialDomains } from "../../hooks/useInitialDomains.ts";
import { handleModalSubmit } from "../../misc/modal-submit.ts";
import {
  Domain,
  DomainsFormItem,
  isDomainSelectionInvalid,
} from "../SelectDomains.tsx";

export type SaveAndDeployProps = {
  chainId?: string;
  initialDomains?: Domain[];
  onSubmit?: (domains: Domain[]) => void | Promise<void>;
};

type SaveAndDeployFormData = {
  domains: Domain[];
};

export const SaveAndDeploy: React.FC<SaveAndDeployProps> = ({
  chainId,
  initialDomains,
  onSubmit,
}) => {
  const [form] = Form.useForm<SaveAndDeployFormData>();
  const [confirmLoading, setConfirmLoading] = useState(false);
  const { closeContainingModal } = useModalContext();
  const domains = Form.useWatch("domains", form);
  const isDeployDisabled = isDomainSelectionInvalid(domains);

  useInitialDomains(form, initialDomains);

  const handleSubmit = (data: SaveAndDeployFormData) => {
    if (!chainId) {
      return;
    }
    handleModalSubmit(
      () => onSubmit?.(data.domains),
      setConfirmLoading,
      closeContainingModal,
    );
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
          disabled={isDeployDisabled}
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
          domains: initialDomains ?? [],
        }}
        labelCol={{ span: 4 }}
        style={{ maxWidth: 600 }}
        labelWrap
        onFinish={(values) => handleSubmit(values)}
      >
        <DomainsFormItem />
      </Form>
    </Modal>
  );
};
