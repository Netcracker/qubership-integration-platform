import React, { useCallback, useEffect, useMemo, useState } from "react";
import { GenericServiceListPage } from "../GenericServiceListPage.tsx";
import { OverridableIcon } from "../../../icons/IconProvider.tsx";
import { Button, message, Table, TableProps } from "antd";
import {
  IntegrationSystemType,
  MCPSystem,
  MCPSystemCreateRequest,
} from "../../../api/apiTypes.ts";
import { formatOptional, formatTimestamp } from "../../../misc/format-utils.ts";
import {
  createActionsColumnBase,
  disableResizeBeforeActions,
} from "../../table/actionsColumn.ts";
import { useNotificationService } from "../../../hooks/useNotificationService.tsx";
import { api } from "../../../api/api.ts";
import { useNavigate } from "react-router-dom";
import { Require } from "../../../permissions/Require.tsx";
import { EntityLabels } from "../../labels/EntityLabels.tsx";
import { InlineEdit } from "../../InlineEdit.tsx";
import { LabelsEdit } from "../../table/LabelsEdit.tsx";
import { ProtectedDropdown } from "../../../permissions/ProtectedDropdown.tsx";
import { downloadFile } from "../../../misc/download-utils.ts";
import { toStringIds } from "../../../misc/selection-utils.ts";
import { useColumnSettingsBasedOnColumnsType } from "../../table/useColumnSettingsButton.tsx";
import {
  attachResizeToColumns,
  sumScrollXForColumns,
  useTableColumnResize,
} from "../../table/useTableColumnResize.tsx";
import { useFilter } from "../../table/filter/useFilter.tsx";
import {
  DateFilterConditions,
  IdFilterConditions,
  StringFilterConditions,
} from "../../table/filter/filter.ts";
import { LabelsStringTableFilter } from "../../../hooks/useChainFilter.ts";
import { ChainColumn } from "../ui/ChainColumn.tsx";

const SELECTION_COLUMN_WIDTH = 48;

export const McpServiceList: React.FC = () => {
  const [systems, setSystems] = useState<MCPSystem[]>([]);
  const [isLoading, setIsLoading] = useState<boolean>(false);
  const [selectedRowKeys, setSelectedRowKeys] = useState<React.Key[]>([]);
  const notificationService = useNotificationService();
  const navigate = useNavigate();
  const [searchString, setSearchString] = useState<string>("");
  const { filters, filterButton } = useFilter([
    { id: "ID", name: "ID", conditions: IdFilterConditions },
    { id: "NAME", name: "Name", conditions: StringFilterConditions },
    {
      id: "IDENTIFIER",
      name: "Identifier",
      conditions: StringFilterConditions,
    },
    { id: "LABELS", name: "Labels", conditions: LabelsStringTableFilter },
    { id: "CREATED", name: "Created", conditions: DateFilterConditions },
  ]);

  const loadSystems = useCallback(async () => {
    try {
      setIsLoading(true);
      const result =
        filters.length > 0 || searchString
          ? await api.filterMcpSystems(searchString, filters)
          : await api.getMcpSystems(true);
      setSystems(result);
    } catch (e) {
      notificationService.requestFailed("Failed to load MCP services", e);
      setSystems([]);
    } finally {
      setIsLoading(false);
    }
  }, [notificationService, searchString, filters]);

  const createSystem = useCallback(
    async (name: string, description: string) => {
      try {
        const system = await api.createMcpSystem({ name, description });
        await navigate(`/services/mcp/${system.id}/parameters`);
      } catch (e) {
        notificationService.requestFailed("Failed to create MCP service", e);
        throw e;
      }
    },
    [navigate, notificationService],
  );

  const updateSystem = useCallback(
    async (id: string, changes: MCPSystemCreateRequest) => {
      try {
        const system = await api.updateMcpSystem(id, changes);
        setSystems((values) => values.map((s) => (s.id === id ? system : s)));
      } catch (e) {
        notificationService.requestFailed("Failed to update MCP service", e);
      }
    },
    [notificationService],
  );

  const deleteSystem = useCallback(
    async (id: string) => {
      try {
        await api.deleteMcpSystem(id);
        setSystems((values) => values.filter((s) => s.id !== id));
      } catch (e) {
        notificationService.requestFailed("Failed to update MCP service", e);
      }
    },
    [notificationService],
  );

  const exportSystems = useCallback(
    async (ids: string[]) => {
      try {
        if (ids.length === 0) {
          message.info("No services selected");
          return;
        }
        const file = await api.exportMcpSystems(ids);
        downloadFile(file);
      } catch (e) {
        notificationService.requestFailed("Failed to download MCP services", e);
      }
    },
    [notificationService],
  );

  useEffect(() => {
    void loadSystems();
  }, [loadSystems]);

  const columns: TableProps<MCPSystem>["columns"] = [
    {
      title: "Name",
      dataIndex: "name",
      key: "name",
      width: 200,
      minWidth: 120,
      render: (_: unknown, system) => (
        <a
          href={`/services/mcp/${system.id}/parameters`}
          onClick={(event) => {
            event.preventDefault();
            event.stopPropagation();
            void navigate(`/services/mcp/${system.id}/parameters`);
          }}
        >
          {system.name}
        </a>
      ),
    },
    {
      title: "Identifier",
      dataIndex: "identifier",
      key: "identifier",
      width: 200,
      minWidth: 120,
    },
    {
      title: "Labels",
      dataIndex: "labels",
      key: "labels",
      width: 200,
      render: (_: unknown, system) => (
        <Require
          permissions={{ service: ["update"] }}
          fallback={<EntityLabels labels={system.labels} />}
        >
          <InlineEdit<{ labels: string[] }>
            values={{
              labels: system.labels
                ?.filter((l) => !l.technical)
                .map((l) => l.name),
            }}
            editor={<LabelsEdit name={"labels"} />}
            viewer={<EntityLabels labels={system.labels} />}
            onSubmit={async ({ labels }) => {
              await updateSystem(system.id, {
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
      render: (_: unknown, system) =>
        <ChainColumn chains={system.chains ?? []} />,
    },
    {
      title: "Created At",
      dataIndex: "createdWhen",
      key: "createdWhen",
      width: 160,
      render: (_: unknown, system) => formatTimestamp(system.createdWhen),
    },
    {
      title: "Created By",
      dataIndex: "createdBy",
      key: "createdBy",
      width: 130,
      render: (_: unknown, system) =>
        formatOptional(system.createdBy?.username),
    },
    {
      title: "Modified At",
      dataIndex: "modifiedWhen",
      key: "modifiedWhen",
      width: 160,
      render: (_: unknown, system) => formatTimestamp(system.modifiedWhen),
    },
    {
      title: "Modified By",
      dataIndex: "modifiedBy",
      key: "modifiedBy",
      width: 130,
      render: (_: unknown, system) =>
        formatOptional(system.modifiedBy?.username),
    },
    {
      ...createActionsColumnBase<MCPSystem>(),
      render: (_: unknown, system) => (
        <>
          <ProtectedDropdown
            menu={{
              items: [
                {
                  key: "delete",
                  label: "Delete",
                  icon: <OverridableIcon name={"delete"} />,
                  onClick: () => void deleteSystem(system.id),
                  require: { service: ["delete"] },
                },
                {
                  key: "export",
                  label: "Export",
                  icon: <OverridableIcon name={"export"} />,
                  onClick: () => void exportSystems([system.id]),
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
        </>
      ),
    },
  ];

  const { orderedColumns, columnSettingsButton } =
    useColumnSettingsBasedOnColumnsType("mcpSystemTable", columns);

  const columnResize = useTableColumnResize({
    name: 200,
    identifier: 200,
    usedBy: 120,
    labels: 200,
    createdBy: 120,
    createdWhen: 168,
    modifiedBy: 120,
    modifiedWhen: 168,
  });

  const columnsWithResize = useMemo(() => {
    const resized = attachResizeToColumns(
      orderedColumns,
      columnResize.columnWidths,
      columnResize.createResizeHandlers,
      { minWidth: 80 },
    );
    return disableResizeBeforeActions(resized);
  }, [
    orderedColumns,
    columnResize.columnWidths,
    columnResize.createResizeHandlers,
  ]);

  const scrollX = useMemo(
    () =>
      sumScrollXForColumns(columnsWithResize, columnResize.columnWidths, {
        selectionColumnWidth: SELECTION_COLUMN_WIDTH,
      }),
    [columnsWithResize, columnResize.columnWidths],
  );

  return (
    <GenericServiceListPage
      title={"MCP Services"}
      icon={<OverridableIcon name={"mcp"} />}
      extraActions={[filterButton, columnSettingsButton]}
      serviceType={IntegrationSystemType.MCP}
      onCreate={(name, description) => createSystem(name, description)}
      onSearch={(value) => setSearchString(value)}
      onExport={() => void exportSystems(toStringIds(selectedRowKeys))}
      onImport={() => void loadSystems()}
    >
      <Table<MCPSystem>
        className={"flex-table"}
        rowKey={"id"}
        size={"small"}
        columns={columnsWithResize}
        dataSource={systems}
        scroll={{ y: "", x: scrollX }}
        loading={isLoading}
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
