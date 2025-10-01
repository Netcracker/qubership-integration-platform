import React from "react";
import { FloatButton, Table, Tooltip } from "antd";
import { useDeployments } from "../hooks/useDeployments.tsx";
import { useParams } from "react-router";
import { TableProps } from "antd/lib/table";
import { CreateDeploymentRequest, Deployment } from "../api/apiTypes.ts";
import { DeploymentRuntimeStates } from "../components/deployment_runtime_states/DeploymentRuntimeStates.tsx";
import { useSnapshots } from "../hooks/useSnapshots.tsx";
import { formatTimestamp } from "../misc/format-utils.ts";
import { useModalsContext } from "../Modals.tsx";
import { DeploymentCreate } from "../components/modal/DeploymentCreate.tsx";
import { api } from "../api/api.ts";
import { LongActionButton } from "../components/LongActionButton.tsx";
import { useNotificationService } from "../hooks/useNotificationService.tsx";
import { Icon } from "../IconProvider.tsx";

export const Deployments: React.FC = () => {
  const { chainId } = useParams<{ chainId: string }>();
  const { isLoading, deployments, setDeployments, removeDeployment } =
    useDeployments(chainId);
  const { snapshots } = useSnapshots(chainId);
  const { showModal } = useModalsContext();
  const notificationService = useNotificationService();

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
      title: "",
      key: "actions",
      width: 40,
      className: "actions-column",
      render: (_, deployment) => (
        <Tooltip title="Delete deployment" placement="topRight">
          <LongActionButton
            size="small"
            icon={<Icon name="delete" />}
            type="text"
            onSubmit={async () => deleteDeployment(deployment)}
          />
        </Tooltip>
      ),
    },
  ];

  const onDeploymentCreated = (deployment: Deployment) => {
    setDeployments([...(deployments ?? []), deployment]);
  };

  const deleteDeployment = async (deployment: Deployment) => {
    try {
      await api.deleteDeployment(deployment.id);
      removeDeployment(deployment);
    } catch (error) {
      notificationService.requestFailed("Failed to delete deployment", error);
    }
  };

  const createDeployment = async (request: CreateDeploymentRequest) => {
    if (!chainId) return;
    try {
      const deployment = await api.createDeployment(chainId, request);
      onDeploymentCreated(deployment);
    } catch (error) {
      notificationService.requestFailed("Failed to create deployment", error);
    }
  };

  return (
    <>
      <Table
        className="flex-table"
        style={{ height: "100%" }}
        size="small"
        columns={columns}
        dataSource={deployments}
        pagination={false}
        loading={isLoading}
        rowKey="id"
        scroll={{ y: "" }}
      />
      <FloatButton
        icon={<Icon name="plus" />}
        tooltip={{ title: "Create deployment", placement: "left" }}
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
