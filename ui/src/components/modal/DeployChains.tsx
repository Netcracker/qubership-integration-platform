import React, { useState } from "react";
import {
  BulkDeploymentResult,
  BulkDeploymentSnapshotAction,
  BulkDeploymentStatus,
  DomainType,
} from "../../api/apiTypes.ts";
import { useModalContext } from "../../ModalContextProvider.tsx";
import { Modal, Button, Flex, Form, Select, Table, Typography, Space, Tag } from "antd";
import { Domain, SelectDomains } from "../SelectDomains.tsx";
import { StatusTag } from "../labels/StatusTag.tsx";
import type { TableProps } from "antd";

export type DeployRequest = {
  domains: Domain[];
  snapshotAction: BulkDeploymentSnapshotAction;
};

type DeployChainsProps = {
  chainCount: number;
  onSubmit?: (options: DeployRequest) => Promise<BulkDeploymentResult[]>;
};

const resultColumns: TableProps<BulkDeploymentResult>["columns"] = [
  {
    title: "Chain Id",
    dataIndex: "chainId",
    ellipsis: true,
  },
  {
    title: "Chain Name",
    dataIndex: "chainName",
    render: (_, record) =>
      record.status === BulkDeploymentStatus.CREATED ? (
        <a href={`/chains/${record.chainId}`} target="_blank" rel="noreferrer">
          {record.chainName}
        </a>
      ) : (
        record.chainName
      ),
  },
  {
    title: "Domain",
    render: (_, record) => record.domain?.type === DomainType.MICRO ? (
      <Space size={"small"}>
        {record.domain?.name}
        <Tag>micro</Tag>
      </Space>
    ) : (
      record.domain?.name
    ),
  },
  {
    title: "Status",
    dataIndex: "status",
    render: (_, record) => <StatusTag status={record.status} />,
  },
  {
    title: "Message",
    dataIndex: "errorMessage",
  },
];

export const DeployChains: React.FC<DeployChainsProps> = ({
  chainCount,
  onSubmit,
}) => {
  const { closeContainingModal } = useModalContext();
  const [confirmLoading, setConfirmLoading] = useState(false);
  const [step, setStep] = useState<"form" | "results">("form");
  const [results, setResults] = useState<(BulkDeploymentResult & { key: React.Key })[]>([]);

  const onFinish = async (values: DeployRequest) => {
    setConfirmLoading(true);
    try {
      const deployResults = await onSubmit?.(values);
      setResults((deployResults ?? []).map((r, i) => ({...r, key: i})));
      setStep("results");
    } catch {
      // Stay on form step — error is handled by the caller
    } finally {
      setConfirmLoading(false);
    }
  };

  return (
    <Modal
      title="Deploy chains"
      open={true}
      onCancel={closeContainingModal}
      width={step === "results" ? 800 : undefined}
      footer={
        step === "form" ? (
          <Flex align="center" gap={8}>
            <Typography.Text type="secondary" style={{ marginRight: "auto" }}>
              {chainCount} chain(s) to deploy
            </Typography.Text>
            <Button onClick={closeContainingModal}>Cancel</Button>
            <Button
              type="primary"
              form="deployOptionsForm"
              htmlType="submit"
              loading={confirmLoading}
            >
              Deploy
            </Button>
          </Flex>
        ) : (
          <Button type="primary" onClick={closeContainingModal}>
            Close
          </Button>
        )
      }
    >
      {step === "form" ? (
        <Form<DeployRequest>
          layout="vertical"
          id="deployOptionsForm"
          initialValues={{
            domains: [{ name: "default", type: DomainType.NATIVE }],
            snapshotAction: BulkDeploymentSnapshotAction.CREATE_NEW,
          }}
          onFinish={(values) => void onFinish(values)}
        >
          <Form.Item
            name="domains"
            label="Engine domains"
            rules={[{ required: true }]}
          >
            <SelectDomains />
        </Form.Item>
        <Form.Item name="snapshotAction" label="Snapshot action">
            <Select
              options={[
                {
                  value: BulkDeploymentSnapshotAction.CREATE_NEW,
                  label: "Create new",
                },
                {
                  value: BulkDeploymentSnapshotAction.LAST_CREATED,
                  label: "Reuse latest, otherwise create new",
                },
              ]}
            />
          </Form.Item>
        </Form>
      ) : (
        <Table<BulkDeploymentResult>
          columns={resultColumns}
          dataSource={results}
          rowKey="key"
          size="small"
          pagination={false}
        />
      )}
    </Modal>
  );
};
