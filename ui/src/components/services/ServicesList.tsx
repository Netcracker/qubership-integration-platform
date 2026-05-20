import React, { useCallback, useEffect, useMemo, useState } from "react";
import styles from "./Services.module.css";
import { message } from "antd";
import { useNavigate } from "react-router-dom";
import { api } from "../../api/api";
import { IntegrationSystemType, Specification } from "../../api/apiTypes";
import { useNotificationService } from "../../hooks/useNotificationService";
import { useServiceFilters } from "../../hooks/useServiceFilter";
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
import type {
  ContextSystem,
  IntegrationSystem,
  SpecificationGroup,
} from "../../api/apiTypes";
import { downloadFile } from "../../misc/download-utils";
import { invalidateServiceCache, prepareFile } from "./utils.tsx";
import { ImportSpecificationsModal } from "./modals/ImportSpecificationsModal";
import { useModalsContext } from "../../Modals";
import { getErrorMessage } from "../../misc/error-utils";
import { useAsyncRequest } from "./useAsyncRequest";
import { OverridableIcon } from "../../icons/IconProvider.tsx";
import { treeExpandIcon } from "../table/TreeExpandIcon.tsx";
import { capitalize } from "../../misc/format-utils.ts";
import { GenericServiceListPage } from "./GenericServiceListPage.tsx";

const STORAGE_KEY = "servicesListTable";

const visibleColumns: string[] = [
  "name",
  "protocol",
  "status",
  "source",
  "labels",
  "usedBy",
];

type TabType = "external" | "internal" | "implemented";

export type ServicesListProps = {
  tab: TabType;
};

export const ServicesList: React.FC<ServicesListProps> = ({ tab }) => {
  const [expandedRowKeys, setExpandedRowKeys] = useState<string[]>([]);
  const [services, setServices] = useState<IntegrationSystem[]>([]);
  const [specGroupsByService, setSpecGroupsByService] = useState<
    Record<string, SpecificationGroup[]>
  >({});
  const [specsByGroup, setSpecsByGroup] = useState<
    Record<string, Specification[]>
  >({});
  const [loadingRows, setLoadingRows] = useState<string[]>([]);
  const [selectedRowKeys, setSelectedRowKeys] = useState<React.Key[]>([]);
  const [searchString, setSearchString] = useState("");
  const notificationService = useNotificationService();
  const navigate = useNavigate();
  const { showModal } = useModalsContext();
  const { filters, filterButton, resetFilters } = useServiceFilters();

  const { loading, execute: loadServices } = useAsyncRequest(
    async () => {
      const hasSearch = searchString.trim().length > 0;
      const hasFilters = filters.length > 0;

      let servicesArray: IntegrationSystem[];
      if (hasSearch && hasFilters) {
        const [searched, filtered] = await Promise.all([
          api.searchServices(searchString.trim()),
          api.filterServices(filters),
        ]);
        const filteredIds = new Set(filtered.map((s) => s.id));
        servicesArray = searched.filter((s) => filteredIds.has(s.id));
      } else if (hasFilters) {
        servicesArray = await api.filterServices(filters);
      } else if (hasSearch) {
        servicesArray = await api.searchServices(searchString.trim());
      } else {
        const all = await api.getServices("", false);
        servicesArray = Array.isArray(all) ? all : [];
      }

      setServices(servicesArray.filter((s) => s.type === getSystemType(tab)));
    },
    { initialValue: undefined },
  );

  useEffect(() => {
    void loadServices();
  }, [filters, searchString, tab]);

  useEffect(() => {
    resetFilters();
  }, [tab]);

  const buildDataSource = useMemo((): ServiceEntity[] => {
    return services.map(
      (service: IntegrationSystem | ContextSystem) => {
        const groups = specGroupsByService[service.id];
        return {
          ...service,
          children:
            groups && groups.length > 0
              ? groups.map((group) => {
                  const specs = specsByGroup[group.id];
                  return {
                    ...group,
                    children: specs ?? [],
                  };
                })
              : [],
        };
      },
    );
  }, [services, specGroupsByService, specsByGroup]);

  const handleExpand = async (expanded: boolean, record: ServiceEntity) => {
    if (!expanded) return;
    if (isIntegrationSystem(record) && !specGroupsByService[record.id]) {
      setLoadingRows((rows) => [...rows, record.id]);
      try {
        const groups = await api.getApiSpecifications(record.id);
        setSpecGroupsByService((prev) => ({ ...prev, [record.id]: groups }));
      } catch (e: unknown) {
        notificationService.requestFailed(
          getErrorMessage(e, "Specifications load error"),
          e,
        );
      } finally {
        setLoadingRows((rows) => rows.filter((id) => id !== record.id));
      }
    }
    if (isSpecificationGroup(record) && !specsByGroup[record.id]) {
      setLoadingRows((rows) => [...rows, record.id]);
      try {
        setSpecsByGroup((prev) => ({
          ...prev,
          [record.id]: record.specifications,
        }));
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
      return (
        isSpecificationGroup(record) &&
        Array.isArray(record.specifications) &&
        record.specifications.length > 0
      );
    },
    childrenColumnName: "children",
    expandIcon: treeExpandIcon<ServiceEntity>(),
  };

  const isRootEntity = (record: ServiceEntity) => {
    return isIntegrationSystem(record);
  };

  const handleEdit = (record: ServiceEntity) => {
    if (isIntegrationSystem(record)) {
      void navigate(`/services/systems/${record.id}/specificationGroups`);
    }
  };

  const handleExpandAll = async () => {
    const roots = services;

    const groupsMap: Record<string, SpecificationGroup[]> = {
      ...specGroupsByService,
    };
    await Promise.all(
      roots.map(async (service) => {
        if (!groupsMap[service.id]) {
          try {
            const groups = await api.getApiSpecifications(service.id);
            groupsMap[service.id] = groups;
            setSpecGroupsByService((prev) => ({
              ...prev,
              [service.id]: groups,
            }));
          } catch (e: unknown) {
            notificationService.requestFailed(
              getErrorMessage(e, "Error loading specifications groups"),
              e,
            );
          }
        }
      }),
    );

    const specsMap: Record<string, Specification[]> = { ...specsByGroup };
    const allGroups = Object.values(groupsMap).flat();

    await Promise.all(
      allGroups.map((group) => {
        if (!specsMap[group.id] && group.specifications) {
          specsMap[group.id] = group.specifications;
          setSpecsByGroup((prev) => ({
            ...prev,
            [group.id]: group.specifications,
          }));
        }
      }),
    );

    const rootIds = roots.map((r) => r.id);
    const groupIds = allGroups.map((g) => g.id);
    const specIds = allGroups.flatMap((g) =>
      g.specifications ? g.specifications.map((s) => s.id) : [],
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

  const handleAddSpecificationGroup = useCallback(
    (record: ServiceEntity) => {
      if (!isIntegrationSystem(record)) return;
      showModal({
        component: (
          <ImportSpecificationsModal
            systemId={record.id}
            groupMode={true}
            isImplementedService={
              record.type === IntegrationSystemType.IMPLEMENTED
            }
            onSuccess={() => {
              void loadServices();
              invalidateServiceCache(record.id);
            }}
          />
        ),
      });
    },
    [showModal, loadServices],
  );

  const actionsColumn: ServicesTableColumn = getActionsColumn(
    getServiceActions({
      onEdit: handleEdit,
      onDelete: handleDeleteWithConfirm,
      onExpandAll: () => {
        void handleExpandAll();
      },
      onCollapseAll: handleCollapseAll,
      isRootEntity,
      isExpandAvailable: () => true,
      onExportSelected: (selected) => {
        void handleExportSelected(selected);
      },
      onAddSpecificationGroup: handleAddSpecificationGroup,
    }),
  );

  const rowClassName = (record: ServiceEntity) =>
    loadingRows.includes(record.id) ? styles.loadingRow : "";

  const servicesTable = useServicesTreeTable<ServiceEntity>({
    dataSource: buildDataSource,
    rowKey: "id",
    columns: allServicesTreeTableColumns.map((col) => col.key),
    className: "flex-table",
    style: { flex: 1, minHeight: 0 },
    allColumns: [
      ...allServicesTreeTableColumns.map((col) => col.key),
      actionsColumn.key,
    ],
    defaultVisibleKeys: visibleColumns,
    storageKey: STORAGE_KEY,
    loading,
    expandable,
    actionsColumn,
    enableSelection: true,
    isRootEntity,
    onExportSelected: (selected) => {
      void handleExportSelected(selected);
    },
    selectedRowKeys,
    onSelectedRowKeysChange: (keys) => {
      setSelectedRowKeys(keys);
    },
    rowClassName,
    onUpdateLabels: async (record, labels) => {
      const updated: ServiceEntity = {
        ...record,
        labels: labels.map((name) => ({ name, technical: false })),
      };
      if (isIntegrationSystem(record)) {
        await api.updateService(record.id, updated as IntegrationSystem);
        await loadServices();
      } else if (isSpecificationGroup(record)) {
        const res = await api.updateApiSpecificationGroup(
          record.id,
          updated as SpecificationGroup,
        );
        setSpecGroupsByService((prev) => updateLabelsInMap(prev, res));
      } else if (isSpecification(record)) {
        const res = await api.updateSpecificationModel(
          record.id,
          updated as Specification,
        );
        setSpecsByGroup((prev) => updateLabelsInMap(prev, res));
      }
    },
    onRowClick: (record, event) => {
      const target = event.target as HTMLElement;
      if (
        target.closest("button") ||
        target.closest(".ant-dropdown") ||
        target.closest("input") ||
        target.closest("a") ||
        target.closest(".inline-edit-labels")
      ) {
        return;
      }
      if (isIntegrationSystem(record)) {
        void navigate(`/services/systems/${record.id}/specificationGroups`);
      } else if (isSpecificationGroup(record)) {
        setExpandedRowKeys((keys) =>
          keys.includes(record.id)
            ? keys.filter((k) => k !== record.id)
            : [...keys, record.id],
        );
        if (!specsByGroup[record.id]) {
          setLoadingRows((rows) => [...rows, record.id]);
          setSpecsByGroup((prev) => ({
            ...prev,
            [record.id]: record.specifications,
          }));
          setLoadingRows((rows) => rows.filter((id) => id !== record.id));
        }
      }
    },
  });

  const handleExportSelected = async (selected: ServiceEntity[]) => {
    if (!selected.length) {
      message.info("No services selected");
      return;
    }
    try {
      const systemIds = selected.map((s) => s.id);
      const file = await api.exportServices(systemIds, []);
      downloadFile(prepareFile(file));
    } catch (e: unknown) {
      notificationService.requestFailed(getErrorMessage(e, "Export error"), e);
    }
  };

  const handleCreate = useCallback(
    async (name: string, description: string) => {
      try {
        const type = getSystemType(tab);
        const service: IntegrationSystem | ContextSystem =
          type === IntegrationSystemType.CONTEXT
            ? await api.createContextService({ name, description })
            : await api.createService({ name, description, type });
        if (
          type === IntegrationSystemType.INTERNAL ||
          type === IntegrationSystemType.IMPLEMENTED
        ) {
          await api.createEnvironment(service.id, { name, address: "/" });
        }
        message.success("Service created");
        void navigate(
          `/services/${type === IntegrationSystemType.CONTEXT ? "context" : "systems"}/${service.id}/parameters`,
        );
      } catch (e: unknown) {
        notificationService.requestFailed(
          getErrorMessage(e, "Service creation error"),
          e,
        );
        throw e;
      }
    },
    [tab, navigate, notificationService],
  );

  const handleDelete = async (id: string) => {
    try {
      await api.deleteService(id);
      void loadServices();
      message.success("Service deleted");
    } catch (e: unknown) {
      notificationService.requestFailed(
        getErrorMessage(e, "Service deletion error"),
        e,
      );
    }
  };

  function updateLabelsInMap<
    T extends {
      id: string;
      labels?: import("../../api/apiTypes").EntityLabel[];
    },
  >(map: Record<string, T[]>, updated: T): Record<string, T[]> {
    const newMap = { ...map };
    Object.keys(newMap).forEach((key) => {
      newMap[key] = newMap[key].map((item) =>
        item.id === updated.id ? { ...item, labels: updated.labels } : item,
      );
    });
    return newMap;
  }

  const getSystemType = (tab: string): IntegrationSystemType => {
    return IntegrationSystemType[
      tab.toUpperCase() as keyof typeof IntegrationSystemType
    ];
  };

  return (
    <GenericServiceListPage
      title={`${capitalize(tab)} Services`}
      icon={
        <OverridableIcon
          name={(() => {
            switch (tab) {
              case "external":
                return "global";
              case "internal":
                return "cloud";
              case "implemented":
                return "cluster";
              default:
                return "table";
            }
          })()}
        />
      }
      extraActions={[filterButton, servicesTable.FilterButton()]}
      serviceType={getSystemType(tab)}
      onCreate={(name, description) => handleCreate(name, description)}
      onSearch={(value) => setSearchString(value)}
      onExport={() => {
        void (async () => {
          if (selectedRowKeys.length === 0) {
            message.info("No services selected");
            return;
          }
          const selected: ServiceEntity[] = buildDataSource.filter(
            (s: ServiceEntity) => selectedRowKeys.includes(s.id),
          );
          await handleExportSelected(selected);
        })();
      }}
      onImport={() => void loadServices()}
    >
      {servicesTable.tableElement}
    </GenericServiceListPage>
  );
};
