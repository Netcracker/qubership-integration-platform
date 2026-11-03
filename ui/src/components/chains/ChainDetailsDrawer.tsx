import React, { ReactNode, useEffect, useMemo, useState } from "react";
import {
  Col,
  Drawer,
  Flex,
  Listy,
  Row,
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
  User,
} from "../../api/apiTypes.ts";
import { api } from "../../api/api.ts";
import { useNotificationService } from "../../hooks/useNotificationService.tsx";
import {
  capitalize,
  formatOptional,
  formatTimestamp,
  PLACEHOLDER,
} from "../../misc/format-utils.ts";
import { pickLoggingProperties } from "../../misc/logging-utils.ts";
import { DeploymentsCumulativeState } from "../deployment_runtime_states/DeploymentsCumulativeState.tsx";
import { DeploymentStatusTag } from "../deployment_runtime_states/DeploymentStatusTag.tsx";
import { EntityLabels } from "../labels/EntityLabels.tsx";
import { LoggingSettingsSourceTag } from "../logging/LoggingSettingsSourceTag.tsx";

export type ChainDetailsDrawerProps = {
  chain: ChainItem | null | undefined;
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

type SidebarItem = {
  group: React.Key;
  key?: ReactNode;
  value: ReactNode;
};

const EMPTY = <Typography.Text type="secondary">{PLACEHOLDER}</Typography.Text>;

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

export const AuthorDetails: React.FC<{ timestamp?: number; user?: User }> = ({
  timestamp,
  user,
}) => {
  return timestamp ? (
    <Flex vertical>
      <Typography.Text>{formatTimestamp(timestamp)}</Typography.Text>
      <Typography.Text type={"secondary"}>{formatOptional(user?.username)}</Typography.Text>
    </Flex>
  ) : (
    EMPTY
  );
};

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
  const logPayload = useMemo(
    () => loggingProperties?.logPayload ?? [],
    [loggingProperties?.logPayload],
  );

  const items = useMemo(
    () => [
      {
        group: "general info",
        key: "ID",
        value: chain ? (
          <Typography.Text copyable style={{ wordBreak: "break-all" }}>
            {chain.id}
          </Typography.Text>
        ) : (
          EMPTY
        ),
      },
      {
        group: "general info",
        key: "Name",
        value: formatOptional(chain?.name),
      },
      {
        group: "general info",
        key: "Description",
        value: formatOptional(chain?.description),
      },
      {
        group: "general info",
        key: "Labels",
        value:
          chain?.labels && chain?.labels.length > 0 ? (
            <EntityLabels labels={chain.labels} />
          ) : (
            EMPTY
          ),
      },
      {
        group: "status & deployment",
        key: "Status",
        value: chain ? (
          <DeploymentsCumulativeState
            chainId={chain.id}
            isNotificationEnabled={false}
            deployments={deployments}
          />
        ) : (
          EMPTY
        ),
      },
      {
        group: "status & deployment",
        key: "Deployments",
        value:
          deploymentRows.length === 0 ? (
            "Not deployed"
          ) : (
            <Table<DeploymentRow>
              className={"details-sidebar-kv-table"}
              size="small"
              columns={DEPLOYMENT_COLUMNS}
              dataSource={deploymentRows}
              pagination={false}
              rowKey="key"
            />
          ),
      },
      {
        group: "logging settings",
        key: "Settings source",
        value: loggingSettings ? (
          <LoggingSettingsSourceTag
            isCustom={!!loggingSettings.custom}
            isConsulDefault={!!loggingSettings.consulDefault}
          />
        ) : (
          EMPTY
        ),
      },
      {
        group: "logging settings",
        key: "Sessions Level",
        value: formatOptional(loggingProperties?.sessionsLoggingLevel, (v) =>
          capitalize(v),
        ),
      },
      {
        group: "logging settings",
        key: "Session log details",
        value: formatOptional(loggingProperties?.sessionLogDetails, (v) =>
          capitalize(v),
        ),
      },
      {
        group: "logging settings",
        key: "Log level",
        value: formatOptional(loggingProperties?.logLoggingLevel, (v) =>
          capitalize(v),
        ),
      },
      {
        group: "logging settings",
        key: "Log payload",
        value:
          logPayload.length === 0 ? (
            EMPTY
          ) : (
            <Flex wrap="wrap" gap={4}>
              {logPayload.map((item) => (
                <Tag
                  key={item}
                  style={{
                    fontSize: 13,
                    padding: "3px 8px",
                    marginInlineEnd: 0,
                  }}
                >
                  {capitalize(item)}
                </Tag>
              ))}
            </Flex>
          ),
      },
      {
        group: "logging settings",
        key: "DPT events",
        value: loggingProperties
          ? capitalize(String(loggingProperties.dptEventsEnabled))
          : PLACEHOLDER,
      },
      {
        group: "logging settings",
        key: "Masking",
        value: loggingProperties
          ? capitalize(String(loggingProperties.maskingEnabled))
          : PLACEHOLDER,
      },
      {
        group: "Audit",
        key: "Created",
        value: (
          <AuthorDetails
            timestamp={chain?.createdWhen}
            user={chain?.createdBy}
          />
        ),
      },
      {
        group: "Audit",
        key: "Modified",
        value: (
          <AuthorDetails
            timestamp={chain?.modifiedWhen}
            user={chain?.modifiedBy}
          />
        ),
      },
    ],
    [
      chain,
      deploymentRows,
      deployments,
      logPayload,
      loggingProperties,
      loggingSettings,
    ],
  );

  return (
    <Drawer
      title="Chain Details"
      placement="right"
      size={350}
      open={open}
      onClose={onClose}
      destroyOnHidden
      closable={{ placement: "end" }}
      getContainer={".page-content"}
      rootStyle={{ position: "absolute" }}
      classNames={{ body: "details-sidebar-body" }}
    >
      {!chain ? null : (
        <Spin spinning={isLoading}>
          <Listy<SidebarItem>
            classNames={{
              groupHeader: "details-sidebar-properties-group",
            }}
            style={{ height: "100%" }}
            items={items}
            rowKey={"key"}
            group={{
              key: (item) => item.group,
              title: (groupKey) => `${groupKey}`,
            }}
            itemRender={(item) =>
              item.key === "Deployments" && deploymentRows.length > 0 ? (
                item.value
              ) : (
                <Row wrap={false} gutter={4}>
                  <Col span={8}>
                    <Typography.Text type="secondary">
                      {item.key}
                    </Typography.Text>
                  </Col>
                  <Col>{item.value}</Col>
                </Row>
              )
            }
            sticky
          />
        </Spin>
      )}
    </Drawer>
  );
};
