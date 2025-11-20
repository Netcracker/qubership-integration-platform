import React, { useEffect, useState, useMemo } from "react";
import { Flex, Spin, Button, FloatButton, Tooltip } from "antd";
import FloatButtonGroup from "antd/lib/float-button/FloatButtonGroup";
import { useNavigate, useParams } from "react-router-dom";
import { api } from "../../api/api";
import { SpecificationGroup, Specification } from "../../api/apiTypes";
import {
  useServicesTreeTable,
  ServiceEntity,
  isSystemOperation,
  isSpecification,
  isSpecificationGroup,
} from "./ServicesTreeTable";
import { getActionsColumn } from "./ServicesTreeTable";
import {  message } from "antd";
import { isVsCode } from "../../api/rest/vscodeExtensionApi.ts";
import { downloadFile } from '../../misc/download-utils';
import {invalidateServiceCache, prepareFile} from "./utils.tsx";
import { ImportSpecificationsModal } from "./ImportSpecificationsModal";
import { useModalsContext } from "../../Modals";
import { useAsyncRequest } from './useAsyncRequest';
import styles from "./Services.module.css";
import { useNotificationService } from "../../hooks/useNotificationService";
import { useServiceContext } from "./ServiceParametersPage";
import { IntegrationSystemType } from "../../api/apiTypes";
import { OverridableIcon } from "../../icons/IconProvider.tsx";

const STORAGE_KEY = "systemParameters";

const SpecificationGroupColumnsKeys = () => [
  "name",
  "usedBy",
  "status",
  "source",
  "labels",
];

const SpecificationColumnsKeys = () => [
  "name",
  "usedBy",
  "status",
  "source",
  "labels",
  "method",
  "url",
];

const OperationColumnKeys = () => [
  "name",
  "method",
  "url",
  "usedBy",
];

const getGroupActions = (
  expandedRowKeys: string[],
  setExpandedRowKeys: (keys: string[]) => void,
  refreshGroups: () => Promise<void>,
  notify: ReturnType<typeof useNotificationService>,
  showModal: (modal: { component: React.ReactNode }) => void,
  systemId: string,
  isImplementedService: boolean,
  loadModels: (systemId: string, groupId: string) => Promise<void>,
  loadGroups: (systemId: string) => Promise<void>
) => (record: ServiceEntity) => {
  if (isSpecification(record)) return [];
  const group = record as SpecificationGroup;
  return [
    {
      key: expandedRowKeys.includes(group.id) ? 'collapse' : 'expand',
      label: expandedRowKeys.includes(group.id) ? 'Collapse' : 'Expand',
      icon: expandedRowKeys.includes(group.id) ? <OverridableIcon name="up" /> : <OverridableIcon name="down" />,
      onClick: () => {
        setExpandedRowKeys(
          expandedRowKeys.includes(group.id)
            ? expandedRowKeys.filter((k) => k !== group.id)
            : [...expandedRowKeys, group.id]
        );
      },
    },
    {
      key: 'add',
      label: 'Add Specification',
      icon: <OverridableIcon name="plus" />,
      onClick: () => {
        showModal({
          component: (
            <ImportSpecificationsModal
              systemId={systemId}
              specificationGroupId={group.id}
              isImplementedService={isImplementedService}
              onSuccess={() => {
                void loadGroups(systemId);
                void loadModels(systemId, group.id);
                invalidateServiceCache(systemId);
              }}
            />
          ),
        });
      },
    },
    {
      key: 'delete',
      label: 'Delete',
      icon: <OverridableIcon name="delete" />,
      onClick: async () => {
        try {
          await api.deleteSpecificationGroup(group.id);
          message.success('Specification group deleted');
          await refreshGroups();
        } catch (e) {
          notify.requestFailed('Delete failed', e);
        }
      },
      confirm: {
        title: 'Are you sure you want to permanently delete this specification group?',
        okText: 'Delete',
        cancelText: 'Cancel',
      },
    },
  ];
};

const getSpecActions = (
  expandedRowKeys: string[],
  setExpandedRowKeys: (keys: string[]) => void,
  refreshModels: () => Promise<void>,
  notify: ReturnType<typeof useNotificationService>
) => (record: ServiceEntity) => {
  if (isSystemOperation(record)) {return []}
  const spec = record as Specification;
  return [
    {
      key: expandedRowKeys.includes(spec.id) ? 'collapse' : 'expand',
      label: expandedRowKeys.includes(spec.id) ? 'Collapse' : 'Expand',
      icon: expandedRowKeys.includes(spec.id) ? <OverridableIcon name="up" /> : <OverridableIcon name="down" />,
      onClick: () => {
        setExpandedRowKeys(
          expandedRowKeys.includes(spec.id)
            ? expandedRowKeys.filter((k) => k !== spec.id)
            : [...expandedRowKeys, spec.id]
        );
      },
    },
    ...(spec.deprecated === true
      ? [{
          key: 'delete',
          label: 'Delete',
          icon: <OverridableIcon name="delete" />,
          onClick: async () => {
            try {
              await api.deleteSpecificationModel(spec.id);
              message.success('Specification deleted');
              await refreshModels();
            } catch (e) {
              notify.requestFailed('Delete failed', e);
            }
          },
          confirm: {
            title: 'Delete this specification?',
            okText: 'Delete',
            cancelText: 'Cancel',
          },
        }]
      : []),
    ...(spec.deprecated !== true
      ? [{
          key: 'deprecate',
          label: 'Deprecate',
          icon: <OverridableIcon name="stop" />,
          onClick: async () => {
            try {
              await api.deprecateModel(spec.id);
              message.success('Specification deprecated');
              await refreshModels();
            } catch (e) {
              notify.requestFailed('Deprecate failed', e);
            }
          },
          confirm: {
            title: 'Deprecate this specification?',
            okText: 'Deprecate',
            cancelText: 'Cancel',
          },
        }]
      : []),
    {
      key: 'export',
      label: 'Export',
      icon: <OverridableIcon name="export" />,
      onClick: () => handleExportSpecifications([spec], notify),
    },
  ];
};

async function handleExportSpecifications(selected: Specification[], notify: ReturnType<typeof useNotificationService>) {
  return (async () => {
    if (!selected.length) {
      message.info('There are no selected specifications');
      return;
    }
    try {
      const ids = selected.map((s) => s.id);
      const groupIds = Array.from(new Set(selected.map((s) => s.specificationGroupId).filter(Boolean)));
      const file = await api.exportSpecifications(ids, groupIds);
      downloadFile(prepareFile(file));
    } catch (e) {
      notify.requestFailed('Export error', e);
    }
  })();
}

type TableType = "groups" | "specs" | "operations";

export const ServiceApiSpecsTab: React.FC = () => {
  const navigate = useNavigate();
  const { systemId, groupId, specId } = useParams<{
    systemId: string;
    groupId?: string;
    specId?: string;
  }>();

  const [operations, setOperations] = useState<ServiceEntity[]>([]);
  const [expandedGroups, setExpandedGroups] = useState<string[]>([]);
  const [expandedSpecs, setExpandedSpecs] = useState<string[]>([]);
  const [selectedSpecRowKeys, setSelectedSpecRowKeys] = useState<React.Key[]>([]);
  const { showModal } = useModalsContext();
  const notify = useNotificationService();
  const system = useServiceContext();
  const isImplementedService = system?.type === IntegrationSystemType.IMPLEMENTED;

  const serviceSpecColumnsKeys = SpecificationGroupColumnsKeys();
  const specificationColumnsKeys = SpecificationColumnsKeys();
  const operationColumnKeys = OperationColumnKeys();

  const {
    loading: loadingGroups,
    error: errorGroups,
    execute: loadGroups,
    value: serviceSpecData,
  } = useAsyncRequest(async (id: string) => {
    return await api.getApiSpecifications(id);
  }, { initialValue: [] });

  const {
    error: errorModels,
    execute: loadModels,
    value: models,
  } = useAsyncRequest(async (systemId: string, groupId: string) => {
    return await api.getSpecificationModel(systemId, groupId);
  }, { initialValue: [] });

  useEffect(() => {
    if (!systemId) return;
    void loadGroups(systemId);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [systemId]);

  useEffect(() => {
    if (!systemId || !groupId) return;
    void loadModels(systemId, groupId);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [systemId, groupId]);

  useEffect(() => {
    if (!specId || !(models ?? []).length) {
      setOperations([]);
      return;
    }
    const model = (models ?? []).find(m => m.id === specId);
    const ops = model?.operations;
    setOperations(Array.isArray(ops) ? ops : ops ? [ops] : []);
  }, [specId, models]);

  const serviceGroupsTable = useServicesTreeTable<ServiceEntity>({
    dataSource: (serviceSpecData ?? []).map(spec => {
      return { ...spec, children: spec.specifications || [] };
    }),
    rowKey: "id",
    columns: serviceSpecColumnsKeys,
    storageKey: STORAGE_KEY + "_groups",
    defaultVisibleKeys: serviceSpecColumnsKeys,
    loading: loadingGroups,
          expandable: {
        expandedRowKeys: expandedGroups,
        onExpand: (expanded, record) => {
          setExpandedGroups(keys => expanded ? [...keys, record.id] : keys.filter(k => k !== record.id));
        },
      rowExpandable: () => true,
      childrenColumnName: "children",
    },
    actionsColumn: getActionsColumn<ServiceEntity>(
      getGroupActions(expandedGroups, setExpandedGroups, async () => { await loadGroups(systemId!); }, notify, showModal, systemId!, isImplementedService, async (systemId: string, groupId: string) => { await loadModels(systemId, groupId); }, async (systemId: string) => { await loadGroups(systemId); })
    ),
    onUpdateLabels: async (record, labels) => {
      if (!systemId) return;
      if (isSpecificationGroup(record)) {
        await api.updateApiSpecificationGroup(record.id, { ...record, labels: labels.map(name => ({ name, technical: false })) });
        await loadGroups(systemId);
      }
    },
  });

  const modelsTable = useServicesTreeTable<ServiceEntity>({
    dataSource: (models ?? []).map(model => ({ ...model, children: model.operations })),
    rowKey: "id",
    columns: specificationColumnsKeys,
    storageKey: STORAGE_KEY + "_models",
    defaultVisibleKeys: specificationColumnsKeys,
    expandable: {
      expandedRowKeys: expandedSpecs,
      onExpand: (expanded, record) => {
        setExpandedSpecs(keys => expanded ? [...keys, record.id] : keys.filter(k => k !== record.id));
      },
      rowExpandable: () => true,
      childrenColumnName: "children",
    },
    actionsColumn: getActionsColumn<ServiceEntity>(
      getSpecActions(expandedSpecs, setExpandedSpecs, async () => { await loadModels(systemId!, groupId!); }, notify)
    ),
    enableSelection: true,
    isRootEntity: (record) => Array.isArray((record as Specification).operations),
    selectedRowKeys: selectedSpecRowKeys,
    onSelectedRowKeysChange: setSelectedSpecRowKeys,
    onExportSelected: (selected) => { void handleExportSpecifications(selected as Specification[], notify); },
    onUpdateLabels: async (record, labels) => {
      if (!systemId || !groupId) return;
      if (isSpecification(record)) {
        await api.updateSpecificationModel(record.id, { ...record, labels: labels.map(name => ({ name, technical: false })) });
        await loadModels(systemId, groupId);
      }
    },
  });

  const operationsTable = useServicesTreeTable<ServiceEntity>({
    dataSource: operations,
    rowKey: "id",
    columns: operationColumnKeys,
    allColumns: operationColumnKeys,
    storageKey: STORAGE_KEY + "_operations",
    defaultVisibleKeys: operationColumnKeys,
  });

  const currentTable = useMemo<TableType>(() => {
    if (specId) return "operations";
    if (groupId) return "specs";
    return "groups";
  }, [groupId, specId]);


  const goToTable = async (type: TableType, options?: { groupId?: string; specId?: string }) => {
    switch (type) {
      case "groups":
        if (!systemId) return;
        await loadGroups(systemId);
        void navigate(`/services/systems/${systemId}/specificationGroups`);
        break;
      case "specs":
        if (options?.groupId && systemId) {
          await loadModels(systemId, options.groupId);
          void navigate(`/services/systems/${systemId}/specificationGroups/${options.groupId}/specifications`);
        }
        break;
      case "operations":
        if (options?.groupId && options?.specId && systemId) {
          void navigate(`/services/systems/${systemId}/specificationGroups/${options.groupId}/specifications/${options.specId}`);
        }
        break;
      default:
        break;
    }
  };

  const css = styles as Record<string, string>;

  const onImportSpecGroupClick = () => {
    showModal({
      component: (
        <ImportSpecificationsModal
          systemId={systemId}
          groupMode={true}
          isImplementedService={isImplementedService}
          onSuccess={() => {
            if (!systemId) return;
            void loadGroups(systemId);
            invalidateServiceCache(systemId);
          }}
        />
      ),
    });
  };

  const onImportSpecClick = () => {
    showModal({
      component: (
        <ImportSpecificationsModal
          systemId={systemId}
          specificationGroupId={groupId}
          isImplementedService={isImplementedService}
          onSuccess={() => {
            if (!systemId || !groupId) return;
            void loadGroups(systemId);
            void loadModels(systemId, groupId);
            invalidateServiceCache(systemId);
          }}
        />
      ),
    });
  };

  return (
    <Flex vertical>
      <div className={css.serviceApiSpecsTabHeader}>
        <div className={css.serviceApiSpecsTabHeaderLeft}>
          {currentTable === "operations" && (
            <Button onClick={() => { void goToTable("specs", { groupId }); }}>
              To Specifications
            </Button>
          )}
          {currentTable === "specs" && (
            <Button onClick={() => { void goToTable("groups"); }}>
              To Specification Groups
            </Button>
          )}
        </div>
        <div className={css.serviceApiSpecsTabHeaderRight}>
          <span className={currentTable === "groups" ? css.serviceApiSpecsTabFilterGroup : css.serviceApiSpecsTabFilterNone}>
            <Flex vertical={false} gap={8}>
              {isVsCode && (
                <Tooltip title="Import Specifications">
                  <Button
                    icon={<OverridableIcon name="cloudUpload" />}
                    onClick={onImportSpecGroupClick}
                  />
                </Tooltip>
              )}
              {serviceGroupsTable.FilterButton()}
            </Flex>
          </span>
          <span className={currentTable === "specs" ? css.serviceApiSpecsTabFilterGroup : css.serviceApiSpecsTabFilterNone}>
            <Flex vertical={false} gap={8}>
              {isVsCode && (
                <Tooltip title="Import Specifications">
                  <Button
                    icon={<OverridableIcon name="cloudUpload" />}
                    onClick={onImportSpecClick}
                  />
                </Tooltip>
              )}
              {modelsTable.FilterButton()}
            </Flex>
          </span>
          <span className={currentTable === "operations" ? css.serviceApiSpecsTabFilterGroup : css.serviceApiSpecsTabFilterNone}>
            {operationsTable.FilterButton()}
          </span>
        </div>
      </div>
      {loadingGroups && <Spin className={css.serviceApiSpecsTabSpin} />}
      {errorGroups && <div className={css.serviceApiSpecsTabError}>Error: {errorGroups}</div>}
      {!loadingGroups && !errorGroups && currentTable === "groups" && (
        <>
          <serviceGroupsTable.Table />
          {!isVsCode && (
            <FloatButtonGroup trigger="hover" icon={<OverridableIcon name="more" />}>
              <FloatButton
                tooltip={{ title: "Import Specifications", placement: "left" }}
                icon={<OverridableIcon name="cloudUpload" />}
                onClick={onImportSpecGroupClick}
              />
              <FloatButton
                tooltip={{ title: "Export all groups", placement: "left" }}
                icon={<OverridableIcon name="cloudDownload" />}
                onClick={() => {
                  void (async () => {
                    if (!(serviceSpecData ?? []).length) {
                      message.info("No groups to export");
                      return;
                    }
                    const groupIds = (serviceSpecData ?? []).map((g) => g.id);
                    try {
                      const file = await api.exportServices([], groupIds);
                      downloadFile(prepareFile(file));
                    } catch (e) {
                      notify.requestFailed("Export error", e);
                    }
                  })();
                }}
              />
            </FloatButtonGroup>
          )}
        </>
      )}
      {currentTable === "specs" && (
        <>
          <modelsTable.Table />
          {!isVsCode && (
            <FloatButtonGroup trigger="hover" icon={<OverridableIcon name="more" />}>
              <FloatButton
                tooltip={{ title: "Import Specification", placement: "left" }}
                icon={<OverridableIcon name="cloudUpload" />}
                onClick={onImportSpecClick}
              />
              <FloatButton
                tooltip={{
                  title: "Export selected specifications",
                  placement: "left",
                }}
                icon={<OverridableIcon name="cloudDownload" />}
                onClick={() => {
                  void (async () => {
                    if (selectedSpecRowKeys.length === 0) {
                      message.info("There are no selected specifications yet");
                      return;
                    }
                    const selected = (models ?? []).filter((m) =>
                      selectedSpecRowKeys.includes(m.id),
                    );
                    await handleExportSpecifications(selected, notify);
                  })();
                }}
              />
            </FloatButtonGroup>
          )}
        </>
      )}
      {currentTable === "operations" && (
        <>
          <operationsTable.Table />
          {!isVsCode && (
            <FloatButtonGroup trigger="hover" icon={<OverridableIcon name="more" />}>
              <FloatButton
                tooltip={{ title: "Export", placement: "left" }}
                icon={<OverridableIcon name="cloudDownload" />}
                onClick={() => {
                  void (async () => {
                    try {
                      if (!specId) {
                        message.info('No model to export');
                        return;
                      }
                      const model = (models ?? []).find(m => m.id === specId);
                      if (!model) {
                        message.info('No model to export');
                        return;
                      }
                      const file = await api.exportServices([specId], []);
                      downloadFile(prepareFile(file));
                    } catch (e) {
                      notify.requestFailed('Export error', e);
                    }
                  })();
                }}
              />
            </FloatButtonGroup>
          )}
        </>
      )}
      {errorModels && <div className={css.serviceApiSpecsTabError}>Error: {errorModels}</div>}
    </Flex>
  );
};
