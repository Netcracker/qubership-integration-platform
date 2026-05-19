import React, { useCallback, useEffect, useState } from "react";
import { Button, message, Modal, Table, TableProps } from "antd";
import { useNavigate } from "react-router-dom";
import { api } from "../../../api/api";
import { IntegrationSystemType } from "../../../api/apiTypes";
import { useNotificationService } from "../../../hooks/useNotificationService";
import { ServiceEntity } from "./../ServicesTreeTable";
import type { ContextSystem } from "../../../api/apiTypes";
import { downloadFile } from "../../../misc/download-utils";
import { prepareFile } from "./../utils.tsx";
import { getErrorMessage } from "../../../misc/error-utils";
import { OverridableIcon } from "../../../icons/IconProvider.tsx";
import { GenericServiceListPage } from "./../GenericServiceListPage.tsx";
import { useAsyncRequest } from "../useAsyncRequest.ts";
import { useContextServiceFilters } from "../../../hooks/useContextServiceFilter.ts";
import { createActionsColumnBase } from "../../table/actionsColumn.ts";
import { Require } from "../../../permissions/Require.tsx";
import { EntityLabels } from "../../labels/EntityLabels.tsx";
import { InlineEdit } from "../../InlineEdit.tsx";
import { LabelsEdit } from "../../table/LabelsEdit.tsx";
import { formatOptional, formatTimestamp } from "../../../misc/format-utils.ts";
import { ProtectedDropdown } from "../../../permissions/ProtectedDropdown.tsx";
import { useColumnSettingsBasedOnColumnsType } from "../../table/useColumnSettingsButton.tsx";
import { ChainColumn } from "../ui/ChainColumn.tsx";
import { useColumnsWithResizeAndScroll } from "../../table/useColumnsWithResizeAndScroll.tsx";

const SELECTION_COLUMN_WIDTH = 48;

export const ContextServiceList: React.FC = () => {
  const [services, setServices] = useState<ContextSystem[]>([]);
  const [selectedRowKeys, setSelectedRowKeys] = useState<React.Key[]>([]);
  const [searchString, setSearchString] = useState("");
  const notificationService = useNotificationService();
  const navigate = useNavigate();
  const { filters, filterButton } = useContextServiceFilters();

  const { errorObject: loadingError, loading, execute: loadServices } = useAsyncRequest(
    async () => {
      const hasSearch = searchString.trim().length > 0;
      const hasFilters = filters.length > 0;

      let servicesArray: ContextSystem[];
      if (hasSearch && hasFilters) {
        const [searched, filtered] = await Promise.all([
          api.searchContextServices(searchString.trim()),
          api.filterContextServices(filters),
        ]);
        const filteredIds = new Set(filtered.map((s) => s.id));
        servicesArray = searched.filter((s) => filteredIds.has(s.id));
      } else if (hasFilters) {
        servicesArray = await api.filterContextServices(filters);
      } else if (hasSearch) {
        servicesArray = await api.searchContextServices(searchString.trim());
      } else {
        const all = await api.getContextServices();
        servicesArray = Array.isArray(all) ? all : [];
      }

      setServices(servicesArray);
    },
    { initialValue: undefined },
  );

  useEffect(() => {
    if (loadingError) {
      notificationService.requestFailed("Failed to load context services", loadingError);
    }
  }, [loadingError, notificationService]);

  const columns: TableProps<ContextSystem>["columns"] = [
    {
      title: "Name",
      dataIndex: "name",
      key: "name",
      width: 200,
      minWidth: 120,
      render: (_: unknown, system) => (
        <a
          href={`/services/context/${system.id}/parameters`}
          onClick={(event) => {
            event.preventDefault();
            event.stopPropagation();
            void navigate(`/services/context/${system.id}/parameters`);
          }}
        >
          {system.name}
        </a>
      ),
    },
    {
      title: "Labels",
      dataIndex: "labels",
      key: "labels",
      width: 200,
      render: (_: unknown, system) => (
        <Require
          permissions={{ service: ["update"] }}
          fallback={<EntityLabels labels={system.labels ?? []} />}
        >
          <InlineEdit<{ labels: string[] }>
            values={{
              labels:
                system.labels?.filter((l) => !l.technical).map((l) => l.name) ??
                [],
            }}
            editor={<LabelsEdit name={"labels"} />}
            viewer={<EntityLabels labels={system.labels ?? []} />}
            onSubmit={async ({ labels }) => {
              await handleUpdate(system.id, {
                labels: labels.map((name) => ({ name, technical: false })),
              });
            }}
          />
        </Require>
      ),
    },
    {
      title: "Used by",
      dataIndex: "usedBy",
      key: "usedBy",
      width: 120,
      render: (_: unknown, system) => (
        <ChainColumn chains={system.chains ?? []} />
      ),
    },
    {
      title: "Created At",
      dataIndex: "createdWhen",
      key: "createdWhen",
      width: 160,
      render: (_: unknown, system) => formatTimestamp(system.createdWhen),
      hidden: true,
    },
    {
      title: "Created By",
      dataIndex: "createdBy",
      key: "createdBy",
      width: 130,
      render: (_: unknown, system) =>
        formatOptional(system.createdBy?.username),
      hidden: true,
    },
    {
      title: "Modified At",
      dataIndex: "modifiedWhen",
      key: "modifiedWhen",
      width: 160,
      render: (_: unknown, system) => formatTimestamp(system.modifiedWhen),
      hidden: true,
    },
    {
      title: "Modified By",
      dataIndex: "modifiedBy",
      key: "modifiedBy",
      width: 130,
      render: (_: unknown, system) =>
        formatOptional(system.modifiedBy?.username),
      hidden: true,
    },
    {
      ...createActionsColumnBase<ContextSystem>(),
      render: (_: unknown, system) => (
        <ProtectedDropdown
          menu={{
            items: [
              {
                key: "edit",
                label: "Edit",
                icon: <OverridableIcon name="edit" />,
                onClick: () => handleEdit(system),
                require: { service: ["update"] },
              },
              {
                key: "delete",
                label: "Delete",
                icon: <OverridableIcon name={"delete"} />,
                onClick: () => {
                  Modal.confirm({
                    title: "Are you sure you want to delete this service?",
                    okText: "Delete",
                    cancelText: "Cancel",
                    onOk: () => void handleDelete(system.id),
                  });
                },
                require: { service: ["delete"] },
              },
              {
                key: "export",
                label: "Export",
                icon: <OverridableIcon name={"export"} />,
                onClick: () => void handleExportSelected([system]),
                require: { service: ["export"] },
              },
            ],
          }}
          trigger={["click"]}
          placement="bottomRight"
        >
          <Button
            size="small"
            type="text"
            icon={<OverridableIcon name="more" />}
          />
        </ProtectedDropdown>
      ),
    },
  ];

  const { orderedColumns, columnSettingsButton } =
    useColumnSettingsBasedOnColumnsType("contextSystemTable", columns);

  const { columnResize, columnsWithResize, scrollX } =
    useColumnsWithResizeAndScroll(
      orderedColumns,
      {
        name: 200,
        labels: 200,
        usedBy: 120,
        createdBy: 120,
        createdWhen: 168,
        modifiedBy: 120,
        modifiedWhen: 168,
      },
      SELECTION_COLUMN_WIDTH,
    );

  useEffect(() => {
    void loadServices();
  }, [filters, searchString]);

  const handleEdit = (record: ServiceEntity) => {
    void navigate(`/services/context/${record.id}/parameters`);
  };

  const handleUpdate = async (id: string, changes: Partial<ContextSystem>) => {
    try {
      const system = await api.updateContextService(id, changes);
      setServices((values) => values.map((s) => (s.id === id ? system : s)));
    } catch (e) {
      notificationService.requestFailed("Failed to update service", e);
    }
  };

  const handleDelete = useCallback(
    async (id: string) => {
      try {
        await api.deleteContextService(id);
        message.success("Service deleted");
        setServices((services) => services.filter((s) => s.id !== id));
      } catch (e: unknown) {
        notificationService.requestFailed("Failed to delete service", e);
      }
    },
    [notificationService],
  );

  const handleExportSelected = async (selected: ServiceEntity[]) => {
    if (!selected.length) {
      message.info("No services selected");
      return;
    }
    try {
      const systemIds = selected.map((s) => s.id);
      const file = await api.exportContextServices(systemIds);
      downloadFile(prepareFile(file));
    } catch (e: unknown) {
      notificationService.requestFailed("Failed to export services", e);
    }
  };

  const handleCreate = useCallback(
    async (name: string, description: string) => {
      try {
        const service = await api.createContextService({ name, description });
        message.success("Service created");
        void navigate(`/services/context/${service.id}/parameters`);
      } catch (e: unknown) {
        notificationService.requestFailed(
          getErrorMessage(e, "Service creation error"),
          e,
        );
        throw e;
      }
    },
    [navigate, notificationService],
  );

  return (
    <GenericServiceListPage
      title={`Context Services`}
      icon={<OverridableIcon name={"database"} />}
      extraActions={[filterButton, columnSettingsButton]}
      serviceType={IntegrationSystemType.CONTEXT}
      onCreate={(name, description) => handleCreate(name, description)}
      onSearch={(value) => setSearchString(value)}
      onExport={() => {
        void (async () => {
          if (selectedRowKeys.length === 0) {
            message.info("No services selected");
            return;
          }
          const selected: ServiceEntity[] = services.filter(
            (s: ServiceEntity) => selectedRowKeys.includes(s.id),
          );
          await handleExportSelected(selected);
        })();
      }}
      onImport={() => void loadServices()}
    >
      <Table<ContextSystem>
        className={"flex-table"}
        rowKey={"id"}
        size={"small"}
        columns={columnsWithResize}
        dataSource={services}
        scroll={{ y: "", x: scrollX }}
        loading={loading}
        pagination={false}
        rowSelection={{
          type: "checkbox",
          selectedRowKeys,
          onChange: (keys) => setSelectedRowKeys(keys),
        }}
        components={columnResize.resizableHeaderComponents}
      />
    </GenericServiceListPage>
  );
};
