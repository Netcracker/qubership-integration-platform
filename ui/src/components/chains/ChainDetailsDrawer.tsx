import React, { useEffect, useMemo, useState } from "react";
import { Flex, Spin, Table, Tag, Typography } from "antd";
import type { TableProps } from "antd/lib/table";
import {
  ChainItem,
  ChainLoggingSettings,
  Deployment,
  DeploymentStatus,
} from "../../api/apiTypes.ts";
import { api } from "../../api/api.ts";
import { useNotificationService } from "../../hooks/useNotificationService.tsx";
import { capitalize, PLACEHOLDER } from "../../misc/format-utils.ts";
import { pickLoggingProperties } from "../../misc/logging-utils.ts";
import { DeploymentsCumulativeState } from "../deployment_runtime_states/DeploymentsCumulativeState.tsx";
import { DeploymentStatusTag } from "../deployment_runtime_states/DeploymentStatusTag.tsx";
import { EntityLabels } from "../labels/EntityLabels.tsx";
import { LoggingSettingsSourceTag } from "../logging/LoggingSettingsSourceTag.tsx";
import {
  AuthorDetails,
  DetailsSidebar,
  IdText,
  Placeholder,
  renderOptional,
} from "../DetailsSidebar.tsx";

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

  const sections = useMemo(
    () => [
      {
        key: "general-info",
        title: "general info",
        items: [
          {
            label: "ID",
            value: <IdText value={chain?.id} />,
          },
          {
            label: "Name",
            value: renderOptional(chain?.name),
          },
          {
            label: "Description",
            value: renderOptional(chain?.description),
          },
          {
            label: "Labels",
            value:
              chain?.labels && chain?.labels.length > 0 ? (
                <EntityLabels labels={chain.labels} />
              ) : (
                <Placeholder />
              ),
          },
        ],
      },
      {
        key: "status",
        title: "status & deployment",
        items: [
          {
            label: "Status",
            value: (
              <Spin spinning={isLoading}>
                {renderOptional(chain, (v) => (
                  <DeploymentsCumulativeState
                    chainId={v.id}
                    isNotificationEnabled={false}
                    deployments={deployments}
                  />
                ))}
              </Spin>
            ),
          },
          {
            label: "Deployments",
            showLabel: deploymentRows.length === 0,
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
        ],
      },
      {
        key: "logging-settings",
        title: "logging settings",
        items: [
          {
            label: "Settings Source",
            value: (
              <Spin spinning={isLoading}>
                {renderOptional(loggingSettings, (ls) => (
                  <LoggingSettingsSourceTag
                    isCustom={!!ls.custom}
                    isConsulDefault={!!ls.consulDefault}
                  />
                ))}
              </Spin>
            ),
          },
          {
            label: "Sessions Level",
            value: (
              <Spin spinning={isLoading}>
                {renderOptional(
                  loggingProperties?.sessionsLoggingLevel,
                  capitalize,
                )}
              </Spin>
            ),
          },
          {
            label: "Session Log Details",
            value: (
              <Spin spinning={isLoading}>
                {renderOptional(
                  loggingProperties?.sessionLogDetails,
                  capitalize,
                )}
              </Spin>
            ),
          },
          {
            label: "Log Level",
            value: (
              <Spin spinning={isLoading}>
                {renderOptional(loggingProperties?.logLoggingLevel, capitalize)}
              </Spin>
            ),
          },
          {
            label: "Log Payload",
            value: (
              <Spin spinning={isLoading}>
                {logPayload.length === 0 ? (
                  <Placeholder />
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
                )}
              </Spin>
            ),
          },
          {
            label: "DPT Events",
            value: (
              <Spin spinning={isLoading}>
                {renderOptional(loggingProperties?.dptEventsEnabled, (v) =>
                  capitalize(String(v)),
                )}
              </Spin>
            ),
          },
          {
            label: "Masking",
            value: (
              <Spin spinning={isLoading}>
                {renderOptional(loggingProperties?.maskingEnabled, (v) =>
                  capitalize(String(v)),
                )}
              </Spin>
            ),
          },
        ],
      },
      {
        key: "audit",
        title: "Audit",
        items: [
          {
            label: "Created",
            value: (
              <AuthorDetails
                timestamp={chain?.createdWhen}
                username={chain?.createdBy?.username}
              />
            ),
          },
          {
            label: "Modified",
            value: (
              <AuthorDetails
                timestamp={chain?.modifiedWhen}
                username={chain?.modifiedBy?.username}
              />
            ),
          },
        ],
      },
    ],
    [
      chain,
      deploymentRows,
      deployments,
      isLoading,
      logPayload,
      loggingProperties?.dptEventsEnabled,
      loggingProperties?.logLoggingLevel,
      loggingProperties?.maskingEnabled,
      loggingProperties?.sessionLogDetails,
      loggingProperties?.sessionsLoggingLevel,
      loggingSettings,
    ],
  );

  return (
    <DetailsSidebar
      title="Chain Details"
      open={open}
      onClose={onClose}
      sections={sections}
    />
  );
};
