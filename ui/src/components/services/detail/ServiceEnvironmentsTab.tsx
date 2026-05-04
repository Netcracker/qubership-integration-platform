import React, {
  useEffect,
  useState,
  useRef,
  useMemo,
  useCallback,
} from "react";
import { Flex, message, Modal, Radio, Spin, Table, Tag } from "antd";
import {
  Environment,
  EnvironmentRequest,
  EnvironmentSourceType,
  IntegrationSystemType,
} from "../../../api/apiTypes";
import {
  useServiceContext,
  useChainsContext,
  useServiceParametersToolbar,
} from "./ServiceParametersPage";
import { api } from "../../../api/api";
import { EnvironmentParamsModal } from "../modals/EnvironmentParamsModal.tsx";
import type { ColumnsType } from "antd/es/table";
import { ChainColumn } from "../ui/ChainColumn";
import { useNotificationService } from "../../../hooks/useNotificationService";
import { getErrorMessage } from "../../../misc/error-utils";
import { useLocation } from "react-router-dom";
import { environmentLabels, prepareFile } from "../utils.tsx";
import { isVsCode } from "../../../api/rest/vscodeExtensionApi.ts";
import {
  isAmqpProtocol,
  isKafkaProtocol,
  normalizeProtocol,
} from "../../../misc/protocol-utils";
import { downloadFile } from "../../../misc/download-utils.ts";
import { ProtectedButton } from "../../../permissions/ProtectedButton.tsx";
import { usePermissions } from "../../../permissions/usePermissions.tsx";
import { hasPermissions } from "../../../permissions/funcs.ts";
import { useColumnSettingsBasedOnColumnsType } from "../../table/useColumnSettingsButton.tsx";
import {
  attachResizeToColumns,
  sumScrollXForColumns,
  useTableColumnResize,
} from "../../table/useTableColumnResize.tsx";
import { createActionsSizing } from "../../table/actionsColumn.ts";
import { TableToolbar } from "../../table/TableToolbar.tsx";
import { matchesByFields } from "../../table/tableSearch.ts";
import { TablePageLayout } from "../../TablePageLayout.tsx";
import { useResizeHeight } from "../../../hooks/useResizeHeigth.tsx";

interface ServiceEnvironmentsTabProps {
  formatTimestamp: (val: number) => string;
  setSystem?: (system: unknown) => void;
}

function environmentMatchesSearch(env: Environment, term: string): boolean {
  const labelParts =
    Array.isArray(env.labels) && env.labels.length > 0
      ? env.labels.map((label) => {
          if (typeof label === "string") return label;
          if (label && typeof label === "object" && "name" in label) {
            return String((label as { name?: unknown }).name);
          }
          return String(label);
        })
      : [];
  return matchesByFields(term, [
    env.name,
    env.address ?? "",
    env.sourceType ?? "",
    ...labelParts,
  ]);
}

const SERVICE_ENVIRONMENTS_TABLE_HEAD_RESERVE_PX = 59;

export const ServiceEnvironmentsTab: React.FC<ServiceEnvironmentsTabProps> = ({
  formatTimestamp,
  setSystem,
}) => {
  const system = useServiceContext();
  const protocol = system?.protocol || "";
  const normalizedProtocol = normalizeProtocol(protocol);
  const systemId = system?.id || "";
  const activeEnvironmentId = system?.activeEnvironmentId;

  const chains = useChainsContext() || [];
  const [environments, setEnvironments] = useState<Environment[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const cacheRef = useRef<{ [key: string]: Environment[] }>({});
  const [editModalOpen, setEditModalOpen] = useState(false);
  const [editingEnv, setEditingEnv] = useState<Environment | null>(null);
  const [saving, setSaving] = useState(false);
  const [addModalOpen, setAddModalOpen] = useState(false);
  const [switchingEnvId, setSwitchingEnvId] = useState<string | null>(null);
  const [searchTerm, setSearchTerm] = useState("");
  const notificationService = useNotificationService();
  const location = useLocation();
  const { setToolbar } = useServiceParametersToolbar() ?? {};
  const permissions = usePermissions();

  const getDefaultProperties = useCallback(
    (protocol: string, sourceType: EnvironmentSourceType) => {
      const normalized = normalizeProtocol(protocol);

      if (sourceType === EnvironmentSourceType.MANUAL) {
        if (isAmqpProtocol(normalized)) {
          return {
            password: "",
            username: "",
            routingKey: "",
            acknowledgeMode: "AUTO",
          };
        }
        if (isKafkaProtocol(normalized)) {
          return {
            key: "",
            sslProtocol: "",
            saslMechanism: "",
            saslJaasConfig: "",
            securityProtocol: "",
            sslEnabledProtocols: "",
            sslEndpointAlgorithm: "",
          };
        }
        if (normalized === "http") {
          return {
            connectTimeout: "120000",
            soTimeout: "120000",
            connectionRequestTimeout: "120000",
            responseTimeout: "120000",
            getWithBody: "false",
            deleteWithBody: "false",
          };
        }
      }

      if (sourceType === EnvironmentSourceType.MAAS_BY_CLASSIFIER) {
        if (isAmqpProtocol(normalized)) {
          return {
            routingKey: "",
            acknowledgeMode: "AUTO",
          };
        }
        return {};
      }

      return {};
    },
    [],
  );

  const defaultNewEnv: Environment = {
    id: "",
    systemId,
    name: "New Environment",
    address: "",
    labels: [],
    properties: getDefaultProperties(
      normalizedProtocol ?? "",
      EnvironmentSourceType.MANUAL,
    ),
    sourceType: EnvironmentSourceType.MANUAL,
  };

  const handleSwitchEnvironment = useCallback(
    (env: Environment) => {
      Modal.confirm({
        title: "Are you sure you want to switch to this Environment?",
        onOk: async () => {
          setSwitchingEnvId(env.id);
          try {
            await api.updateService(systemId, {
              activeEnvironmentId: env.id,
              name: system?.name,
              type: system?.type,
            });
            delete cacheRef.current[systemId];
            setLoading(true);
            const newEnvs = await api.getEnvironments(systemId);
            setEnvironments(newEnvs);
            if (setSystem) {
              const updatedSystem = await api.getService(systemId);
              setSystem(updatedSystem);
            }
            message.success("Active environment switched");
          } catch (e: unknown) {
            notificationService.requestFailed(
              getErrorMessage(e, "Switch failed"),
              e,
            );
          } finally {
            setSwitchingEnvId(null);
            setLoading(false);
          }
        },
      });
    },
    [systemId, system, setSystem, notificationService],
  );

  const loadEnvironments = useCallback(
    async (silent = false) => {
      if (!systemId) return;
      if (!silent) {
        setError(null);
        setLoading(true);
      }
      try {
        const data = await api.getEnvironments(systemId);
        setEnvironments(data);
        cacheRef.current[systemId] = data;
      } catch (e: unknown) {
        if (!silent) {
          setError(getErrorMessage(e, "Environments load error"));
        }
      } finally {
        if (!silent) {
          setLoading(false);
        }
      }
    },
    [systemId],
  );

  useEffect(() => {
    void loadEnvironments();
  }, [loadEnvironments]);

  useEffect(() => {
    if (systemId && location.pathname.includes("/environments")) {
      void loadEnvironments(true);
    }
  }, [location.pathname, systemId, loadEnvironments]);

  const handleEditClick = useCallback((env: Environment) => {
    setEditingEnv(env);
    setEditModalOpen(true);
  }, []);

  const handleDelete = useCallback(
    (envId: string) => {
      Modal.confirm({
        title: "Are you sure you want to delete this Environment?",
        onOk: async () => {
          try {
            setLoading(true);
            await api.deleteEnvironment(systemId, envId);
            setEnvironments((envs) => envs.filter((e) => e.id !== envId));
            message.success("Environment deleted");
          } catch (e: unknown) {
            notificationService.requestFailed(
              getErrorMessage(e, "Delete failed"),
              e,
            );
          } finally {
            setLoading(false);
          }
        },
      });
    },
    [systemId, notificationService],
  );

  const memoChains = useMemo(() => chains, [chains]);

  const filteredEnvironments = useMemo(
    () => environments.filter((e) => environmentMatchesSearch(e, searchTerm)),
    [environments, searchTerm],
  );

  const columns: ColumnsType<Environment> = useMemo(
    () => [
      {
        title: "",
        key: "select",
        width: 48,
        align: "center",
        render: (_: unknown, record: Environment) => (
          <Radio
            checked={record.id === activeEnvironmentId}
            onChange={() =>
              hasPermissions(permissions, { service: ["update"] }) &&
              handleSwitchEnvironment(record)
            }
            disabled={
              record.id === activeEnvironmentId || switchingEnvId === record.id
            }
          />
        ),
      },
      {
        title: "Name",
        dataIndex: "name",
        key: "name",
        render: (text: string, record: Environment) => (
          <span
            role="button"
            tabIndex={0}
            style={{
              fontWeight: 600,
              color: "var(--vscode-textLink-foreground, #1677ff)",
              cursor: "pointer",
            }}
            onClick={() =>
              hasPermissions(permissions, { environment: ["update"] }) &&
              handleEditClick(record)
            }
            onKeyDown={(e) => {
              if (e.key === "Enter" || e.key === " ") {
                e.preventDefault();
                handleEditClick(record);
              }
            }}
          >
            {text}
          </span>
        ),
      },
      {
        title: "Address",
        dataIndex: "address",
        key: "address",
        render: (text?: string) =>
          text || (
            <span
              style={{
                color:
                  "var(--vscode-descriptionForeground, rgba(0, 0, 0, 0.45))",
              }}
            >
              /
            </span>
          ),
      },
      {
        title: "Source",
        dataIndex: "sourceType",
        key: "sourceType",
        render: (val?: string) => {
          if (!val) return "-";
          if (val === "MAAS" || val === "MAAS_BY_CLASSIFIER") return "MaaS";
          return val.charAt(0) + val.slice(1).toLowerCase();
        },
      },
      {
        title: "Modified At",
        dataIndex: "modifiedWhen",
        key: "modifiedWhen",
        hidden: isVsCode,
        render: (val?: number) => (val ? formatTimestamp(val) : ""),
      },
      {
        title: "Labels",
        dataIndex: "labels",
        key: "labels",
        render: (labels?: unknown[]) => {
          if (!Array.isArray(labels) || labels.length === 0) {
            return (
              <span
                style={{
                  color:
                    "var(--vscode-descriptionForeground, rgba(0, 0, 0, 0.45))",
                }}
              >
                -
              </span>
            );
          }

          return (
            <span>
              {labels.map((label, idx) => {
                let name =
                  typeof label === "string"
                    ? label
                    : typeof label === "object" && label && "name" in label
                      ? String((label as { name?: unknown }).name)
                      : String(label);
                const labelKey = name as keyof typeof environmentLabels;
                name = environmentLabels[labelKey] || name;

                return (
                  <Tag color="blue" key={`${name}-${idx}`}>
                    {name}
                  </Tag>
                );
              })}
            </span>
          );
        },
      },
      {
        title: "Used by",
        key: "usedBy",
        align: "center",
        render: (_: unknown, record: Environment) => {
          const isActive = system && record.id === system.activeEnvironmentId;
          return <ChainColumn chains={isActive ? memoChains : []} />;
        },
      },
      {
        title: "",
        key: "actions",
        align: "center",
        ...createActionsSizing<Environment>(48),
        render: (_: unknown, record: Environment) => (
          <ProtectedButton
            require={{ environment: ["delete"] }}
            tooltipProps={{ title: "Delete", placement: "bottom" }}
            buttonProps={{
              type: "text",
              iconName: "delete",
              danger: true,
              onClick: () => handleDelete(record.id),
            }}
          />
        ),
      },
    ],
    [
      activeEnvironmentId,
      switchingEnvId,
      permissions,
      handleSwitchEnvironment,
      handleEditClick,
      formatTimestamp,
      system,
      memoChains,
      handleDelete,
    ],
  );

  const { orderedColumns, columnSettingsButton } =
    useColumnSettingsBasedOnColumnsType<Environment>(
      "serviceEnvironmentsTable",
      columns,
    );

  const environmentsColumnResize = useTableColumnResize({
    name: 160,
    address: 200,
    sourceType: 100,
    modifiedWhen: 160,
    labels: 200,
    usedBy: 120,
  });

  const columnsWithResize = useMemo(
    () =>
      attachResizeToColumns(
        orderedColumns,
        environmentsColumnResize.columnWidths,
        environmentsColumnResize.createResizeHandlers,
        { minWidth: 80 },
      ),
    [
      orderedColumns,
      environmentsColumnResize.columnWidths,
      environmentsColumnResize.createResizeHandlers,
    ],
  );

  const [tableAreaRef, tableAreaHeight] = useResizeHeight<HTMLDivElement>();

  const tableBodyScrollY = useMemo(() => {
    if (tableAreaHeight <= 0) {
      return 400;
    }
    return Math.max(
      120,
      tableAreaHeight - SERVICE_ENVIRONMENTS_TABLE_HEAD_RESERVE_PX,
    );
  }, [tableAreaHeight]);

  const scrollX = useMemo(
    () =>
      sumScrollXForColumns(
        columnsWithResize,
        environmentsColumnResize.columnWidths,
      ),
    [columnsWithResize, environmentsColumnResize.columnWidths],
  );

  const tableScroll = useMemo(
    () =>
      filteredEnvironments.length > 0
        ? { x: scrollX, y: tableBodyScrollY }
        : { x: scrollX },
    [scrollX, tableBodyScrollY, filteredEnvironments.length],
  );

  const isEnvironmentsActive = location.pathname.includes("/environments");

  const environmentsToolbarActions = useMemo(
    () => (
      <>
        {!isVsCode && (
          <ProtectedButton
            require={{ service: ["export"] }}
            tooltipProps={{ title: "Export service", placement: "bottom" }}
            buttonProps={{
              iconName: "cloudDownload",
              onClick: () => {
                void (async () => {
                  if (!systemId) return;
                  try {
                    const file = await api.exportServices([systemId], []);
                    downloadFile(prepareFile(file));
                  } catch (e) {
                    notificationService.requestFailed("Export error", e);
                  }
                })();
              },
            }}
          />
        )}
        {system?.type === IntegrationSystemType.EXTERNAL && (
          <ProtectedButton
            require={{ environment: ["create"] }}
            tooltipProps={{ title: "Add Environment", placement: "bottom" }}
            buttonProps={{
              type: "primary",
              onClick: () => setAddModalOpen(true),
              children: "Add Environment",
            }}
          />
        )}
      </>
    ),
    [notificationService, system?.type, systemId],
  );

  useEffect(() => {
    if (!setToolbar || !isEnvironmentsActive) return;
    setToolbar(
      <TableToolbar
        variant="admin"
        search={{
          value: searchTerm,
          onChange: setSearchTerm,
          placeholder: "Search environments...",
          allowClear: true,
        }}
        columnSettingsButton={columnSettingsButton}
        actions={environmentsToolbarActions}
      />,
    );
    return () => {
      setToolbar(null);
    };
    // columnSettingsButton is unstable (new element each render) — including it
    // retriggers this effect after setToolbar → parent re-render → infinite loop.
    // eslint-disable-next-line react-hooks/exhaustive-deps -- omit columnSettingsButton
  }, [
    setToolbar,
    isEnvironmentsActive,
    searchTerm,
    environmentsToolbarActions,
  ]);

  if (loading) return <Spin style={{ margin: 32 }} />;
  if (error)
    return (
      <div
        style={{ color: "var(--vscode-errorForeground, #d73a49)", margin: 32 }}
      >
        {error}
      </div>
    );
  if (!systemId) return null;

  return (
    <Flex
      vertical
      style={{
        flex: 1,
        minHeight: 0,
        height: "100%",
      }}
    >
      <TablePageLayout>
        <div
          ref={tableAreaRef}
          style={{
            flex: 1,
            minHeight: 0,
            display: "flex",
            flexDirection: "column",
          }}
        >
          <Table<Environment>
            className="flex-table"
            dataSource={filteredEnvironments}
            rowKey="id"
            pagination={false}
            size="small"
            style={{
              width: "100%",
              flex: 1,
              minHeight: 0,
            }}
            columns={columnsWithResize}
            scroll={tableScroll}
            components={environmentsColumnResize.resizableHeaderComponents}
          />
        </div>
      </TablePageLayout>
      <EnvironmentParamsModal
        open={editModalOpen}
        environment={editingEnv}
        onClose={() => {
          setEditModalOpen(false);
          setEditingEnv(null);
        }}
        onSave={async (envRequest: EnvironmentRequest) => {
          try {
            setSaving(true);
            if (!editingEnv) throw new Error("No environment selected");
            const updated = await api.updateEnvironment(
              systemId,
              editingEnv.id,
              envRequest,
            );
            setEnvironments((envs) =>
              envs.map((e) => (e.id === updated.id ? updated : e)),
            );
            setEditModalOpen(false);
            message.success("Environment updated");
          } catch (e: unknown) {
            notificationService.requestFailed(
              getErrorMessage(e, "Update failed"),
              e,
            );
            throw e;
          } finally {
            setSaving(false);
          }
        }}
        saving={saving}
      />
      <EnvironmentParamsModal
        open={addModalOpen}
        environment={defaultNewEnv}
        onClose={() => setAddModalOpen(false)}
        onSave={async (envRequest: EnvironmentRequest) => {
          try {
            setSaving(true);
            const created = await api.createEnvironment(systemId, envRequest);
            setEnvironments((envs) => [...envs, created]);
            setAddModalOpen(false);
            message.success("Environment created");
          } catch (e: unknown) {
            notificationService.requestFailed(
              getErrorMessage(e, "Create failed"),
              e,
            );
            throw e;
          } finally {
            setSaving(false);
          }
        }}
        saving={saving}
      />
    </Flex>
  );
};
