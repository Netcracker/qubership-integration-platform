import { Button, Form, Modal, Select, SelectProps } from "antd";
import React, { useState } from "react";
import { useModalContext } from "../../ModalContextProvider.tsx";
import { useDomains } from "../../hooks/useDomains.tsx";
import { useSnapshots } from "../../hooks/useSnapshots.tsx";
import { CreateDeploymentRequest } from "../../api/apiTypes.ts";

type Props = {
  chainId?: string;
  onSubmit?: (request: CreateDeploymentRequest) => void | Promise<void>;
};

export const DeploymentCreate: React.FC<Props> = ({ chainId, onSubmit }) => {
  const [form] = Form.useForm();
  const [confirmLoading, setConfirmLoading] = useState(false);
  const { closeContainingModal } = useModalContext();
  const { isLoading: domainsLoading, domains } = useDomains();
  const { isLoading: snapshotsLoading, snapshots } = useSnapshots(chainId);

  const domainOptions: SelectProps["options"] = domains
    ?.sort((d1, d2) => d1.name.localeCompare(d2.name))
    .map((domain) => ({
      label: domain.name,
      value: domain.id,
    }));

  const snapshotOptions: SelectProps["options"] =
    snapshots
      ?.sort((s1, s2) => s2.modifiedWhen - s1.modifiedWhen)
      .map((snapshot) => ({
        label: snapshot.name,
        value: snapshot.id,
      })) ?? [];

  const handleOk = (domain: string, snapshotId: string) => {
    if (!chainId) {
      return;
    }
    setConfirmLoading(true);
    const request: CreateDeploymentRequest = {
      domain,
      snapshotId,
      suspended: false,
    };
    try {
      const result = onSubmit?.(request);
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
          disabled={domainsLoading || snapshotsLoading}
        >
          Deploy
        </Button>,
      ]}
    >
      <Form<{ domain: string; snapshot: string }>
        id="deploymentCreateForm"
        form={form}
        onFinish={(values) => handleOk(values.domain, values.snapshot)}
        layout="horizontal"
        labelCol={{ span: 4 }}
        style={{ maxWidth: 600 }}
        disabled={domainsLoading || snapshotsLoading}
      >
        <Form.Item
          label="Domain"
          name="domain"
          rules={[{ required: true, message: "Please specify a domain" }]}
        >
          <Select options={domainOptions} loading={domainsLoading} />
        </Form.Item>
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
