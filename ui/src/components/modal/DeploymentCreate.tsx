import {
  Button,
  Form,
  Modal,
  Select,
  SelectProps,
} from "antd";
import React, { useState } from "react";
import { useModalContext } from "../../ModalContextProvider.tsx";
import { useDomains } from "../../hooks/useDomains.tsx";
import { useSnapshots } from "../../hooks/useSnapshots.tsx";
import { CreateDeploymentRequest } from "../../api/apiTypes.ts";

type Props = {
  chainId?: string;
  onSubmit?: (request: CreateDeploymentRequest) => void;
};

export const DeploymentCreate: React.FC<Props> = ({ chainId, onSubmit }) => {
  const [form] = Form.useForm();
  const [confirmLoading, setConfirmLoading] = useState(false);
  const { closeContainingModal } = useModalContext();
  const domainsState = useDomains();
  const snapshotsState = useSnapshots(chainId);

  const domainOptions: SelectProps["options"] = domainsState.domains
    ?.sort((d1, d2) => d1.name.localeCompare(d2.name))
    .map((domain) => ({
      label: domain.name,
      value: domain.id,
    }));

  const snapshotOptions: SelectProps["options"] =
    snapshotsState.snapshots
      ?.sort((s1, s2) => s2.modifiedWhen - s1.modifiedWhen)
      .map((snapshot) => ({
        label: snapshot.name,
        value: snapshot.id,
      })) ?? [];

  const handleOk = async () => {
    if (!chainId) {
      return;
    }
    setConfirmLoading(true);
    const request: CreateDeploymentRequest = {
      domain: form.getFieldValue("domain"),
      snapshotId: form.getFieldValue("snapshot"),
      suspended: false,
    };
    try {
      onSubmit?.(request);
    } finally {
      setConfirmLoading(false);
      closeContainingModal();
    }
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
          disabled={domainsState.isLoading || snapshotsState.isLoading}
        >
          Deploy
        </Button>,
      ]}
    >
      <Form
        id="deploymentCreateForm"
        form={form}
        onFinish={handleOk}
        layout="horizontal"
        labelCol={{ span: 4 }}
        style={{ maxWidth: 600 }}
        disabled={domainsState.isLoading || snapshotsState.isLoading}
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
        <Form.Item
          label="Snapshot"
          name="snapshot"
          rules={[{ required: true, message: "Please specify a snapshot" }]}
        >
          <Select
            options={snapshotOptions}
            loading={snapshotsState.isLoading}
          />
        </Form.Item>
      </Form>
    </Modal>
  );
};
