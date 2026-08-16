import React, { useCallback, useMemo, useRef, useState } from "react";
import { Flex, Table } from "antd";
import type { TableProps } from "antd/lib/table";
import type { TableRowSelection } from "antd/lib/table/interface";
import { useNavigate, useParams } from "react-router";
import { api } from "../../api/api.ts";
import { EndpointMock, TestingSortOrder } from "../../api/apiTypes.ts";
import { AdminToolsHeader } from "../../components/admin_tools/AdminToolsHeader.tsx";
import commonStyles from "../../components/admin_tools/CommonStyle.module.css";
import { CreateEndpointMockModal } from "../../components/modal/testing/CreateEndpointMockModal.tsx";
import { ImportEndpointMocksModal } from "../../components/modal/testing/ImportEndpointMocksModal.tsx";
import { TablePageLayout } from "../../components/TablePageLayout.tsx";
import { nameLinkStyle } from "../../components/table/nameLinkStyle.ts";
import { tableEmpty } from "../../components/table/tableEmpty.tsx";
import { tableScroll } from "../../components/table/tableScroll.ts";
import { TableToolbar } from "../../components/table/TableToolbar.tsx";
import {
  ColumnsTypeWithSettings,
  useColumnSettingsBasedOnColumnsType,
} from "../../components/table/useColumnSettingsButton.tsx";
import { useColumnsWithResizeAndScroll } from "../../components/table/useColumnsWithResizeAndScroll.tsx";
import { EndpointMockDetailsDrawer } from "../../components/testing/EndpointMockDetailsDrawer.tsx";
import { formatMockNumber } from "../../components/testing/endpointMocks.ts";
import { getTestingPermissions } from "../../components/testing/testingPermissions.ts";
import { EnabledTag } from "../../components/testing/TestingTags.tsx";
import { useTestingFilter } from "../../hooks/filter/useTestingFilter.ts";
import {
  endpointMocksListSource,
  useTestingEntityList,
} from "../../hooks/testing/useTestingEntityList.ts";
import { useNotificationService } from "../../hooks/useNotificationService.tsx";
import { useTableInfiniteScroll } from "../../hooks/useTableInfiniteScroll.ts";
import { confirmAndRun } from "../../misc/confirm-utils.ts";
import { formatOptional, formatTimestamp } from "../../misc/format-utils.ts";
import { toStringIds } from "../../misc/selection-utils.ts";
import { useModalsContext } from "../../Modals.tsx";
import { ProtectedButton } from "../../permissions/ProtectedButton.tsx";
import { useRegisterChainHeaderActions } from "../ChainHeaderActionsContext.tsx";

const SELECTION_COLUMN_WIDTH = 48;

const COLUMN_WIDTHS = {
  name: 220,
  description: 220,
  chain_id: 180,
  element_id: 180,
  enabled: 110,
  status: 130,
  delay: 130,
  created_at: 160,
  created_by: 130,
  updated_at: 160,
  updated_by: 130,
};

/** Selection option that reaches past the loaded page; resolved server-side. */
const SELECT_ALL_MATCHING_KEY = "all-matching";

export type EndpointMocksProps = {
  variant?: "chain-tab" | "admin-page";
};

export const EndpointMocks: React.FC<EndpointMocksProps> = ({
  variant = "chain-tab",
}) => {
  const { chainId } = useParams<{ chainId: string }>();
  const navigate = useNavigate();
  const notificationService = useNotificationService();
  const { showModal } = useModalsContext();
  const [searchString, setSearchString] = useState("");
  const [sortBy, setSortBy] = useState<string>();
  const [sortOrder, setSortOrder] = useState<TestingSortOrder>();
  const [selectedRowKeys, setSelectedRowKeys] = useState<React.Key[]>([]);
  const [selectAllMatching, setSelectAllMatching] = useState(false);
  const [detailsMock, setDetailsMock] = useState<EndpointMock | null>(null);
  const tableWrapperRef = useRef<HTMLDivElement>(null);

  const { filters, filterButton } = useTestingFilter("endpointMocks", chainId);
  const permissions = useMemo(() => getTestingPermissions(chainId), [chainId]);
  const sectionPath = chainId
    ? `/chains/${chainId}/testing`
    : "/admintools/testing";

  const {
    items,
    isLoading,
    allLoaded,
    loadMore,
    refresh,
    getChainName,
    getElementName,
    resolveTargetIds,
    exportEntities,
  } = useTestingEntityList<EndpointMock>({
    source: endpointMocksListSource,
    chainId,
    filters,
    searchString,
    sortBy,
    sortOrder,
  });

  useTableInfiniteScroll(tableWrapperRef, { isLoading, allLoaded, loadMore });

  const clearSelection = useCallback(() => {
    setSelectedRowKeys([]);
    setSelectAllMatching(false);
  }, []);

  const collectTargetIds = useCallback(
    () => resolveTargetIds(toStringIds(selectedRowKeys), selectAllMatching),
    [resolveTargetIds, selectedRowKeys, selectAllMatching],
  );

  const handleRefresh = useCallback(() => {
    clearSelection();
    refresh();
  }, [clearSelection, refresh]);

  const handleExport = useCallback(async () => {
    if (selectedRowKeys.length === 0) {
      return;
    }
    const ids = await collectTargetIds();
    if (ids.length > 0) {
      await exportEntities(ids);
    }
  }, [selectedRowKeys, collectTargetIds, exportEntities]);

  const deleteSelected = useCallback(async () => {
    try {
      const ids = await collectTargetIds();
      if (ids.length === 0) {
        return;
      }
      await api.deleteEndpointMocks(ids);
      clearSelection();
      refresh();
    } catch (error) {
      notificationService.requestFailed(
        "Failed to delete endpoint mocks",
        error,
      );
    }
  }, [collectTargetIds, clearSelection, refresh, notificationService]);

  const handleDelete = useCallback(() => {
    if (selectedRowKeys.length === 0) {
      return;
    }
    const target = selectAllMatching
      ? "all endpoint mocks that match the filters"
      : `${selectedRowKeys.length} endpoint mock${selectedRowKeys.length === 1 ? "" : "s"}`;
    confirmAndRun({
      title: "Delete Endpoint Mocks",
      content: `Delete ${target}? This cannot be undone.`,
      onOk: deleteSelected,
    });
  }, [selectedRowKeys, selectAllMatching, deleteSelected]);

  const handleCreate = useCallback(() => {
    if (!chainId) {
      return;
    }
    showModal({
      component: (
        <CreateEndpointMockModal
          chainId={chainId}
          onCreated={(endpointMock) =>
            void navigate(`${sectionPath}/endpoint-mocks/${endpointMock.id}`)
          }
        />
      ),
    });
  }, [chainId, navigate, sectionPath, showModal]);

  const handleImport = useCallback(() => {
    showModal({
      component: <ImportEndpointMocksModal onImported={handleRefresh} />,
    });
  }, [handleRefresh, showModal]);

  const renderChainCell = useCallback(
    (endpointMock: EndpointMock) => {
      const id = endpointMock.endpointReference?.chainId;
      if (!id) {
        return formatOptional(id);
      }
      return (
        <a
          onClick={(event) => {
            event.stopPropagation();
            void navigate(`/chains/${id}`);
          }}
        >
          {getChainName(id)}
        </a>
      );
    },
    [navigate, getChainName],
  );

  const renderElementCell = useCallback(
    (endpointMock: EndpointMock) => {
      const reference = endpointMock.endpointReference;
      if (!reference?.chainId || !reference.elementId) {
        return formatOptional(null);
      }
      return (
        <a
          onClick={(event) => {
            event.stopPropagation();
            void navigate(
              `/chains/${reference.chainId}/graph/${reference.elementId}`,
            );
          }}
        >
          {getElementName(reference.elementId)}
        </a>
      );
    },
    [navigate, getElementName],
  );

  const columnDefinitions = useMemo<ColumnsTypeWithSettings<EndpointMock>>(
    () => [
      {
        title: "Name",
        dataIndex: "name",
        key: "name",
        sorter: true,
        settings: { visibilityLocked: true, orderLocked: true },
        render: (_, endpointMock) => (
          <a
            style={nameLinkStyle}
            onClick={(event) => {
              event.stopPropagation();
              void navigate(`${sectionPath}/endpoint-mocks/${endpointMock.id}`);
            }}
          >
            {endpointMock.name}
          </a>
        ),
      },
      {
        title: "Description",
        dataIndex: "description",
        key: "description",
        sorter: true,
        render: (_, endpointMock) => formatOptional(endpointMock.description),
      },
      ...(chainId
        ? []
        : [
            {
              title: "Chain",
              key: "chain_id",
              sorter: true,
              render: (_: unknown, endpointMock: EndpointMock) =>
                renderChainCell(endpointMock),
            },
          ]),
      {
        title: "Element",
        key: "element_id",
        sorter: true,
        render: (_, endpointMock) => renderElementCell(endpointMock),
      },
      {
        title: "Enabled",
        dataIndex: "enabled",
        key: "enabled",
        sorter: true,
        render: (_, endpointMock) => (
          <EnabledTag enabled={endpointMock.enabled} />
        ),
      },
      {
        title: "Response Status",
        key: "status",
        sorter: true,
        render: (_, endpointMock) =>
          formatMockNumber(endpointMock.responseSettings?.status),
      },
      {
        title: "Response Delay",
        key: "delay",
        sorter: true,
        render: (_, endpointMock) =>
          formatMockNumber(endpointMock.responseSettings?.delay),
      },
      {
        title: "Created At",
        key: "created_at",
        sorter: true,
        hidden: true,
        render: (_, endpointMock) => formatTimestamp(endpointMock.createdAt),
      },
      {
        title: "Created By",
        key: "created_by",
        sorter: true,
        hidden: true,
        render: (_, endpointMock) => formatOptional(endpointMock.createdBy),
      },
      {
        title: "Updated At",
        key: "updated_at",
        sorter: true,
        hidden: true,
        render: (_, endpointMock) => formatTimestamp(endpointMock.updatedAt),
      },
      {
        title: "Updated By",
        key: "updated_by",
        sorter: true,
        hidden: true,
        render: (_, endpointMock) => formatOptional(endpointMock.updatedBy),
      },
    ],
    [chainId, navigate, sectionPath, renderChainCell, renderElementCell],
  );

  const { orderedColumns, columnSettingsButton } =
    useColumnSettingsBasedOnColumnsType<EndpointMock>(
      chainId ? "endpointMocksTableChain" : "endpointMocksTableAdmin",
      columnDefinitions,
    );

  const { columnsWithResize, scrollX, components } =
    useColumnsWithResizeAndScroll(orderedColumns, COLUMN_WIDTHS, {
      selectionColumnWidth: SELECTION_COLUMN_WIDTH,
    });

  const handleTableChange = useCallback<
    NonNullable<TableProps<EndpointMock>["onChange"]>
  >((_pagination, _tableFilters, sorter) => {
    const { columnKey, order } = Array.isArray(sorter) ? sorter[0] : sorter;
    setSortBy(order ? String(columnKey) : undefined);
    setSortOrder(
      order === "descend"
        ? TestingSortOrder.DESC
        : order === "ascend"
          ? TestingSortOrder.ASC
          : undefined,
    );
  }, []);

  const rowSelection: TableRowSelection<EndpointMock> = {
    type: "checkbox",
    selectedRowKeys,
    onChange: (keys) => {
      setSelectedRowKeys(keys);
      setSelectAllMatching(false);
    },
    selections: allLoaded
      ? undefined
      : [
          Table.SELECTION_ALL,
          Table.SELECTION_NONE,
          {
            key: SELECT_ALL_MATCHING_KEY,
            text: "Select all that match the filters",
            onSelect: () => {
              setSelectedRowKeys(items.map((endpointMock) => endpointMock.id));
              setSelectAllMatching(true);
            },
          },
        ],
  };

  const toolbarActions = useMemo(
    () => (
      <>
        <ProtectedButton
          require={permissions.view}
          tooltipProps={{ title: "Refresh", placement: "bottom" }}
          buttonProps={{
            "data-testid": "endpoint-mocks-refresh",
            iconName: "refresh",
            onClick: handleRefresh,
          }}
        />
        <ProtectedButton
          require={permissions.export}
          tooltipProps={{ title: "Export selected endpoint mocks" }}
          buttonProps={{
            "data-testid": "endpoint-mocks-export",
            iconName: "cloudDownload",
            onClick: () => void handleExport(),
          }}
        />
        {chainId ? null : (
          <ProtectedButton
            require={permissions.import}
            tooltipProps={{ title: "Import endpoint mocks" }}
            buttonProps={{
              "data-testid": "endpoint-mocks-import",
              iconName: "cloudUpload",
              onClick: handleImport,
            }}
          />
        )}
        <ProtectedButton
          require={permissions.write}
          tooltipProps={{ title: "Delete selected endpoint mocks" }}
          buttonProps={{
            "data-testid": "endpoint-mocks-delete",
            iconName: "delete",
            onClick: handleDelete,
          }}
        />
        {chainId ? (
          <ProtectedButton
            require={permissions.write}
            tooltipProps={{ title: "Create an endpoint mock" }}
            buttonProps={{
              "data-testid": "endpoint-mocks-create",
              type: "primary",
              iconName: "plus",
              children: "Create",
              onClick: handleCreate,
            }}
          />
        ) : null}
      </>
    ),
    [
      chainId,
      permissions,
      handleRefresh,
      handleExport,
      handleImport,
      handleDelete,
      handleCreate,
    ],
  );

  const toolbar = useMemo(
    () => (
      <TableToolbar
        variant={variant === "admin-page" ? "admin" : "chain-tab"}
        search={{
          value: searchString,
          onChange: setSearchString,
          placeholder: "Search endpoint mocks...",
          allowClear: true,
        }}
        filterButton={filterButton}
        columnSettingsButton={columnSettingsButton}
        actions={toolbarActions}
      />
    ),
    [variant, searchString, filterButton, columnSettingsButton, toolbarActions],
  );

  // Re-registered on the state the toolbar reads, not on the toolbar node: the
  // filter and column-settings buttons are fresh elements on every render, and
  // depending on them would loop through the header's own re-render.
  useRegisterChainHeaderActions(variant === "chain-tab" ? toolbar : undefined, [
    variant,
    searchString,
    selectedRowKeys,
    selectAllMatching,
    allLoaded,
    filters,
    permissions,
  ]);

  const table = (
    <TablePageLayout>
      <div
        ref={tableWrapperRef}
        style={{
          flex: 1,
          minHeight: 0,
          display: "flex",
          flexDirection: "column",
        }}
      >
        <Table<EndpointMock>
          size="small"
          className="flex-table"
          columns={columnsWithResize}
          rowSelection={rowSelection}
          dataSource={items}
          pagination={false}
          loading={isLoading}
          rowKey="id"
          sticky
          style={{ flex: 1, minHeight: 0 }}
          locale={{ emptyText: tableEmpty("No endpoint mocks to display") }}
          scroll={tableScroll(scrollX, items.length)}
          components={components}
          onChange={handleTableChange}
          onRow={(endpointMock) => ({
            onClick: () => setDetailsMock(endpointMock),
          })}
        />
      </div>
    </TablePageLayout>
  );

  return (
    <>
      {variant === "admin-page" ? (
        <Flex vertical className={commonStyles.container}>
          <AdminToolsHeader
            title="Endpoint Mocks"
            iconName="api"
            toolbar={toolbar}
          />
          {table}
        </Flex>
      ) : (
        table
      )}
      <EndpointMockDetailsDrawer
        endpointMock={detailsMock}
        chainName={getChainName(detailsMock?.endpointReference?.chainId)}
        elementName={getElementName(detailsMock?.endpointReference?.elementId)}
        open={detailsMock !== null}
        onClose={() => setDetailsMock(null)}
      />
    </>
  );
};

export default EndpointMocks;
