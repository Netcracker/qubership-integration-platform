import React, { useEffect, useState } from "react";
import { Button, Form, Modal } from "antd";
import { useModalContext } from "../../ModalContextProvider.tsx";
import { useInitialDomains } from "../../hooks/useInitialDomains.ts";
import { handleModalSubmit } from "../../misc/modal-submit.ts";
import { toDomains } from "../../misc/domain-utils.ts";
import { api } from "../../api/api.ts";
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
  const [domainsLoading, setDomainsLoading] = useState(false);
  const { closeContainingModal } = useModalContext();
  const domains = Form.useWatch("domains", form);
  const isDeployDisabled = domainsLoading || isDomainSelectionInvalid(domains);

  useInitialDomains(form, initialDomains);

  useEffect(() => {
    if (!chainId) {
      return;
    }
    let cancelled = false;
    setDomainsLoading(true);
    void api
      .getDeployments(chainId)
      .then((deployments) => {
        if (!cancelled) {
          form.setFieldsValue({ domains: toDomains(deployments) });
        }
      })
      .catch(() => {
        if (!cancelled) {
          form.setFieldsValue({ domains: initialDomains ?? [] });
        }
      })
      .finally(() => {
        if (!cancelled) {
          setDomainsLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [form, chainId, initialDomains]);

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
        <DomainsFormItem disabled={domainsLoading} />
      </Form>
    </Modal>
  );
};
