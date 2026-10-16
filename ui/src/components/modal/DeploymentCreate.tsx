import { Button, Form, Modal, Select, SelectProps } from "antd";
import React, { useState } from "react";
import { useModalContext } from "../../ModalContextProvider.tsx";
import { useSnapshots } from "../../hooks/useSnapshots.tsx";
import { useInitialDomains } from "../../hooks/useInitialDomains.ts";
import { handleModalSubmit } from "../../misc/modal-submit.ts";
import {
  Domain,
  DomainsFormItem,
  isDomainSelectionInvalid,
} from "../SelectDomains.tsx";

export type CreateDeploymentOptions = {
  domains: Domain[];
  snapshotId: string;
};

type CreateDeploymentProps = {
  chainId?: string;
  initialDomains?: Domain[];
  onSubmit?: (request: CreateDeploymentOptions) => void | Promise<void>;
};

export const DeploymentCreate: React.FC<CreateDeploymentProps> = ({
  chainId,
  initialDomains,
  onSubmit,
}) => {
  const [form] = Form.useForm<{ domains: Domain[]; snapshot: string }>();
  const [confirmLoading, setConfirmLoading] = useState(false);
  const { closeContainingModal } = useModalContext();
  const { isLoading: snapshotsLoading, snapshots } = useSnapshots(chainId);
  const domains = Form.useWatch("domains", form);
  const isDeployDisabled =
    snapshotsLoading || isDomainSelectionInvalid(domains);

  useInitialDomains(form, initialDomains);

  const snapshotOptions: SelectProps["options"] =
    snapshots
      ?.sort((s1, s2) => (s2.modifiedWhen ?? 0) - (s1.modifiedWhen ?? 0))
      .map((snapshot) => ({
        label: snapshot.name,
        value: snapshot.id,
      })) ?? [];

  const handleOk = (domains: Domain[], snapshotId: string) => {
    if (!chainId) {
      return;
    }
    const options: CreateDeploymentOptions = { domains, snapshotId };
    handleModalSubmit(
      () => onSubmit?.(options),
      setConfirmLoading,
      closeContainingModal,
    );
  };

  const handleCancel = () => {
    closeContainingModal();
  };

  return (
    <Modal
      title="Deployment"
      open={true}
      onCancel={handleCancel}
      footer={[
        <Button key="cancel" onClick={handleCancel}>
          Cancel
        </Button>,
        <Button
          key="submit"
          type="primary"
          form="deploymentCreateForm"
          htmlType={"submit"}
          loading={confirmLoading}
          disabled={isDeployDisabled}
        >
          Deploy
        </Button>,
      ]}
    >
      <Form<{ domains: Domain[]; snapshot: string }>
        id="deploymentCreateForm"
        form={form}
        initialValues={{
          domains: initialDomains ?? [],
        }}
        onFinish={(values) => handleOk(values.domains, values.snapshot)}
        layout="horizontal"
        labelCol={{ span: 4 }}
        style={{ maxWidth: 600 }}
        disabled={snapshotsLoading}
      >
        <DomainsFormItem />
        <Form.Item
          label="Snapshot"
          name="snapshot"
          rules={[{ required: true, message: "Please specify a snapshot" }]}
        >
          <Select options={snapshotOptions} loading={snapshotsLoading} />
        </Form.Item>
      </Form>
    </Modal>
  );
};
