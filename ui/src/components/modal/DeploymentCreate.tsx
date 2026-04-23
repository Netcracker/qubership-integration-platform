import { Button, Form, Modal, Select, SelectProps } from "antd";
import React, { useState } from "react";
import { useModalContext } from "../../ModalContextProvider.tsx";
import { useSnapshots } from "../../hooks/useSnapshots.tsx";
import { DomainType } from "../../api/apiTypes.ts";
import { Domain, SelectDomains } from "../SelectDomains.tsx";

export type CreateDeploymentOptions = {
  domains: Domain[];
  snapshotId: string;
};

type CreateDeploymentProps = {
  chainId?: string;
  onSubmit?: (request: CreateDeploymentOptions) => void | Promise<void>;
};

export const DeploymentCreate: React.FC<CreateDeploymentProps> = ({
  chainId,
  onSubmit,
}) => {
  const [form] = Form.useForm();
  const [confirmLoading, setConfirmLoading] = useState(false);
  const { closeContainingModal } = useModalContext();
  const { isLoading: snapshotsLoading, snapshots } = useSnapshots(chainId);

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
    setConfirmLoading(true);
    const options: CreateDeploymentOptions = { domains, snapshotId };
    try {
      const result = onSubmit?.(options);
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
          disabled={snapshotsLoading}
        >
          Deploy
        </Button>,
      ]}
    >
      <Form<{ domains: Domain[]; snapshot: string }>
        id="deploymentCreateForm"
        form={form}
        initialValues={{
          domains: [{ name: "default", type: DomainType.NATIVE }],
        }}
        onFinish={(values) => handleOk(values.domains, values.snapshot)}
        layout="horizontal"
        labelCol={{ span: 4 }}
        style={{ maxWidth: 600 }}
        disabled={snapshotsLoading}
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
