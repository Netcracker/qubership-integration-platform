import React, { useCallback, useMemo } from "react";
import { Table, Tooltip } from "antd";
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
import { OverridableIcon } from "../icons/IconProvider.tsx";
import { useRegisterChainHeaderActions } from "./ChainHeaderActionsContext.tsx";
import { ChainHeaderToolbar } from "../components/ChainHeaderToolbar.tsx";
import { TablePageLayout } from "../components/TablePageLayout.tsx";
import { Require } from "../permissions/Require.tsx";

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
      render: (_, deployment) => deployment.createdBy.username,
    },
    {
      title: "Created At",
      dataIndex: "createdWhen",
      key: "createdWhen",
      render: (_, deployment) => formatTimestamp(deployment.createdWhen),
    },
    {
      title: "",
      key: "actions",
      width: 40,
      className: "actions-column",
      render: (_, deployment) => (
        <Require permissions={{ deployment: ["delete"] }}>
          <Tooltip title="Delete deployment" placement="topRight">
            <LongActionButton
              size="small"
              icon={<OverridableIcon name="delete" />}
              type="text"
              onSubmit={async () => deleteDeployment(deployment)}
            />
          </Tooltip>
        </Require>
      ),
    },
  ];

  const deleteDeployment = async (deployment: Deployment) => {
    try {
      await api.deleteDeployment(deployment.id);
      removeDeployment(deployment);
    } catch (error) {
      notificationService.requestFailed("Failed to delete deployment", error);
    }
  };

  const createDeployment = useCallback(
    async (request: CreateDeploymentRequest) => {
      if (!chainId) return;
      try {
        const deployment = await api.createDeployment(chainId, request);
        setDeployments((prevDeployments) => [
          ...(prevDeployments ?? []),
          deployment,
        ]);
      } catch (error) {
        notificationService.requestFailed("Failed to create deployment", error);
      }
    },
    [chainId, notificationService, setDeployments],
  );

  const headerActions = useMemo(
    () => (
      <ChainHeaderToolbar
        buttons={[
          {
            require: { deployment: ["create"] },
            tooltipProps: { title: "Create deployment" },
            buttonProps: {
              type: "primary",
              iconName: "plus",
              onClick: () =>
                showModal({
                  component: (
                    <DeploymentCreate
                      chainId={chainId}
                      onSubmit={createDeployment}
                    />
                  ),
                }),
            },
          },
        ]}
      />
    ),
    [showModal, chainId, createDeployment],
  );
  useRegisterChainHeaderActions(headerActions, [chainId]);

  return (
    <TablePageLayout>
      <Table
        className="flex-table"
        size="small"
        columns={columns}
        dataSource={deployments}
        pagination={false}
        loading={isLoading}
        rowKey="id"
        scroll={{ y: "" }}
        style={{ flex: 1, minHeight: 0 }}
      />
    </TablePageLayout>
  );
};
