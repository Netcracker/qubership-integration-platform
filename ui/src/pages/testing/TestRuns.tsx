import React, { useCallback, useMemo, useRef, useState } from "react";
import { Flex, Table } from "antd";
import { useNavigate } from "react-router";
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
import { TableToolbar } from "../../components/table/TableToolbar.tsx";
import {
  ColumnsTypeWithSettings,
  useColumnSettingsBasedOnColumnsType,
} from "../../components/table/useColumnSettingsButton.tsx";
import { useColumnsWithResizeAndScroll } from "../../components/table/useColumnsWithResizeAndScroll.tsx";
import { getTestingPermissions } from "../../components/testing/testingPermissions.ts";
import { RunStatusTag } from "../../components/testing/TestingTags.tsx";
import { TestRunDrawer } from "../../components/testing/TestRunDrawer.tsx";
import { useTestingFilter } from "../../hooks/filter/useTestingFilter.ts";
import {
  TESTING_SELECTION_COLUMN_WIDTH,
  testsRunsListSource,
  useTestingEntityList,
} from "../../hooks/testing/useTestingEntityList.ts";
import { useNotificationService } from "../../hooks/useNotificationService.tsx";
import { useTableInfiniteScroll } from "../../hooks/useTableInfiniteScroll.ts";
import { confirmAndRun } from "../../misc/confirm-utils.ts";
import { formatOptional, formatTimestamp } from "../../misc/format-utils.ts";
import { ProtectedButton } from "../../permissions/ProtectedButton.tsx";

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
  const navigate = useNavigate();
  const notificationService = useNotificationService();
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
  } = useTestingEntityList<TestsRunView>({
    source: testsRunsListSource,
    filters,
    searchString,
    // Newest first: a run set is read from the latest attempt backwards.
    initialSortBy: "start",
    initialSortOrder: TestingSortOrder.DESC,
  });

  useTableInfiniteScroll(tableWrapperRef, { isLoading, allLoaded, loadMore });

  const handleRefresh = useCallback(() => {
    clearSelection();
    refresh();
  }, [clearSelection, refresh]);

  const handleExport = useCallback(async () => {
    if (selectedRowKeys.length === 0) {
      return;
    }
    try {
      const ids = await collectTargetIds();
      if (ids.length > 0) {
        await exportEntities(ids);
      }
    } catch (error) {
      notificationService.requestFailed("Failed to export test runs", error);
    }
  }, [selectedRowKeys, collectTargetIds, exportEntities, notificationService]);

  // A restart lands in this very list, so the rows are reloaded once it starts.
  const handleRestart = useCallback(async () => {
    if (selectedRowKeys.length === 0) {
      return;
    }
    try {
      const ids = await collectTargetIds();
      if (ids.length === 0) {
        return;
      }
      const newRunId = await api.startTestsRun(ids, TestsRunSource.TESTS_RUNS);
      notificationService.info(
        "Test run started",
        <a onClick={() => void navigate(`${SECTION_PATH}/${newRunId}`)}>
          {newRunId}
        </a>,
      );
      clearSelection();
      refresh();
    } catch (error) {
      notificationService.requestFailed("Failed to start a test run", error);
    }
  }, [
    selectedRowKeys,
    collectTargetIds,
    clearSelection,
    refresh,
    navigate,
    notificationService,
  ]);

  const cancelSelected = useCallback(async () => {
    try {
      const ids = await collectTargetIds();
      if (ids.length === 0) {
        return;
      }
      await api.cancelTestsRuns(ids);
      clearSelection();
      refresh();
    } catch (error) {
      notificationService.requestFailed("Failed to cancel test runs", error);
    }
  }, [collectTargetIds, clearSelection, refresh, notificationService]);

  // The service cancels the cases of the run that have not started yet and leaves
  // a running one alone, so the wording promises no more than that.
  const handleCancel = useCallback(() => {
    if (selectedRowKeys.length === 0) {
      return;
    }
    const target = selectAllMatching
      ? "all test runs that match the filters"
      : `${selectedRowKeys.length} test run${selectedRowKeys.length === 1 ? "" : "s"}`;
    confirmAndRun({
      title: "Cancel Test Runs",
      content: `Cancel ${target}? A case that already started keeps running.`,
      onOk: cancelSelected,
    });
  }, [selectedRowKeys, selectAllMatching, cancelSelected]);

  const deleteSelected = useCallback(async () => {
    try {
      const ids = await collectTargetIds();
      if (ids.length === 0) {
        return;
      }
      await api.deleteTestsRuns(ids);
      clearSelection();
      refresh();
    } catch (error) {
      notificationService.requestFailed("Failed to delete test runs", error);
    }
  }, [collectTargetIds, clearSelection, refresh, notificationService]);

  const handleDelete = useCallback(() => {
    if (selectedRowKeys.length === 0) {
      return;
    }
    const target = selectAllMatching
      ? "all test runs that match the filters"
      : `${selectedRowKeys.length} test run${selectedRowKeys.length === 1 ? "" : "s"}`;
    confirmAndRun({
      title: "Delete Test Runs",
      content: `Delete ${target} with their case runs? This cannot be undone.`,
      onOk: deleteSelected,
    });
  }, [selectedRowKeys, selectAllMatching, deleteSelected]);

  const columnDefinitions = useMemo<ColumnsTypeWithSettings<TestsRunView>>(
    () => [
      {
        title: "Id",
        dataIndex: "id",
        key: "id",
        sorter: true,
        settings: { visibilityLocked: true, orderLocked: true },
        render: (_, run) => (
          <a
            style={nameLinkStyle}
            onClick={(event) => {
              event.stopPropagation();
              void navigate(`${SECTION_PATH}/${run.id}`);
            }}
          >
            {run.id}
          </a>
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
    [navigate],
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
      <>
        <ProtectedButton
          require={permissions.view}
          tooltipProps={{ title: "Refresh", placement: "bottom" }}
          buttonProps={{
            "data-testid": "test-runs-refresh",
            iconName: "refresh",
            onClick: handleRefresh,
          }}
        />
        <ProtectedButton
          require={permissions.execute}
          tooltipProps={{ title: "Restart selected test runs" }}
          buttonProps={{
            "data-testid": "test-runs-restart",
            iconName: "redo",
            onClick: () => void handleRestart(),
          }}
        />
        <ProtectedButton
          require={permissions.execute}
          tooltipProps={{ title: "Cancel selected test runs" }}
          buttonProps={{
            "data-testid": "test-runs-cancel",
            iconName: "stop",
            onClick: handleCancel,
          }}
        />
        <ProtectedButton
          require={permissions.export}
          tooltipProps={{ title: "Export selected test runs" }}
          buttonProps={{
            "data-testid": "test-runs-export",
            iconName: "cloudDownload",
            onClick: () => void handleExport(),
          }}
        />
        <ProtectedButton
          require={permissions.write}
          tooltipProps={{ title: "Delete selected test runs" }}
          buttonProps={{
            "data-testid": "test-runs-delete",
            iconName: "delete",
            onClick: handleDelete,
          }}
        />
      </>
    ),
    [
      permissions,
      handleRefresh,
      handleRestart,
      handleCancel,
      handleExport,
      handleDelete,
    ],
  );

  const toolbar = useMemo(
    () => (
      <TableToolbar
        variant="admin"
        search={{
          value: searchString,
          onChange: setSearchString,
          placeholder: "Search test runs...",
          allowClear: true,
        }}
        filterButton={filterButton}
        columnSettingsButton={columnSettingsButton}
        actions={toolbarActions}
      />
    ),
    [searchString, filterButton, columnSettingsButton, toolbarActions],
  );

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
              onRow={(run) => ({
                onClick: () => setDetailsRun(run),
              })}
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
