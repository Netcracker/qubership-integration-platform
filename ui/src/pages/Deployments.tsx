import { Table } from "antd";
import React, { useCallback, useMemo, useState } from "react";
import { useTableConfiguration } from "../components/table/useTableConfiguration.tsx";
import { tableEmpty } from "../components/table/tableEmpty.tsx";
import { tableScroll } from "../components/table/tableScroll.ts";
import { Flex, Space, Tag, Tooltip } from "antd";
import { DeploymentStateTag } from "../components/deployment_runtime_states/DeploymentStateTag.tsx";
import { useDeployments } from "../hooks/useDeployments.tsx";
import { useParams } from "react-router";
import { TableProps } from "antd/lib/table";
import {
  CreateDeploymentRequest,
  Deployment,
  DeployMode,
  DomainType,
  Snapshot,
} from "../api/apiTypes.ts";
import { useSnapshots } from "../hooks/useSnapshots.tsx";
import { formatOptional, formatTimestamp } from "../misc/format-utils.ts";
import { useModalsContext } from "../Modals.tsx";
import {
  CreateDeploymentOptions,
  DeploymentCreate,
} from "../components/modal/DeploymentCreate.tsx";
import { toDomains } from "../misc/domain-utils.ts";
import { api } from "../api/api.ts";
import { LongActionButton } from "../components/LongActionButton.tsx";
import { useNotificationService } from "../hooks/useNotificationService.tsx";
import { OverridableIcon } from "../icons/IconProvider.tsx";
import { TablePageLayout } from "../components/TablePageLayout.tsx";
import { Require } from "../permissions/Require.tsx";
import { useColumnSettingsBasedOnColumnsType } from "../components/table/useColumnSettingsButton.tsx";
import { createActionsColumnBase } from "../components/table/actionsColumn.ts";
import { matchesByFields } from "../components/table/tableSearch.ts";
import { ProtectedButton } from "../permissions/ProtectedButton.tsx";
import { TableToolbar } from "../components/table/TableToolbar.tsx";
import { ChainHeaderActions } from "./ChainHeaderActionsContext.tsx";
import { DeleteDeploymentWarningModal } from "../components/modal/DeleteDeploymentWarningModal.tsx";

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
    deployment.createdBy?.username ?? "",
    formatTimestamp(deployment.createdWhen),
  ]);
}

export const Deployments: React.FC = () => {
  const { chainId } = useParams<{ chainId: string }>();
  const { isLoading, deployments, setDeployments, removeDeployment, refresh } =
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

  const deleteDeploymentFromChain = useCallback(
    async (deployment: Deployment) => {
      if (!chainId) return;
      try {
        await api.deleteDeployment(chainId, deployment.id);
        removeDeployment(deployment);
      } catch (error) {
        notificationService.requestFailed("Failed to delete deployment", error);
      }
    },
    [chainId, removeDeployment, notificationService],
  );

  const columns: TableProps<Deployment>["columns"] = useMemo(
    () => [
      {
        title: "Snapshot",
        dataIndex: "snapshotId",
        key: "snapshotId",
        render: (_, deployment) => (
          <>
            {snapshots?.find(
              (snapshot) => snapshot.id === deployment.snapshotId,
            )?.name ?? deployment.snapshotId}
          </>
        ),
      },
      {
        title: "Domain",
        dataIndex: "domain",
        key: "domain",
        render: (_, deployment) =>
          deployment.domainType === DomainType.MICRO ? (
            <Space size={"small"}>
              {deployment.domain}
              <Tag>micro</Tag>
            </Space>
          ) : (
            deployment.domain
          ),
      },
      {
        title: "Status",
        dataIndex: "runtime",
        key: "runtime",
        render: (_, deployment) => (
          <Flex gap="4px 4px" wrap>
            {Object.entries(deployment.runtime?.states ?? {}).map(
              ([name, runtimeState]) => (
                <DeploymentStateTag
                  key={name}
                  name={name}
                  service={deployment.serviceName}
                  timestamp={deployment.createdWhen}
                  runtimeState={runtimeState}
                />
              ),
            )}
          </Flex>
        ),
      },
      {
        title: "Created By",
        dataIndex: "createdBy",
        key: "createdBy",
        render: (_, deployment) => (
          <>{formatOptional(deployment.createdBy?.username)}</>
        ),
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
                onSubmit={() =>
                  showModal({
                    component: (
                      <DeleteDeploymentWarningModal
                        onDelete={() =>
                          void deleteDeploymentFromChain(deployment)
                        }
                      />
                    ),
                  })
                }
              />
            </Tooltip>
          </Require>
        ),
      },
    ],
    [snapshots, deleteDeploymentFromChain, showModal],
  );

  const { orderedColumns, columnSettingsButton } =
    useColumnSettingsBasedOnColumnsType<Deployment>(
      "deploymentsTable",
      columns,
    );

  const {
    columnsWithResize,
    scrollX,
    components,
    handleTableChange: handleConfiguredTableChange,
  } = useTableConfiguration(
    orderedColumns,
    {
      snapshotId: 200,
      domain: 140,
      runtime: 260,
      createdBy: 120,
      createdWhen: 168,
    },
    {},
    "deploymentsTable",
  );

  const onDeploymentCreated = useCallback(
    (deployment: Deployment) => {
      setDeployments((prev) => {
        const existing = prev ?? [];
        return existing.some((d) => d.id === deployment.id)
          ? existing.map((d) => (d.id === deployment.id ? deployment : d))
          : [...existing, deployment];
      });
    },
    [setDeployments],
  );

  const createDeployment = useCallback(
    async (options: CreateDeploymentOptions) => {
      if (!chainId) return;
      try {
        await Promise.all(
          options.domains.map(async (domain) => {
            const request: CreateDeploymentRequest = {
              domain: domain.name,
              snapshotId: options.snapshotId,
              mode: DeployMode.APPEND,
              suspended: false,
            };
            const deployment = await api.createDeployment(chainId, request);
            onDeploymentCreated(deployment);
          }),
        );
      } catch (error) {
        notificationService.requestFailed("Failed to create deployment", error);
      }
    },
    [chainId, notificationService, onDeploymentCreated],
  );

  const initialDomains = useMemo(() => toDomains(deployments), [deployments]);

  const onCreateClick = useCallback(() => {
    showModal({
      component: (
        <DeploymentCreate
          chainId={chainId}
          initialDomains={initialDomains}
          onSubmit={createDeployment}
        />
      ),
    });
  }, [showModal, chainId, createDeployment, initialDomains]);

  const chainTabToolbar = useMemo(
    () => (
      <TableToolbar
        refresh={{ onRefresh: refresh, loading: isLoading }}
        variant="chain-tab"
        search={{
          value: searchTerm,
          onChange: setSearchTerm,
          placeholder: "Search deployments...",
          style: { minWidth: 160, maxWidth: 360, flex: "0 1 auto" },
        }}
        columnSettingsButton={columnSettingsButton}
        actions={
          <ProtectedButton
            require={{ deployment: ["create"] }}
            tooltipProps={{ title: "Create deployment" }}
            buttonProps={{
              type: "primary",
              iconName: "plus",
              onClick: onCreateClick,
              "data-testid": "deployments-create",
            }}
          />
        }
      />
    ),
    [searchTerm, columnSettingsButton, onCreateClick, refresh, isLoading],
  );

  return (
    <TablePageLayout>
      <ChainHeaderActions>{chainTabToolbar}</ChainHeaderActions>
      <Table
        className="flex-table"
        size="small"
        columns={columnsWithResize}
        dataSource={filteredDeployments}
        pagination={false}
        loading={isLoading}
        rowKey="id"
        locale={{ emptyText: tableEmpty("No deployments") }}
        scroll={tableScroll(scrollX, filteredDeployments.length)}
        components={components}
        onChange={handleConfiguredTableChange}
        style={{ flex: 1, minHeight: 0 }}
      />
    </TablePageLayout>
  );
};
