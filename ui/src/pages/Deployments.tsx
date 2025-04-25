import React from "react";
import { FloatButton, notification, Table } from "antd";
import { useDeployments } from "../hooks/useDeployments.tsx";
import { useParams } from "react-router";
import { DeleteOutlined, PlusOutlined } from "@ant-design/icons";
import { TableProps } from "antd/lib/table";
import { CreateDeploymentRequest, Deployment } from "../api/apiTypes.ts";
import { DeploymentRuntimeStates } from "../components/deployment_runtime_states/DeploymentRuntimeStates.tsx";
import { useSnapshots } from "../hooks/useSnapshots.tsx";
import { formatTimestamp } from "../misc/format-utils.ts";
import { useModalsContext } from "../Modals.tsx";
import { DeploymentCreate } from "../components/modal/DeploymentCreate.tsx";
import { api } from "../api/api.ts";
import { LongActionButton } from "../components/LongActionButton.tsx";

export const Deployments: React.FC = () => {
  const { chainId } = useParams<{ chainId: string }>();
  const { isLoading, deployments, setDeployments } = useDeployments(chainId);
  const { snapshots } = useSnapshots(chainId);
  const { showModal } = useModalsContext();

  const columns: TableProps<Deployment>["columns"] = [
    {
      title: "Snapshot",
      dataIndex: "snapshotId",
      key: "snapshotId",
      render: (_, deployment) => (
        <>
          {snapshots?.find((snapshot) => snapshot.id === deployment.snapshotId)
            ?.name ?? deployment.snapshotId}
        </>
      ),
    },
    { title: "Domain", dataIndex: "domain", key: "domain" },
    {
      title: "Status",
      dataIndex: "runtime",
      key: "runtime",
      render: (_, deployment) => (
        <DeploymentRuntimeStates
          timestamp={deployment.createdWhen}
          service={deployment.serviceName}
          runtimeStates={deployment.runtime ?? { states: {} }}
        />
      ),
    },
    {
      title: "Created By",
      dataIndex: "createdBy",
      key: "createdBy",
      render: (_, deployment) => <>{deployment.createdBy.username}</>,
    },
    {
      title: "Created At",
      dataIndex: "createdWhen",
      key: "createdWhen",
      render: (_, deployment) => <>{formatTimestamp(deployment.createdWhen)}</>,
    },
    {
      title: "Actions",
      key: "actions",
      render: (_, deployment) => (
        <LongActionButton
          icon={<DeleteOutlined />}
          type="text"
          onAction={async () => deleteDeployment(deployment)}
        />
      ),
    },
  ];

  const onDeploymentCreated = async (deployment: Deployment) => {
    setDeployments([...(deployments ?? []), deployment]);
  };

  const deleteDeployment = async (deployment: Deployment) => {
    try {
      await api.deleteDeployment(deployment.id);
      setDeployments(deployments?.filter((d) => d.id !== deployment.id) ?? []);
    } catch (error) {
      notification.error({
        message: "Request failed",
        description: "Failed to delete deployment",
      });
    }
  };

  const createDeployment = async (request: CreateDeploymentRequest) => {
    if (!chainId) return;
    try {
      const deployment = await api.createDeployment(chainId, request);
      await onDeploymentCreated(deployment);
    } catch (error) {
      notification.error({
        message: "Request failed",
        description: "Failed to create deployment",
      });
    }
  };

  return (
    <>
      <Table
        columns={columns}
        dataSource={deployments}
        pagination={false}
        loading={isLoading}
        rowKey="name"
        scroll={{ y: "calc(100vh - 200px)" }}
      />
      <FloatButton
        icon={<PlusOutlined />}
        onClick={() =>
          showModal({
            component: (
              <DeploymentCreate chainId={chainId} onSubmit={createDeployment} />
            ),
          })
        }
      />
    </>
  );
};
