import React, { useCallback, useMemo, useState } from "react";
import {
  attachResizeToColumns,
  sumScrollXForColumns,
  useTableColumnResize,
} from "../components/table/useTableColumnResize.tsx";
import { Flex, Table, Tooltip } from "antd";
import { useDeployments } from "../hooks/useDeployments.tsx";
import { useParams } from "react-router";
import { TableProps } from "antd/lib/table";
import {
  CreateDeploymentRequest,
  Deployment,
  Snapshot,
} from "../api/apiTypes.ts";
import { DeploymentRuntimeStates } from "../components/deployment_runtime_states/DeploymentRuntimeStates.tsx";
import { useSnapshots } from "../hooks/useSnapshots.tsx";
import { formatTimestamp } from "../misc/format-utils.ts";
import { useModalsContext } from "../Modals.tsx";
import { DeploymentCreate } from "../components/modal/DeploymentCreate.tsx";
import { api } from "../api/api.ts";
import { LongActionButton } from "../components/LongActionButton.tsx";
import { useNotificationService } from "../hooks/useNotificationService.tsx";
import { OverridableIcon } from "../icons/IconProvider.tsx";
import { ProtectedButton } from "../permissions/ProtectedButton.tsx";
import { TablePageLayout } from "../components/TablePageLayout.tsx";
import { Require } from "../permissions/Require.tsx";
import { useColumnSettingsBasedOnColumnsType } from "../components/table/useColumnSettingsButton.tsx";
import { TableToolbar } from "../components/table/TableToolbar.tsx";
import {
  createActionsColumnBase,
  disableResizeBeforeActions,
} from "../components/table/actionsColumn.ts";
import commonStyles from "../components/admin_tools/CommonStyle.module.css";
import { CompactSearch } from "../components/table/CompactSearch.tsx";
import { matchesByFields } from "../components/table/tableSearch.ts";

function deploymentMatchesSearch(
  deployment: Deployment,
  term: string,
  snapshots: Snapshot[] | undefined,
): boolean {
  const snapshotName =
    snapshots?.find((s) => s.id === deployment.snapshotId)?.name ??
    deployment.snapshotId;
  return matchesByFields(term, [
    deployment.name,
    deployment.domain,
    deployment.serviceName,
    snapshotName,
    deployment.snapshotId,
    deployment.createdBy.username,
    formatTimestamp(deployment.createdWhen),
  ]);
}

export const Deployments: React.FC = () => {
  const { chainId } = useParams<{ chainId: string }>();
  const { isLoading, deployments, setDeployments, removeDeployment } =
    useDeployments(chainId);
  const { snapshots } = useSnapshots(chainId);
  const { showModal } = useModalsContext();
  const notificationService = useNotificationService();
  const [searchTerm, setSearchTerm] = useState("");

  const filteredDeployments = useMemo(
    () =>
      (deployments ?? []).filter((row) =>
        deploymentMatchesSearch(row, searchTerm, snapshots),
      ),
    [deployments, searchTerm, snapshots],
  );

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
      ...createActionsColumnBase<Deployment>(),
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

  const { orderedColumns, columnSettingsButton } =
    useColumnSettingsBasedOnColumnsType<Deployment>(
      "deploymentsTable",
      columns,
    );

  const deploymentsColumnResize = useTableColumnResize({
    snapshotId: 200,
    domain: 140,
    runtime: 260,
    createdBy: 120,
    createdWhen: 168,
  });

  const columnsWithResize = useMemo(() => {
    const resized = attachResizeToColumns(
      orderedColumns,
      deploymentsColumnResize.columnWidths,
      deploymentsColumnResize.createResizeHandlers,
      { minWidth: 80 },
    );
    return disableResizeBeforeActions(resized);
  }, [
    orderedColumns,
    deploymentsColumnResize.columnWidths,
    deploymentsColumnResize.createResizeHandlers,
  ]);

  const scrollX = useMemo(
    () =>
      sumScrollXForColumns(
        columnsWithResize,
        deploymentsColumnResize.columnWidths,
      ),
    [columnsWithResize, deploymentsColumnResize.columnWidths],
  );

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

  const onCreateClick = useCallback(() => {
    showModal({
      component: (
        <DeploymentCreate chainId={chainId} onSubmit={createDeployment} />
      ),
    });
  }, [showModal, chainId, createDeployment]);

  return (
    <TablePageLayout>
      <TableToolbar
        leading={
          <CompactSearch
            value={searchTerm}
            onChange={setSearchTerm}
            placeholder="Search deployments..."
            allowClear
            className={commonStyles.searchField as string}
          />
        }
        trailing={
          <Flex align="center" gap={8} wrap="wrap">
            {columnSettingsButton}
            <ProtectedButton
              require={{ deployment: ["create"] }}
              tooltipProps={{ title: "Create deployment" }}
              buttonProps={{
                type: "primary",
                iconName: "plus",
                onClick: onCreateClick,
              }}
            />
          </Flex>
        }
      />
      <Table
        className="flex-table"
        size="small"
        columns={columnsWithResize}
        dataSource={filteredDeployments}
        pagination={false}
        loading={isLoading}
        rowKey="id"
        scroll={
          filteredDeployments.length > 0
            ? { x: scrollX, y: "" }
            : { x: scrollX }
        }
        components={deploymentsColumnResize.resizableHeaderComponents}
        style={{ flex: 1, minHeight: 0 }}
      />
    </TablePageLayout>
  );
};
