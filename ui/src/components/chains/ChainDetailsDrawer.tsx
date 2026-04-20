import React, { useEffect, useMemo, useState } from "react";
import {
  Descriptions,
  Divider,
  Drawer,
  Empty,
  Flex,
  Spin,
  Table,
  Tag,
  Typography,
} from "antd";
import type { TableProps } from "antd/lib/table";
import {
  ChainItem,
  ChainLoggingSettings,
  Deployment,
  DeploymentStatus,
} from "../../api/apiTypes.ts";
import { api } from "../../api/api.ts";
import { useNotificationService } from "../../hooks/useNotificationService.tsx";
import { capitalize, formatTimestamp } from "../../misc/format-utils.ts";
import { pickLoggingProperties } from "../../misc/logging-utils.ts";
import { DeploymentsCumulativeState } from "../deployment_runtime_states/DeploymentsCumulativeState.tsx";
import { DeploymentStatusTag } from "../deployment_runtime_states/DeploymentStatusTag.tsx";
import { EntityLabels } from "../labels/EntityLabels.tsx";
import { LoggingSettingsSourceTag } from "../logging/LoggingSettingsSourceTag.tsx";

type ChainDetailsDrawerProps = {
  chain: ChainItem | null;
  open: boolean;
  onClose: () => void;
};

type EngineInfo = {
  host: string;
  status?: DeploymentStatus;
};

type DeploymentRow = {
  key: string;
  domain: string;
  engines: EngineInfo[];
};

const EMPTY = <Typography.Text type="secondary">—</Typography.Text>;

const DEPLOYMENT_COLUMNS: TableProps<DeploymentRow>["columns"] = [
  {
    title: "Domain",
    dataIndex: "domain",
    key: "domain",
    render: (value: string) => value || EMPTY,
  },
  {
    title: "Active Engines",
    dataIndex: "engines",
    key: "engines",
    render: (engines: EngineInfo[]) =>
      engines.length === 0 ? (
        EMPTY
      ) : (
        <Flex wrap="wrap" gap={4}>
          {engines.map((engine) => (
            <DeploymentStatusTag
              key={engine.host}
              status={engine.status ?? "DRAFT"}
              text={engine.host}
            />
          ))}
        </Flex>
      ),
  },
];

export const ChainDetailsDrawer: React.FC<ChainDetailsDrawerProps> = ({
  chain,
  open,
  onClose,
}) => {
  const notificationService = useNotificationService();
  const [deployments, setDeployments] = useState<Deployment[]>([]);
  const [loggingSettings, setLoggingSettings] =
    useState<ChainLoggingSettings | null>(null);
  const [isLoading, setIsLoading] = useState(false);

  useEffect(() => {
    if (!open || !chain) {
      return;
    }
    let cancelled = false;
    setIsLoading(true);
    void Promise.all([
      api.getDeployments(chain.id).catch((error) => {
        notificationService.requestFailed("Failed to load deployments", error);
        return [] as Deployment[];
      }),
      api.getLoggingSettings(chain.id).catch((error) => {
        notificationService.requestFailed(
          "Failed to load logging settings",
          error,
        );
        return null;
      }),
    ])
      .then(([deploymentsResult, loggingResult]) => {
        if (cancelled) {
          return;
        }
        setDeployments(deploymentsResult);
        setLoggingSettings(loggingResult);
      })
      .finally(() => {
        if (!cancelled) {
          setIsLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [open, chain, notificationService]);

  const deploymentRows: DeploymentRow[] = useMemo(
    () =>
      deployments.map((deployment) => ({
        key: deployment.id,
        domain: deployment.domain,
        engines: Object.entries(deployment.runtime?.states ?? {}).map(
          ([host, state]) => ({ host, status: state?.status }),
        ),
      })),
    [deployments],
  );

  const loggingProperties = pickLoggingProperties(loggingSettings);
  const logPayload = loggingProperties?.logPayload ?? [];

  return (
    <Drawer
      title="Chain Details"
      placement="right"
      width={350}
      open={open}
      onClose={onClose}
      destroyOnHidden
    >
      {!chain ? null : (
        <Spin spinning={isLoading}>
          <Descriptions column={1} size="small" layout="vertical" colon={false}>
            <Descriptions.Item label="Id">
              <Typography.Text copyable style={{ wordBreak: "break-all" }}>
                {chain.id}
              </Typography.Text>
            </Descriptions.Item>
            <Descriptions.Item label="Name">
              {chain.name || EMPTY}
            </Descriptions.Item>
            <Descriptions.Item label="Description">
              {chain.description ? chain.description : EMPTY}
            </Descriptions.Item>
            <Descriptions.Item label="Labels">
              {chain.labels && chain.labels.length > 0 ? (
                <EntityLabels labels={chain.labels} />
              ) : (
                EMPTY
              )}
            </Descriptions.Item>
          </Descriptions>
          <Divider style={{ margin: "12px 0" }} />
          <Descriptions column={1} size="small" layout="vertical" colon={false}>
            <Descriptions.Item label="Status">
              <DeploymentsCumulativeState
                chainId={chain.id}
                isNotificationEnabled={false}
                deployments={deployments}
              />
            </Descriptions.Item>
            <Descriptions.Item label="Deployments">
              {deploymentRows.length === 0 ? (
                <Empty
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description="Not deployed"
                  style={{ margin: "4px 0" }}
                />
              ) : (
                <Table<DeploymentRow>
                  size="small"
                  columns={DEPLOYMENT_COLUMNS}
                  dataSource={deploymentRows}
                  pagination={false}
                  rowKey="key"
                />
              )}
            </Descriptions.Item>
            <Descriptions.Item label="Logging settings source">
              {loggingSettings ? (
                <LoggingSettingsSourceTag
                  isCustom={!!loggingSettings.custom}
                  isConsulDefault={!!loggingSettings.consulDefault}
                />
              ) : (
                EMPTY
              )}
            </Descriptions.Item>
            <Descriptions.Item label="Sessions logging level">
              {loggingProperties
                ? capitalize(loggingProperties.sessionsLoggingLevel)
                : EMPTY}
            </Descriptions.Item>
            <Descriptions.Item label="Log logging level">
              {loggingProperties
                ? capitalize(loggingProperties.logLoggingLevel)
                : EMPTY}
            </Descriptions.Item>
            <Descriptions.Item label="Log payload">
              {logPayload.length === 0 ? (
                EMPTY
              ) : (
                <Flex wrap="wrap" gap={4}>
                  {logPayload.map((item) => (
                    <Tag key={item} style={{ fontSize: 13, marginInlineEnd: 0 }}>
                      {capitalize(item)}
                    </Tag>
                  ))}
                </Flex>
              )}
            </Descriptions.Item>
            <Descriptions.Item label="DPT events enabled">
              {loggingProperties
                ? String(loggingProperties.dptEventsEnabled)
                : EMPTY}
            </Descriptions.Item>
            <Descriptions.Item label="Masking enabled">
              {loggingProperties
                ? String(loggingProperties.maskingEnabled)
                : EMPTY}
            </Descriptions.Item>
          </Descriptions>
          <Divider style={{ margin: "12px 0" }} />
          <Descriptions column={1} size="small" layout="vertical" colon={false}>
            <Descriptions.Item label="Created">
              {chain.createdWhen
                ? `${formatTimestamp(chain.createdWhen)}${
                    chain.createdBy?.username
                      ? ` by ${chain.createdBy.username}`
                      : ""
                  }`
                : EMPTY}
            </Descriptions.Item>
            <Descriptions.Item label="Modified">
              {chain.modifiedWhen
                ? `${formatTimestamp(chain.modifiedWhen)}${
                    chain.modifiedBy?.username
                      ? ` by ${chain.modifiedBy.username}`
                      : ""
                  }`
                : EMPTY}
            </Descriptions.Item>
          </Descriptions>
        </Spin>
      )}
    </Drawer>
  );
};
