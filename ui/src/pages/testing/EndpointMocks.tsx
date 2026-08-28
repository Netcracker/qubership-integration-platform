import React, { useCallback, useMemo, useRef, useState } from "react";
import { Flex, Table } from "antd";
import { useNavigate, useParams } from "react-router";
import { api } from "../../api/api.ts";
import { EndpointMock } from "../../api/apiTypes.ts";
import { AdminToolsHeader } from "../../components/admin_tools/AdminToolsHeader.tsx";
import commonStyles from "../../components/admin_tools/CommonStyle.module.css";
import { CreateEndpointMockModal } from "../../components/modal/testing/CreateEndpointMockModal.tsx";
import { TestingImportModal } from "../../components/modal/testing/TestingImportModal.tsx";
import { TablePageLayout } from "../../components/TablePageLayout.tsx";
import { nameLinkStyle } from "../../components/table/nameLinkStyle.ts";
import { tableEmpty } from "../../components/table/tableEmpty.tsx";
import { tableScroll } from "../../components/table/tableScroll.ts";
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
  TESTING_SELECTION_COLUMN_WIDTH,
  endpointMocksListSource,
  useTestingEntityList,
} from "../../hooks/testing/useTestingEntityList.ts";
import { useTestingBulkActions } from "../../hooks/testing/useTestingBulkActions.ts";
import { useTableInfiniteScroll } from "../../hooks/useTableInfiniteScroll.ts";
import { formatOptional, formatTimestamp } from "../../misc/format-utils.ts";
import { useModalsContext } from "../../Modals.tsx";
import type { TestingPageVariant } from "./TestingLayout.tsx";
import { RowLink } from "../../components/table/RowLink.tsx";
import { TestingListActions } from "../../components/testing/TestingListActions.tsx";
import { useTestingListToolbar } from "../../components/testing/useTestingListToolbar.tsx";

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

export type EndpointMocksProps = {
  variant?: TestingPageVariant;
};

export const EndpointMocks: React.FC<EndpointMocksProps> = ({
  variant = "chain-tab",
}) => {
  const { chainId } = useParams<{ chainId: string }>();
  const navigate = useNavigate();
  const { showModal } = useModalsContext();
  const [searchString, setSearchString] = useState("");
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
    exportEntities,
    sortBy,
    sortOrder,
    handleTableChange,
    selectedRowKeys,
    selectAllMatching,
    rowSelection,
    clearSelection,
    collectTargetIds,
    confirmSearch,
  } = useTestingEntityList<EndpointMock>({
    source: endpointMocksListSource,
    chainId,
    filters,
    searchString,
  });

  useTableInfiniteScroll(tableWrapperRef, { isLoading, allLoaded, loadMore });

  const { hasSelection, handleRefresh, handleExport, handleDelete } =
    useTestingBulkActions({
      entityName: endpointMocksListSource.entityName,
      entityNameSingular: "endpoint mock",
      selectedRowKeys,
      selectAllMatching,
      collectTargetIds,
      clearSelection,
      refresh,
      exportEntities,
      delete: {
        title: "Delete Endpoint Mocks",
        content: (target) => `Delete ${target}? This cannot be undone.`,
        run: (ids) => api.deleteEndpointMocks(ids),
      },
    });

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
      component: (
        <TestingImportModal
          title="Import Endpoint Mocks"
          failureMessage="Failed to import endpoint mocks"
          importFiles={(files) => api.importEndpointMocks(files)}
          onImported={handleRefresh}
        />
      ),
    });
  }, [handleRefresh, showModal]);

  const renderChainCell = useCallback(
    (endpointMock: EndpointMock) => {
      const id = endpointMock.endpointReference?.chainId;
      if (!id) {
        return formatOptional(null);
      }
      return <RowLink to={`/chains/${id}`}>{getChainName(id)}</RowLink>;
    },
    [getChainName],
  );

  const renderElementCell = useCallback(
    (endpointMock: EndpointMock) => {
      const reference = endpointMock.endpointReference;
      if (!reference?.chainId || !reference.elementId) {
        return formatOptional(null);
      }
      return (
        <RowLink
          to={`/chains/${reference.chainId}/graph/${reference.elementId}`}
        >
          {getElementName(reference.elementId)}
        </RowLink>
      );
    },
    [getElementName],
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
          <RowLink
            to={`${sectionPath}/endpoint-mocks/${endpointMock.id}`}
            style={nameLinkStyle}
          >
            {endpointMock.name}
          </RowLink>
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
    [chainId, sectionPath, renderChainCell, renderElementCell],
  );

  const { orderedColumns, columnSettingsButton } =
    useColumnSettingsBasedOnColumnsType<EndpointMock>(
      chainId ? "endpointMocksTableChain" : "endpointMocksTableAdmin",
      columnDefinitions,
    );

  const { columnsWithResize, scrollX, components } =
    useColumnsWithResizeAndScroll(orderedColumns, COLUMN_WIDTHS, {
      selectionColumnWidth: TESTING_SELECTION_COLUMN_WIDTH,
    });

  const toolbarActions = useMemo(
    () => (
      <TestingListActions
        testIdPrefix="endpoint-mocks"
        entityLabel="endpoint mocks"
        createLabel="an endpoint mock"
        permissions={permissions}
        actions={[
          { kind: "refresh", onClick: handleRefresh },
          {
            kind: "export",
            onClick: () => void handleExport(),
            disabled: !hasSelection,
          },
          ...(chainId
            ? []
            : [{ kind: "import" as const, onClick: handleImport }]),
          { kind: "delete", onClick: handleDelete, disabled: !hasSelection },
          ...(chainId
            ? [{ kind: "create" as const, onClick: handleCreate }]
            : []),
        ]}
      />
    ),
    [
      chainId,
      permissions,
      handleRefresh,
      hasSelection,
      handleExport,
      handleImport,
      handleDelete,
      handleCreate,
    ],
  );

  const toolbar = useTestingListToolbar({
    variant: variant === "admin-page" ? "admin" : "chain-tab",
    searchValue: searchString,
    onSearchChange: setSearchString,
    onSearchConfirm: confirmSearch,
    searchPlaceholder: "Search endpoint mocks...",
    filterButton,
    columnSettingsButton,
    actions: toolbarActions,
    registerInChainHeader: variant === "chain-tab",
    registerDependencies: [
      variant,
      searchString,
      sortBy,
      sortOrder,
      selectedRowKeys,
      selectAllMatching,
      allLoaded,
      filters,
      permissions,
    ],
  });

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
