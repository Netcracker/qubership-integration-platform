import React, { useCallback, useEffect, useMemo, useState } from "react";
import styles from "./Services.module.css";
import {
  CloudDownloadOutlined,
  CloudUploadOutlined,
  MoreOutlined,
  PlusOutlined,
  TableOutlined,
  CloudOutlined,
  ClusterOutlined, GlobalOutlined,
  RightOutlined,
  DownOutlined,
} from "@ant-design/icons";
import {
  FloatButton,
  Typography,
  message,
  Flex,
} from "antd";
import FloatButtonGroup from "antd/lib/float-button/FloatButtonGroup";
import { CreateServiceModal } from "./CreateServiceModal";
import { useNavigate } from "react-router-dom";
import { api } from "../../api/api";
import {
  IntegrationSystemType,
  Specification,
} from "../../api/apiTypes";
import { useNotificationService } from "../../hooks/useNotificationService";
import {
  useServicesTreeTable,
  allServicesTreeTableColumns,
  getActionsColumn,
  getServiceActions,
  ServiceEntity,
  ServicesTableColumn,
  isSpecification,
  isSpecificationGroup,
  isIntegrationSystem,
} from "./ServicesTreeTable";
import type { IntegrationSystem, SpecificationGroup } from "../../api/apiTypes";
import { downloadFile } from '../../misc/download-utils';
import { prepareFile } from "./utils.tsx";
import ImportServicesModal from "./ImportServicesModal";
import { useModalsContext } from "../../Modals";
import { getErrorMessage } from '../../misc/error-utils';
import { useAsyncRequest } from './useAsyncRequest';
import type { ExpandableConfig } from 'antd/es/table/interface';

const STORAGE_KEY = "servicesListTable";

const visibleColumns: string[] = [
  "name",
  "protocol",
  "status",
  "source",
  "labels",
  "usedBy",
];

export const ServicesListPage: React.FC = () => {
  type TabType = "external" | "internal" | "implemented";
  const [tab] = useHashTab("implemented") as [TabType, (tab: TabType) => void];
  const [createModalOpen, setCreateModalOpen] = useState(false);
  const [createLoading, setCreateLoading] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);
  const [expandedRowKeys, setExpandedRowKeys] = useState<string[]>([]);
  const [servicesByType, setServicesByType] = useState<{
    external: IntegrationSystem[];
    internal: IntegrationSystem[];
    implemented: IntegrationSystem[];
  }>({
    external: [],
    internal: [],
    implemented: [],
  });
  const [specGroupsByService, setSpecGroupsByService] = useState<Record<string, SpecificationGroup[]>>({});
  const [specsByGroup, setSpecsByGroup] = useState<Record<string, Specification[]>>({});
  const [loadingRows, setLoadingRows] = useState<string[]>([]);
  const [selectedRowKeys, setSelectedRowKeys] = useState<React.Key[]>([]);
  const notify = useNotificationService();
  const navigate = useNavigate();
  const { showModal } = useModalsContext();

  const {
    loading,
    error,
    execute: loadServices,
  } = useAsyncRequest(async () => {
    const all = await api.getServices("", false);
    setServicesByType({
      external: all.filter((s) => s.type === IntegrationSystemType.EXTERNAL),
      internal: all.filter((s) => s.type === IntegrationSystemType.INTERNAL),
      implemented: all.filter((s) => s.type === IntegrationSystemType.IMPLEMENTED),
    });
  }, { initialValue: undefined });

  useEffect(() => {
    void loadServices();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const getServicesByTab = useCallback(() => {
    if (tab === "internal") return servicesByType["internal"];
    return servicesByType[tab] ?? [];
  },[servicesByType, tab]);

  const buildDataSource = useMemo((): ServiceEntity[] => {
    return getServicesByTab().map((service: IntegrationSystem) => {
      const groups = specGroupsByService[service.id];
      return {
        ...service,
        children: (groups && groups.length > 0
          ? groups.map((group) => {
            const specs = specsByGroup[group.id];
            return {
              ...group,
              children: specs ?? [],
            };
          })
          : []),
      };
    });
  }, [getServicesByTab, specGroupsByService, specsByGroup]);

  const handleExpand = async (expanded: boolean, record: ServiceEntity) => {
    if (!expanded) return;
    if (isIntegrationSystem(record) && !specGroupsByService[record.id]) {
      setLoadingRows((rows) => [...rows, record.id]);
      try {
        const groups = await api.getApiSpecifications(record.id);
        setSpecGroupsByService((prev) => ({ ...prev, [record.id]: groups }));
      } catch (e: unknown) {
        notify.requestFailed(getErrorMessage(e, 'Specifications load error'), e);
      } finally {
        setLoadingRows((rows) => rows.filter((id) => id !== record.id));
      }
    }
    if (isSpecificationGroup(record) && !specsByGroup[record.id]) {
      setLoadingRows((rows) => [...rows, record.id]);
      try {
        setSpecsByGroup((prev) => ({ ...prev, [record.id]: record.specifications }));
      } finally {
        setLoadingRows((rows) => rows.filter((id) => id !== record.id));
      }
    }
  };

  const expandable = {
    expandedRowKeys,
    onExpand: (expanded: boolean, record: ServiceEntity) => {
      setExpandedRowKeys((keys) => {
        if (expanded) return [...keys, record.id];
        return keys.filter((k) => k !== record.id);
      });
      void handleExpand(expanded, record);
    },
    rowExpandable: (record: ServiceEntity) => {
      if (isIntegrationSystem(record)) return true;
      return isSpecificationGroup(record) && Array.isArray(record.specifications) && record.specifications.length > 0;
    },
    childrenColumnName: 'children',
    expandIcon: ({ expanded, onExpand, record }: Parameters<NonNullable<ExpandableConfig<ServiceEntity>["expandIcon"]>>[0]) =>
      expandable.rowExpandable(record) ? (
        <span
          onClick={e => {
            onExpand(record, e);
            e.stopPropagation();
          }}
          style={{ cursor: "pointer", fontSize: 11, color: '#b0b8c4', display: 'inline-flex', alignItems: 'center', verticalAlign: 'sub', marginRight: 6 }}
        >
          {expanded ? <DownOutlined /> : <RightOutlined />}
        </span>
      ) : null,
  };

  const isRootEntity = (record: ServiceEntity) => {
    return isIntegrationSystem(record)
  };

  const handleEdit = (record: ServiceEntity) => {
    if (isIntegrationSystem(record)) {
      void navigate(`/services/systems/${record.id}/specificationGroups`);
    }
  };

  const handleExpandAll = async () => {
    const roots = getServicesByTab();

    const groupsMap: Record<string, SpecificationGroup[]> = { ...specGroupsByService };
    await Promise.all(
      roots.map(async (service) => {
        if (!groupsMap[service.id]) {
          try {
            const groups = await api.getApiSpecifications(service.id);
            groupsMap[service.id] = groups;
            setSpecGroupsByService((prev) => ({ ...prev, [service.id]: groups }));
          } catch (e: unknown) {
            notify.requestFailed(getErrorMessage(e, 'Error loading specifications groups'), e);
          }
        }
      })
    );

    const specsMap: Record<string, Specification[]> = { ...specsByGroup };
    const allGroups = Object.values(groupsMap).flat();

    await Promise.all(
      allGroups.map((group) => {
        if (!specsMap[group.id] && group.specifications) {
          specsMap[group.id] = group.specifications;
          setSpecsByGroup((prev) => ({ ...prev, [group.id]: group.specifications }));
        }
      })
    );

    const rootIds = roots.map((r) => r.id);
    const groupIds = allGroups.map((g) => g.id);
    const specIds = allGroups.flatMap((g) =>
      g.specifications ? g.specifications.map((s) => s.id) : []
    );

    const allIds = [...rootIds, ...groupIds, ...specIds];
    setExpandedRowKeys(allIds);
  };

  const handleCollapseAll = () => {
    setExpandedRowKeys([]);
  };

  const handleDeleteWithConfirm = (record: ServiceEntity) => {
    if (isIntegrationSystem(record)) {
      void handleDelete(record.id);
    }
  };

  const actionsColumn: ServicesTableColumn = getActionsColumn(
    getServiceActions({
      onEdit: handleEdit,
      onDelete: handleDeleteWithConfirm,
      onExpandAll: () => { void handleExpandAll(); },
      onCollapseAll: handleCollapseAll,
      isRootEntity,
      onExportSelected:  (selected) => { void handleExportSelected(selected); },
    })
  );

  const rowClassName = (record: ServiceEntity) => loadingRows.includes(record.id) ? styles.loadingRow : '';

  const servicesTable = useServicesTreeTable<ServiceEntity>({
    dataSource: buildDataSource,
    rowKey: "id",
    columns: allServicesTreeTableColumns.map(col => col.key),
    allColumns: [...allServicesTreeTableColumns.map(col => col.key), actionsColumn.key],
    defaultVisibleKeys: visibleColumns,
    storageKey: STORAGE_KEY,
    loading,
    expandable,
    actionsColumn,
    enableSelection: true,
    isRootEntity,
    onExportSelected:  (selected) => { void handleExportSelected(selected); },
    selectedRowKeys,
    onSelectedRowKeysChange: (keys) => {
      setSelectedRowKeys(keys);
    },
    rowClassName,
    onUpdateLabels: async (record, labels) => {
      const updated: ServiceEntity = { ...record, labels: labels.map(name => ({ name, technical: false })) };
      if (isIntegrationSystem(record)) {
        await api.updateService(record.id, updated as IntegrationSystem);
        await loadServices();
      } else if (isSpecificationGroup(record)) {
        const res = await api.updateApiSpecificationGroup(record.id, updated as SpecificationGroup);
        setSpecGroupsByService(prev => updateLabelsInMap(prev, res));
      } else if (isSpecification(record)) {
        const res = await api.updateSpecificationModel(record.id, updated as Specification);
        setSpecsByGroup(prev => updateLabelsInMap(prev, res));
      }
    },
    onRowClick: (record, event) => {
      const target = event.target as HTMLElement;
      if (
        target.closest('button') ||
        target.closest('.ant-dropdown') ||
        target.closest('input') ||
        target.closest('a') ||
        target.closest('.inline-edit-labels')
      ) {
        return;
      }
      if (isIntegrationSystem(record)) {
        void navigate(`/services/systems/${record.id}/specificationGroups`);
      } else if (isSpecificationGroup(record)) {
        setExpandedRowKeys((keys) => keys.includes(record.id) ? keys.filter((k) => k !== record.id) : [...keys, record.id]);
        if (!specsByGroup[record.id]) {
          setLoadingRows((rows) => [...rows, record.id]);
          setSpecsByGroup((prev) => ({ ...prev, [record.id]: record.specifications }));
          setLoadingRows((rows) => rows.filter((id) => id !== record.id));
        }
      }
    },
  });

  const handleExportSelected = async (selected: ServiceEntity[]) => {
    if (!selected.length) {
      message.info('No services selected');
      return;
    }
    try {
      const systemIds = selected.map(s => s.id)
      const file = await api.exportServices(systemIds, []);
      downloadFile(prepareFile(file));
    } catch (e: unknown) {
      notify.requestFailed(getErrorMessage(e, 'Export error'), e);
    }
  };

  const handleCreate = async (name: string, description: string | undefined, type: IntegrationSystemType) => {
    setCreateLoading(true);
    setCreateError(null);
    try {
      const service = await api.createService({ name, description, type });
      if (type === IntegrationSystemType.INTERNAL || type === IntegrationSystemType.IMPLEMENTED) {
        await api.createEnvironment(service.id, { name, address: "/" });
      }
      void loadServices();
      setCreateModalOpen(false);
      message.success("Service created");
      void navigate(`/services/systems/${service.id}/parameters`);
    } catch (e: unknown) {
      setCreateError(getErrorMessage(e, "Service creation error"));
      notify.requestFailed(getErrorMessage(e, "Service creation error"), e);
    } finally {
      setCreateLoading(false);
    }
  };

  const handleDelete = async (id: string) => {
    try {
      await api.deleteService(id);
      void loadServices();
      message.success("Service deleted");
    } catch (e: unknown) {
      notify.requestFailed(getErrorMessage(e, "Service deletion error"), e);
    }
  };

  function updateLabelsInMap<T extends { id: string; labels?: import("../../api/apiTypes").EntityLabel[] }>(
    map: Record<string, T[]>,
    updated: T
  ): Record<string, T[]> {
    const newMap = { ...map };
    Object.keys(newMap).forEach(key => {
      newMap[key] = newMap[key].map(item => item.id === updated.id ? { ...item, labels: updated.labels } : item);
    });
    return newMap;
  }

  return (
    <Flex vertical className={styles["container"]}>
        <div className={styles["header"]}>
          <Typography.Title level={4} className={styles["title"]}>
            {(() => {
              switch (tab) {
                case "external": return <GlobalOutlined className={styles["icon"]} />;
                case "internal": return <CloudOutlined className={styles["icon"]} />;
                case "implemented": return <ClusterOutlined className={styles["icon"]} />;
                default: return <TableOutlined className={styles["icon"]} />;
              }
            })()}
            {(() => {
              switch (tab) {
                case "external": return "External Services";
                case "internal": return "Internal Services";
                case "implemented": return "Implemented Services";
                default: return "Services";
              }
            })()}
          </Typography.Title>

          <div className={styles["actions"]}>
            {servicesTable.FilterButton()}
          </div>
        </div>

        <div>
          {loading && <div>Loading...</div>}

          <servicesTable.Table />
          {error && <div style={{ color: "red" }}>Error: {error}</div>}

          <FloatButtonGroup trigger="hover" icon={<MoreOutlined />}>
            <FloatButton
              tooltip={{ title: "Download selected services", placement: "left" }}
              icon={<CloudDownloadOutlined />}
              onClick={() => {
                void (async () => {
                  if (selectedRowKeys.length === 0) {
                    message.info('No services selected');
                    return;
                  }
                  const selected: ServiceEntity[] = buildDataSource.filter(
                    (s: ServiceEntity) => selectedRowKeys.includes(s.id)
                  );
                  await handleExportSelected(selected);
                })();
              }}
            />
            <FloatButton
              tooltip={{ title: "Upload services", placement: "left" }}
              icon={<CloudUploadOutlined />}
              onClick={() => {
                showModal({
                  component: <ImportServicesModal onSuccess={() => { void loadServices(); }} />,
                });
              }}
            />
            <FloatButton
              tooltip={{ title: "Create service", placement: "left" }}
              icon={<PlusOutlined />}
              onClick={() => setCreateModalOpen(true)} />
          </FloatButtonGroup>
        </div>

        <CreateServiceModal
          open={createModalOpen}
          onCancel={() => {
            setCreateModalOpen(false);
            setCreateError(null);
          }}
          onCreate={handleCreate}
          loading={createLoading}
          error={createError}
          defaultType={tab === "external" ? IntegrationSystemType.EXTERNAL : (tab === "internal" ? IntegrationSystemType.INTERNAL : IntegrationSystemType.IMPLEMENTED)}
        />
    </Flex>
  );
};

function useHashTab(defaultTab: string): [string, (tab: string) => void] {
  const [tab, setTab] = useState(() => location.hash.slice(1) || defaultTab);

  useEffect(() => {
    const onHashChange = () => {
      setTab(location.hash.slice(1) || defaultTab);
    };
    window.addEventListener("hashchange", onHashChange);
    return () => window.removeEventListener("hashchange", onHashChange);
  }, [defaultTab, tab]);

  const navigate = (nextTab: string) => {
    window.location.hash = nextTab;
  };

  return [tab, navigate];
}
