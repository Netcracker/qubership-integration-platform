import React, { useMemo, useRef, useState } from "react";
import { Flex, Table } from "antd";
import { api } from "../../api/api.ts";
import {
  TestingSortOrder,
  TestsRunSource,
  TestsRunView,
} from "../../api/apiTypes.ts";
import { AdminToolsHeader } from "../../components/admin_tools/AdminToolsHeader.tsx";
import commonStyles from "../../components/admin_tools/CommonStyle.module.css";
import { TablePageLayout } from "../../components/TablePageLayout.tsx";
import { nameLinkStyle } from "../../components/table/nameLinkStyle.ts";
import { tableEmpty } from "../../components/table/tableEmpty.tsx";
import { tableScroll } from "../../components/table/tableScroll.ts";
import { rowClickProps } from "../../components/table/rowClick.ts";
import {
  ColumnsTypeWithSettings,
  useColumnSettingsBasedOnColumnsType,
} from "../../components/table/useColumnSettingsButton.tsx";
import { useColumnsWithResizeAndScroll } from "../../components/table/useColumnsWithResizeAndScroll.tsx";
import { getTestingPermissions } from "../../components/testing/testingPermissions.ts";
import { isTestsRunCancellable } from "../../components/testing/runStatus.ts";
import { RunStatusTag } from "../../components/testing/TestingTags.tsx";
import { TestRunDrawer } from "../../components/testing/TestRunDrawer.tsx";
import { useTestingFilter } from "../../hooks/filter/useTestingFilter.ts";
import {
  TESTING_SELECTION_COLUMN_WIDTH,
  testsRunsListSource,
  useTestingEntityList,
} from "../../hooks/testing/useTestingEntityList.ts";
import { useTestingBulkActions } from "../../hooks/testing/useTestingBulkActions.ts";
import { useTestsRunStarter } from "../../hooks/testing/useTestsRunStarter.tsx";
import { useTableInfiniteScroll } from "../../hooks/useTableInfiniteScroll.ts";
import { formatOptional, formatTimestamp } from "../../misc/format-utils.ts";
import { RowLink } from "../../components/table/RowLink.tsx";
import { TestingListActions } from "../../components/testing/TestingListActions.tsx";
import { useTestingListToolbar } from "../../components/testing/useTestingListToolbar.tsx";

const COLUMN_WIDTHS = {
  id: 260,
  status: 120,
  start: 160,
  finish: 160,
  test_cases: 110,
  errors: 170,
  created_at: 160,
  created_by: 130,
  updated_at: 160,
  updated_by: 130,
};

/** The list is routed under admin tools alone, so every path is an admin one. */
const SECTION_PATH = "/admintools/testing/test-runs";

export const TestRuns: React.FC = () => {
  const [searchString, setSearchString] = useState("");
  const [detailsRun, setDetailsRun] = useState<TestsRunView | null>(null);
  const tableWrapperRef = useRef<HTMLDivElement>(null);

  const { filters, filterButton } = useTestingFilter("testsRuns");
  const permissions = useMemo(() => getTestingPermissions(), []);

  const {
    items,
    isLoading,
    allLoaded,
    loadMore,
    refresh,
    exportEntities,
    handleTableChange,
    selectedRowKeys,
    selectAllMatching,
    rowSelection,
    clearSelection,
    collectTargetIds,
    confirmSearch,
  } = useTestingEntityList<TestsRunView>({
    source: testsRunsListSource,
    filters,
    searchString,
    // Newest first: a run set is read from the latest attempt backwards.
    initialSortBy: "start",
    initialSortOrder: TestingSortOrder.DESC,
  });

  useTableInfiniteScroll(tableWrapperRef, { isLoading, allLoaded, loadMore });

  // Selecting everything that matches resolves the ids server-side at click time,
  // so the statuses off the loaded page are unknown and the button has to stay
  // open. A hand-picked selection is checked against the rows on screen.
  const canCancelSelection = useMemo(() => {
    if (selectAllMatching) {
      return selectedRowKeys.length > 0;
    }
    const selected = new Set(selectedRowKeys.map(String));
    return items.some(
      (run) =>
        selected.has(String(run.id)) && isTestsRunCancellable(run.status),
    );
  }, [items, selectedRowKeys, selectAllMatching]);

  const {
    hasSelection,
    handleRefresh,
    handleExport,
    handleCancel,
    handleDelete,
  } = useTestingBulkActions({
    entityName: testsRunsListSource.entityName,
    entityNameSingular: "test run",
    selectedRowKeys,
    selectAllMatching,
    collectTargetIds,
    clearSelection,
    refresh,
    exportEntities,
    // The service cancels the cases of the run that have not started yet and
    // leaves a running one alone, so the wording promises no more than that.
    cancel: {
      title: "Cancel Test Runs",
      content: (target) =>
        `Cancel ${target}? A case that already started keeps running.`,
      run: (ids) => api.cancelTestsRuns(ids),
    },
    delete: {
      title: "Delete Test Runs",
      content: (target) =>
        `Delete ${target} with their case runs? This cannot be undone.`,
      run: (ids) => api.deleteTestsRuns(ids),
    },
  });

  // A restart lands in this very list, so the rows are reloaded once it starts.
  const { isStarting, startRun } = useTestsRunStarter({
    source: TestsRunSource.TESTS_RUNS,
    collectTargetIds,
    onStarted: handleRefresh,
  });

  const columnDefinitions = useMemo<ColumnsTypeWithSettings<TestsRunView>>(
    () => [
      {
        title: "Id",
        dataIndex: "id",
        key: "id",
        sorter: true,
        settings: { visibilityLocked: true, orderLocked: true },
        render: (_, run) => (
          <RowLink to={`${SECTION_PATH}/${run.id}`} style={nameLinkStyle}>
            {run.id}
          </RowLink>
        ),
      },
      {
        title: "Status",
        key: "status",
        sorter: true,
        render: (_, run) => <RunStatusTag status={run.status} />,
      },
      {
        title: "Start",
        key: "start",
        sorter: true,
        defaultSortOrder: "descend",
        render: (_, run) => formatTimestamp(run.start),
      },
      {
        title: "Finish",
        key: "finish",
        sorter: true,
        render: (_, run) => formatTimestamp(run.finish),
      },
      {
        title: "Test Cases",
        dataIndex: "testCases",
        key: "test_cases",
        sorter: true,
      },
      {
        // The aggregate counts the cases that failed, not the errors they recorded.
        title: "Test Cases With Errors",
        dataIndex: "errors",
        key: "errors",
        sorter: true,
      },
      {
        title: "Created At",
        key: "created_at",
        sorter: true,
        hidden: true,
        render: (_, run) => formatTimestamp(run.createdAt),
      },
      {
        title: "Created By",
        key: "created_by",
        sorter: true,
        hidden: true,
        render: (_, run) => formatOptional(run.createdBy),
      },
      // The updated pair renders but carries no sorter: the service rejects it as
      // a sort field.
      {
        title: "Updated At",
        key: "updated_at",
        hidden: true,
        render: (_, run) => formatTimestamp(run.updatedAt),
      },
      {
        title: "Updated By",
        key: "updated_by",
        hidden: true,
        render: (_, run) => formatOptional(run.updatedBy),
      },
    ],
    [],
  );

  const { orderedColumns, columnSettingsButton } =
    useColumnSettingsBasedOnColumnsType<TestsRunView>(
      "testsRunsTable",
      columnDefinitions,
    );

  const { columnsWithResize, scrollX, components } =
    useColumnsWithResizeAndScroll(orderedColumns, COLUMN_WIDTHS, {
      selectionColumnWidth: TESTING_SELECTION_COLUMN_WIDTH,
    });

  const toolbarActions = useMemo(
    () => (
      <TestingListActions
        testIdPrefix="test-runs"
        entityLabel="test runs"
        permissions={permissions}
        actions={[
          { kind: "refresh", onClick: handleRefresh },
          {
            kind: "restart",
            onClick: () => void startRun(),
            loading: isStarting,
            disabled: isStarting || !hasSelection,
          },
          {
            kind: "cancel",
            onClick: handleCancel,
            disabled: !canCancelSelection,
          },
          {
            kind: "export",
            onClick: () => void handleExport(),
            disabled: !hasSelection,
          },
          { kind: "delete", onClick: handleDelete, disabled: !hasSelection },
        ]}
      />
    ),
    [
      permissions,
      handleRefresh,
      isStarting,
      hasSelection,
      startRun,
      canCancelSelection,
      handleCancel,
      handleExport,
      handleDelete,
    ],
  );

  const toolbar = useTestingListToolbar({
    variant: "admin",
    searchValue: searchString,
    onSearchChange: setSearchString,
    onSearchConfirm: confirmSearch,
    searchPlaceholder: "Search test runs...",
    filterButton,
    columnSettingsButton,
    actions: toolbarActions,
    registerInChainHeader: false,
    registerDependencies: [],
  });

  return (
    <>
      <Flex vertical className={commonStyles.container}>
        <AdminToolsHeader
          title="Test Runs"
          iconName="carryOut"
          toolbar={toolbar}
        />
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
            <Table<TestsRunView>
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
              locale={{ emptyText: tableEmpty("No test runs to display") }}
              scroll={tableScroll(scrollX, items.length)}
              components={components}
              onChange={handleTableChange}
              onRow={rowClickProps(setDetailsRun)}
            />
          </div>
        </TablePageLayout>
      </Flex>
      <TestRunDrawer
        run={detailsRun}
        caseRunsPath={
          detailsRun ? `${SECTION_PATH}/${detailsRun.id}` : undefined
        }
        open={detailsRun !== null}
        onClose={() => setDetailsRun(null)}
      />
    </>
  );
};

export default TestRuns;
