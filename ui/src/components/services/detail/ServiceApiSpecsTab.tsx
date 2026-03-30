import React, { useEffect, useState, useMemo, useRef } from "react";
import { Flex, Spin, Button } from "antd";
import { useLocation, useNavigate, useParams } from "react-router-dom";
import { api } from "../../../api/api";
import { SpecificationGroup, Specification } from "../../../api/apiTypes";
import {
  useServicesTreeTable,
  ServiceEntity,
  isSystemOperation,
  isSpecification,
  isSpecificationGroup,
  ActionConfig,
} from "../ServicesTreeTable";
import { getActionsColumn } from "../ServicesTreeTable";
import { message } from "antd";
import { isVsCode } from "../../../api/rest/vscodeExtensionApi.ts";
import { downloadFile } from "../../../misc/download-utils";
import { invalidateServiceCache, prepareFile } from "../utils.tsx";
import { ImportSpecificationsModal } from "../modals/ImportSpecificationsModal";
import { useModalsContext } from "../../../Modals";
import { useAsyncRequest } from "../useAsyncRequest";
import styles from "../Services.module.css";
import { useNotificationService } from "../../../hooks/useNotificationService";
import {
  useServiceContext,
  useServiceParametersToolbar,
} from "./ServiceParametersPage";
import { IntegrationSystemType } from "../../../api/apiTypes";
import { OverridableIcon } from "../../../icons/IconProvider.tsx";
import { ProtectedButton } from "../../../permissions/ProtectedButton.tsx";
import { endpointColumnTitleForProtocol } from "../endpointColumnTitle.ts";

const STORAGE_KEY = "systemParameters";

const specificationGroupAllColumnKeys = () => [
  "name",
  "usedBy",
  "status",
  "source",
  "labels",
  "createdWhen",
  "createdBy",
  "modifiedWhen",
  "modifiedBy",
];

const specificationGroupDefaultVisibleKeys = () => [
  "name",
  "usedBy",
  "status",
  "source",
  "labels",
];

const specificationModelAllColumnKeys = () => [
  "name",
  "usedBy",
  "status",
  "source",
  "labels",
  "method",
  "url",
  "createdWhen",
  "createdBy",
  "modifiedWhen",
  "modifiedBy",
];

const specificationModelDefaultVisibleKeys = () => [
  "name",
  "usedBy",
  "status",
  "source",
  "labels",
  "method",
  "url",
];

const operationAllColumnKeys = () => [
  "name",
  "method",
  "url",
  "usedBy",
  "createdWhen",
  "createdBy",
  "modifiedWhen",
  "modifiedBy",
];

const buildOperationDefaultVisibleKeys = () => [
  "name",
  "method",
  "url",
  "usedBy",
];

const getGroupActions =
  (
    expandedRowKeys: string[],
    setExpandedRowKeys: (keys: string[]) => void,
    refreshGroups: () => Promise<void>,
    notify: ReturnType<typeof useNotificationService>,
    showModal: (modal: { component: React.ReactNode }) => void,
    systemId: string,
    isImplementedService: boolean,
    loadModels: (systemId: string, groupId: string) => Promise<void>,
    loadGroups: (systemId: string) => Promise<void>,
  ) =>
  (record: ServiceEntity): ActionConfig<ServiceEntity>[] => {
    if (isSpecification(record)) return [];
    const group = record as SpecificationGroup;
    return [
      {
        key: expandedRowKeys.includes(group.id) ? "collapse" : "expand",
        label: expandedRowKeys.includes(group.id) ? "Collapse" : "Expand",
        icon: expandedRowKeys.includes(group.id) ? (
          <OverridableIcon name="up" />
        ) : (
          <OverridableIcon name="down" />
        ),
        onClick: () => {
          setExpandedRowKeys(
            expandedRowKeys.includes(group.id)
              ? expandedRowKeys.filter((k) => k !== group.id)
              : [...expandedRowKeys, group.id],
          );
        },
      },
      {
        key: "add",
        label: "Add Specification",
        icon: <OverridableIcon name="plus" />,
        require: { specificationGroup: ["create"] },
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
        key: "delete",
        label: "Delete",
        icon: <OverridableIcon name="delete" />,
        require: { specificationGroup: ["delete"] },
        onClick: () =>
          void (async () => {
            try {
              await api.deleteSpecificationGroup(group.id);
              message.success("Specification group deleted");
              await refreshGroups();
            } catch (e) {
              notify.requestFailed("Delete failed", e);
            }
          })(),
        confirm: {
          title:
            "Are you sure you want to permanently delete this specification group?",
          okText: "Delete",
          cancelText: "Cancel",
        },
      },
    ];
  };

const getSpecActions =
  (
    expandedRowKeys: string[],
    setExpandedRowKeys: (keys: string[]) => void,
    refreshModels: () => Promise<void>,
    notify: ReturnType<typeof useNotificationService>,
  ) =>
  (record: ServiceEntity): ActionConfig<ServiceEntity>[] => {
    if (isSystemOperation(record)) {
      return [];
    }
    const spec = record as Specification;
    return [
      {
        key: expandedRowKeys.includes(spec.id) ? "collapse" : "expand",
        label: expandedRowKeys.includes(spec.id) ? "Collapse" : "Expand",
        icon: expandedRowKeys.includes(spec.id) ? (
          <OverridableIcon name="up" />
        ) : (
          <OverridableIcon name="down" />
        ),
        onClick: () => {
          setExpandedRowKeys(
            expandedRowKeys.includes(spec.id)
              ? expandedRowKeys.filter((k) => k !== spec.id)
              : [...expandedRowKeys, spec.id],
          );
        },
      },
      ...(spec.deprecated === true
        ? [
            {
              key: "delete",
              label: "Delete",
              icon: <OverridableIcon name="delete" />,
              require: { specification: ["delete" as const] },
              onClick: async () => {
                try {
                  await api.deleteSpecificationModel(spec.id);
                  message.success("Specification deleted");
                  await refreshModels();
                } catch (e) {
                  notify.requestFailed("Delete failed", e);
                }
              },
              confirm: {
                title: "Delete this specification?",
                okText: "Delete",
                cancelText: "Cancel",
              },
            },
          ]
        : []),
      ...(spec.deprecated !== true
        ? [
            {
              key: "deprecate",
              label: "Deprecate",
              icon: <OverridableIcon name="stop" />,
              require: { specification: ["execute" as const] },
              onClick: async () => {
                try {
                  await api.deprecateModel(spec.id);
                  message.success("Specification deprecated");
                  await refreshModels();
                } catch (e) {
                  notify.requestFailed("Deprecate failed", e);
                }
              },
              confirm: {
                title: "Deprecate this specification?",
                okText: "Deprecate",
                cancelText: "Cancel",
              },
            },
          ]
        : []),
      {
        key: "export",
        label: "Export",
        icon: <OverridableIcon name="export" />,
        require: { specification: ["export" as const] },
        onClick: () => void handleExportSpecifications([spec], notify),
      },
    ];
  };

async function handleExportSpecifications(
  selected: Specification[],
  notify: ReturnType<typeof useNotificationService>,
) {
  return (async () => {
    if (!selected.length) {
      message.info("There are no selected specifications");
      return;
    }
    try {
      const ids = selected.map((s) => s.id);
      const groupIds = Array.from(
        new Set(selected.map((s) => s.specificationGroupId).filter(Boolean)),
      );
      const file = await api.exportSpecifications(ids, groupIds);
      downloadFile(file.name ? file : prepareFile(file));
    } catch (e) {
      notify.requestFailed("Export error", e);
    }
  })();
}

type TableType = "groups" | "specs" | "operations";

export const ServiceApiSpecsTab: React.FC = () => {
  const navigate = useNavigate();
  const location = useLocation();
  const { systemId, groupId, specId } = useParams<{
    systemId: string;
    groupId?: string;
    specId?: string;
  }>();

  const [operations, setOperations] = useState<ServiceEntity[]>([]);
  const [expandedGroups, setExpandedGroups] = useState<string[]>([]);
  const [expandedSpecs, setExpandedSpecs] = useState<string[]>([]);
  const [selectedSpecRowKeys, setSelectedSpecRowKeys] = useState<React.Key[]>(
    [],
  );
  const { showModal } = useModalsContext();
  const notify = useNotificationService();
  const system = useServiceContext();
  const { setToolbar } = useServiceParametersToolbar() ?? {};
  const isApiSpecsActive = location.pathname.includes("/specificationGroups");
  const isImplementedService =
    system?.type === IntegrationSystemType.IMPLEMENTED;
  const toolbarSignatureRef = useRef<string>("");

  const serviceSpecColumnsKeys = specificationGroupAllColumnKeys();
  const serviceSpecDefaultVisibleKeys = specificationGroupDefaultVisibleKeys();
  const specificationColumnsKeys = specificationModelAllColumnKeys();
  const specificationDefaultVisibleKeys =
    specificationModelDefaultVisibleKeys();
  const operationColumnKeys = operationAllColumnKeys();
  const operationVisibleKeys = buildOperationDefaultVisibleKeys();

  const {
    loading: loadingGroups,
    error: errorGroups,
    execute: loadGroups,
    value: serviceSpecData,
  } = useAsyncRequest(
    async (id: string) => {
      return await api.getApiSpecifications(id);
    },
    { initialValue: [] },
  );

  const {
    error: errorModels,
    execute: loadModels,
    value: models,
  } = useAsyncRequest(
    async (systemId: string, groupId: string) => {
      return await api.getSpecificationModel(systemId, groupId);
    },
    { initialValue: [] },
  );

  useEffect(() => {
    if (!systemId) return;
    void loadGroups(systemId);
  }, [systemId]);

  useEffect(() => {
    if (!systemId || !groupId) return;
    void loadModels(systemId, groupId);
  }, [systemId, groupId]);

  useEffect(() => {
    if (!specId || !(models ?? []).length) {
      setOperations([]);
      return;
    }
    const model = (models ?? []).find((m) => m.id === specId);
    const ops = model?.operations;
    setOperations(Array.isArray(ops) ? ops : ops ? [ops] : []);
  }, [specId, models]);

  const serviceGroupsTable = useServicesTreeTable<ServiceEntity>({
    dataSource: (serviceSpecData ?? []).map((spec) => {
      return { ...spec, children: spec.specifications || [] };
    }),
    rowKey: "id",
    columns: serviceSpecColumnsKeys,
    storageKey: STORAGE_KEY + "_groups",
    defaultVisibleKeys: serviceSpecDefaultVisibleKeys,
    loading: loadingGroups,
    expandable: {
      expandedRowKeys: expandedGroups,
      onExpand: (expanded, record) => {
        setExpandedGroups((keys) =>
          expanded ? [...keys, record.id] : keys.filter((k) => k !== record.id),
        );
      },
      rowExpandable: () => true,
      childrenColumnName: "children",
    },
    actionsColumn: getActionsColumn<ServiceEntity>(
      getGroupActions(
        expandedGroups,
        setExpandedGroups,
        async () => {
          await loadGroups(systemId!);
        },
        notify,
        showModal,
        systemId!,
        isImplementedService,
        async (systemId: string, groupId: string) => {
          await loadModels(systemId, groupId);
        },
        async (systemId: string) => {
          await loadGroups(systemId);
        },
      ),
    ),
    onUpdateLabels: async (record, labels) => {
      if (!systemId) return;
      if (isSpecificationGroup(record)) {
        await api.updateApiSpecificationGroup(record.id, {
          ...record,
          labels: labels.map((name) => ({ name, technical: false })),
        });
        await loadGroups(systemId);
      }
    },
  });

  const modelsTable = useServicesTreeTable<ServiceEntity>({
    dataSource: (models ?? []).map((model) => ({
      ...model,
      children: model.operations,
    })),
    rowKey: "id",
    columns: specificationColumnsKeys,
    storageKey: STORAGE_KEY + "_models",
    defaultVisibleKeys: specificationDefaultVisibleKeys,
    expandable: {
      expandedRowKeys: expandedSpecs,
      onExpand: (expanded, record) => {
        setExpandedSpecs((keys) =>
          expanded ? [...keys, record.id] : keys.filter((k) => k !== record.id),
        );
      },
      rowExpandable: () => true,
      childrenColumnName: "children",
    },
    actionsColumn: getActionsColumn<ServiceEntity>(
      getSpecActions(
        expandedSpecs,
        setExpandedSpecs,
        async () => {
          await loadModels(systemId!, groupId!);
        },
        notify,
      ),
    ),
    enableSelection: true,
    isRootEntity: (record) =>
      Array.isArray((record as Specification).operations),
    selectedRowKeys: selectedSpecRowKeys,
    onSelectedRowKeysChange: setSelectedSpecRowKeys,
    onExportSelected: (selected) => {
      void handleExportSpecifications(selected as Specification[], notify);
    },
    onUpdateLabels: async (record, labels) => {
      if (!systemId || !groupId) return;
      if (isSpecification(record)) {
        await api.updateSpecificationModel(record.id, {
          ...record,
          labels: labels.map((name) => ({ name, technical: false })),
        });
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
    defaultVisibleKeys: operationVisibleKeys,
    urlColumnTitle: endpointColumnTitleForProtocol(system?.protocol),
  });

  const currentTable = useMemo<TableType>(() => {
    if (specId) return "operations";
    if (groupId) return "specs";
    return "groups";
  }, [groupId, specId]);

  const goToTable = async (
    type: TableType,
    options?: { groupId?: string; specId?: string },
  ) => {
    switch (type) {
      case "groups":
        if (!systemId) return;
        await loadGroups(systemId);
        void navigate(`/services/systems/${systemId}/specificationGroups`);
        break;
      case "specs":
        if (options?.groupId && systemId) {
          await loadModels(systemId, options.groupId);
          void navigate(
            `/services/systems/${systemId}/specificationGroups/${options.groupId}/specifications`,
          );
        }
        break;
      case "operations":
        if (options?.groupId && options?.specId && systemId) {
          void navigate(
            `/services/systems/${systemId}/specificationGroups/${options.groupId}/specifications/${options.specId}`,
          );
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

  const toolbarContent = useMemo(
    () => (
      <>
        <div className={css.serviceApiSpecsTabHeaderLeft}>
          {currentTable === "operations" && (
            <Button
              onClick={() => {
                void goToTable("specs", { groupId });
              }}
            >
              To Specifications
            </Button>
          )}
          {currentTable === "specs" && (
            <Button
              onClick={() => {
                void goToTable("groups");
              }}
            >
              To Specification Groups
            </Button>
          )}
        </div>
        <div
          className={css.serviceApiSpecsTabHeaderRight}
          style={{ marginLeft: "auto" }}
        >
          <span
            className={
              currentTable === "groups"
                ? css.serviceApiSpecsTabFilterGroup
                : css.serviceApiSpecsTabFilterNone
            }
          >
            <Flex vertical={false} gap={4}>
              <ProtectedButton
                require={{ specificationGroup: ["import"] }}
                tooltipProps={{ title: "Import Specifications" }}
                buttonProps={{
                  iconName: "cloudUpload",
                  onClick: onImportSpecGroupClick,
                }}
              />
              {!isVsCode && (
                <ProtectedButton
                  require={{ service: ["export"] }}
                  tooltipProps={{
                    title: "Export service",
                    placement: "bottom",
                  }}
                  buttonProps={{
                    iconName: "cloudDownload",
                    onClick: () => {
                      void (async () => {
                        if (!systemId) return;
                        try {
                          const file = await api.exportServices([systemId], []);
                          downloadFile(prepareFile(file));
                        } catch (e) {
                          notify.requestFailed("Export error", e);
                        }
                      })();
                    },
                  }}
                />
              )}
              {serviceGroupsTable.FilterButton()}
            </Flex>
          </span>
          <span
            className={
              currentTable === "specs"
                ? css.serviceApiSpecsTabFilterGroup
                : css.serviceApiSpecsTabFilterNone
            }
          >
            <Flex vertical={false} gap={4}>
              <ProtectedButton
                require={{ specification: ["import"] }}
                tooltipProps={{ title: "Import Specification" }}
                buttonProps={{
                  iconName: "cloudUpload",
                  onClick: onImportSpecClick,
                }}
              />
              {!isVsCode && (
                <ProtectedButton
                  require={{ specification: ["export"] }}
                  tooltipProps={{
                    title: "Export selected specifications",
                    placement: "bottom",
                  }}
                  buttonProps={{
                    iconName: "cloudDownload",
                    onClick: () => {
                      void (async () => {
                        if (selectedSpecRowKeys.length === 0) {
                          message.info(
                            "There are no selected specifications yet",
                          );
                          return;
                        }
                        const selected = (models ?? []).filter((m) =>
                          selectedSpecRowKeys.includes(m.id),
                        );
                        await handleExportSpecifications(selected, notify);
                      })();
                    },
                  }}
                />
              )}
              {modelsTable.FilterButton()}
            </Flex>
          </span>
          <span
            className={
              currentTable === "operations"
                ? css.serviceApiSpecsTabFilterGroup
                : css.serviceApiSpecsTabFilterNone
            }
          >
            <Flex vertical={false} gap={4}>
              {!isVsCode && (
                <ProtectedButton
                  require={{ specification: ["export"] }}
                  tooltipProps={{
                    title: "Export specification",
                    placement: "bottom",
                  }}
                  buttonProps={{
                    iconName: "cloudDownload",
                    onClick: () => {
                      void (async () => {
                        try {
                          if (!specId) {
                            message.info("No model to export");
                            return;
                          }
                          const model = (models ?? []).find(
                            (m) => m.id === specId,
                          );
                          if (!model) {
                            message.info("No model to export");
                            return;
                          }
                          const file = await api.exportSpecifications(
                            [specId],
                            [],
                          );
                          downloadFile(file.name ? file : prepareFile(file));
                        } catch (e) {
                          notify.requestFailed("Export error", e);
                        }
                      })();
                    },
                  }}
                />
              )}
              {operationsTable.FilterButton()}
            </Flex>
          </span>
        </div>
      </>
    ),
    [
      currentTable,
      groupId,
      systemId,
      specId,
      models,
      selectedSpecRowKeys,
      goToTable,
      onImportSpecGroupClick,
      onImportSpecClick,
      serviceGroupsTable,
      modelsTable,
      operationsTable,
      notify,
      css,
    ],
  );

  useEffect(() => {
    if (!setToolbar) return;
    if (!isApiSpecsActive) {
      toolbarSignatureRef.current = "";
      setToolbar(null);
      return;
    }

    const signature = [
      currentTable,
      groupId ?? "",
      specId ?? "",
      systemId ?? "",
      String(selectedSpecRowKeys.length),
      String(models?.length ?? 0),
    ].join("|");

    if (toolbarSignatureRef.current === signature) {
      return;
    }

    toolbarSignatureRef.current = signature;
    setToolbar(
      <Flex align="center" gap={8} style={{ flexWrap: "wrap" }}>
        {toolbarContent}
      </Flex>,
    );
  }, [
    setToolbar,
    isApiSpecsActive,
    currentTable,
    groupId,
    specId,
    systemId,
    selectedSpecRowKeys.length,
    models?.length,
    toolbarContent,
  ]);

  useEffect(() => {
    return () => {
      if (!setToolbar) return;
      toolbarSignatureRef.current = "";
      setToolbar(null);
    };
  }, [setToolbar]);

  return (
    <Flex vertical>
      {loadingGroups && <Spin className={css.serviceApiSpecsTabSpin} />}
      {errorGroups && (
        <div className={css.serviceApiSpecsTabError}>Error: {errorGroups}</div>
      )}
      {!loadingGroups &&
        !errorGroups &&
        currentTable === "groups" &&
        serviceGroupsTable.tableElement}
      {currentTable === "specs" && modelsTable.tableElement}
      {currentTable === "operations" && operationsTable.tableElement}
      {errorModels && (
        <div className={css.serviceApiSpecsTabError}>Error: {errorModels}</div>
      )}
    </Flex>
  );
};
