import React, { useCallback, useMemo, useRef, useState } from "react";
import { Flex, Table } from "antd";
import { useNavigate, useParams } from "react-router";
import { api } from "../../api/api.ts";
import { TestCaseView } from "../../api/apiTypes.ts";
import { AdminToolsHeader } from "../../components/admin_tools/AdminToolsHeader.tsx";
import commonStyles from "../../components/admin_tools/CommonStyle.module.css";
import { CreateTestCaseModal } from "../../components/modal/testing/CreateTestCaseModal.tsx";
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
import { TestCaseDetailsDrawer } from "../../components/testing/TestCaseDetailsDrawer.tsx";
import { isTestCaseReady } from "../../components/testing/testCases.ts";
import { getTestingPermissions } from "../../components/testing/testingPermissions.ts";
import {
  EnabledTag,
  ReadinessTag,
} from "../../components/testing/TestingTags.tsx";
import { useTestingFilter } from "../../hooks/filter/useTestingFilter.ts";
import {
  TESTING_SELECTION_COLUMN_WIDTH,
  testCasesListSource,
  useTestingEntityList,
} from "../../hooks/testing/useTestingEntityList.ts";
import { useTestingBulkActions } from "../../hooks/testing/useTestingBulkActions.ts";
import { useTestsRunStarter } from "../../hooks/testing/useTestsRunStarter.tsx";
import { useTableInfiniteScroll } from "../../hooks/useTableInfiniteScroll.ts";
import { formatOptional, formatTimestamp } from "../../misc/format-utils.ts";
import { useModalsContext } from "../../Modals.tsx";
import { TestingListActions } from "../../components/testing/TestingListActions.tsx";
import { useTestingListToolbar } from "../../components/testing/useTestingListToolbar.tsx";
import type { TestingPageVariant } from "./TestingLayout.tsx";
import { RowLink } from "../../components/table/RowLink.tsx";

const COLUMN_WIDTHS = {
  name: 220,
  description: 220,
  chain_id: 180,
  element_id: 180,
  enabled: 110,
  readiness: 120,
  validation_rule_count: 90,
  enabled_rule_count: 110,
  created_at: 160,
  created_by: 130,
  updated_at: 160,
  updated_by: 130,
};

export type TestCasesProps = {
  variant?: TestingPageVariant;
};

export const TestCases: React.FC<TestCasesProps> = ({
  variant = "chain-tab",
}) => {
  const { chainId } = useParams<{ chainId: string }>();
  const navigate = useNavigate();
  const { showModal } = useModalsContext();
  const [searchString, setSearchString] = useState("");
  const [detailsTestCase, setDetailsTestCase] = useState<TestCaseView | null>(
    null,
  );
  const tableWrapperRef = useRef<HTMLDivElement>(null);

  const { filters, filterButton } = useTestingFilter("testCases", chainId);
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
  } = useTestingEntityList<TestCaseView>({
    source: testCasesListSource,
    chainId,
    filters,
    searchString,
  });

  useTableInfiniteScroll(tableWrapperRef, { isLoading, allLoaded, loadMore });

  const { hasSelection, handleRefresh, handleExport, handleDelete } =
    useTestingBulkActions({
      entityName: testCasesListSource.entityName,
      entityNameSingular: "test case",
      selectedRowKeys,
      selectAllMatching,
      collectTargetIds,
      clearSelection,
      refresh,
      exportEntities,
      delete: {
        title: "Delete Test Cases",
        content: (target) => `Delete ${target}? This cannot be undone.`,
        run: (ids) => api.deleteTestCases(ids),
      },
    });

  const { isStarting, startRun } = useTestsRunStarter({
    chainId,
    collectTargetIds,
  });

  const handleCreate = useCallback(() => {
    if (!chainId) {
      return;
    }
    showModal({
      component: (
        <CreateTestCaseModal
          chainId={chainId}
          onCreated={(testCase) =>
            void navigate(`${sectionPath}/test-cases/${testCase.id}`)
          }
        />
      ),
    });
  }, [chainId, navigate, sectionPath, showModal]);

  const handleImport = useCallback(() => {
    showModal({
      component: (
        <TestingImportModal
          title="Import Test Cases"
          failureMessage="Failed to import test cases"
          importFiles={(files) => api.importTestCases(files)}
          onImported={handleRefresh}
        />
      ),
    });
  }, [handleRefresh, showModal]);

  const renderChainCell = useCallback(
    (testCase: TestCaseView) => {
      const id = testCase.triggerReference?.chainId;
      if (!id) {
        return formatOptional(null);
      }
      return <RowLink to={`/chains/${id}`}>{getChainName(id)}</RowLink>;
    },
    [getChainName],
  );

  const renderElementCell = useCallback(
    (testCase: TestCaseView) => {
      const reference = testCase.triggerReference;
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

  const columnDefinitions = useMemo<ColumnsTypeWithSettings<TestCaseView>>(
    () => [
      {
        title: "Name",
        dataIndex: "name",
        key: "name",
        sorter: true,
        settings: { visibilityLocked: true, orderLocked: true },
        render: (_, testCase) => (
          <RowLink
            to={`${sectionPath}/test-cases/${testCase.id}`}
            style={nameLinkStyle}
          >
            {testCase.name}
          </RowLink>
        ),
      },
      {
        title: "Description",
        dataIndex: "description",
        key: "description",
        sorter: true,
        render: (_, testCase) => formatOptional(testCase.description),
      },
      ...(chainId
        ? []
        : [
            {
              title: "Chain",
              key: "chain_id",
              sorter: true,
              render: (_: unknown, testCase: TestCaseView) =>
                renderChainCell(testCase),
            },
          ]),
      {
        title: "Element",
        key: "element_id",
        sorter: true,
        render: (_, testCase) => renderElementCell(testCase),
      },
      {
        title: "Enabled",
        dataIndex: "enabled",
        key: "enabled",
        sorter: true,
        render: (_, testCase) => <EnabledTag enabled={testCase.enabled} />,
      },
      {
        title: "Readiness",
        key: "readiness",
        render: (_, testCase) => (
          <ReadinessTag ready={isTestCaseReady(testCase)} />
        ),
      },
      {
        title: "Rules",
        dataIndex: "validationRuleCount",
        key: "validation_rule_count",
        sorter: true,
      },
      {
        title: "Active Rules",
        dataIndex: "enabledRuleCount",
        key: "enabled_rule_count",
        sorter: true,
      },
      {
        title: "Created At",
        key: "created_at",
        sorter: true,
        hidden: true,
        render: (_, testCase) => formatTimestamp(testCase.createdAt),
      },
      {
        title: "Created By",
        key: "created_by",
        sorter: true,
        hidden: true,
        render: (_, testCase) => formatOptional(testCase.createdBy),
      },
      {
        title: "Updated At",
        key: "updated_at",
        sorter: true,
        hidden: true,
        render: (_, testCase) => formatTimestamp(testCase.updatedAt),
      },
      {
        title: "Updated By",
        key: "updated_by",
        sorter: true,
        hidden: true,
        render: (_, testCase) => formatOptional(testCase.updatedBy),
      },
    ],
    [chainId, sectionPath, renderChainCell, renderElementCell],
  );

  const { orderedColumns, columnSettingsButton } =
    useColumnSettingsBasedOnColumnsType<TestCaseView>(
      chainId ? "testCasesTableChain" : "testCasesTableAdmin",
      columnDefinitions,
    );

  const { columnsWithResize, scrollX, components } =
    useColumnsWithResizeAndScroll(orderedColumns, COLUMN_WIDTHS, {
      selectionColumnWidth: TESTING_SELECTION_COLUMN_WIDTH,
    });

  const toolbarActions = useMemo(
    () => (
      <TestingListActions
        testIdPrefix="test-cases"
        entityLabel="test cases"
        createLabel="a test case"
        permissions={permissions}
        actions={[
          { kind: "refresh", onClick: handleRefresh },
          {
            kind: "run",
            onClick: () => void startRun(),
            loading: isStarting,
            disabled: isStarting || !hasSelection,
          },
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
      isStarting,
      hasSelection,
      startRun,
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
    searchPlaceholder: "Search test cases...",
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
      isStarting,
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
        <Table<TestCaseView>
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
          locale={{ emptyText: tableEmpty("No test cases to display") }}
          scroll={tableScroll(scrollX, items.length)}
          components={components}
          onChange={handleTableChange}
          onRow={(testCase) => ({
            onClick: () => setDetailsTestCase(testCase),
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
            title="Test Cases"
            iconName="checkSquare"
            toolbar={toolbar}
          />
          {table}
        </Flex>
      ) : (
        table
      )}
      <TestCaseDetailsDrawer
        testCase={detailsTestCase}
        chainName={getChainName(detailsTestCase?.triggerReference?.chainId)}
        elementName={getElementName(
          detailsTestCase?.triggerReference?.elementId,
        )}
        open={detailsTestCase !== null}
        onClose={() => setDetailsTestCase(null)}
      />
    </>
  );
};

export default TestCases;
