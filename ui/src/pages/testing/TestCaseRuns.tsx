import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { Flex, Table } from "antd";
import { useNavigate, useParams } from "react-router";
import { api } from "../../api/api.ts";
import {
  TestCaseRunView,
  TestingFilter,
  TestingFilterCondition,
  TestingSortOrder,
  TestsRunSource,
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
import { TestCaseRunDrawer } from "../../components/testing/TestCaseRunDrawer.tsx";
import { getTestingPermissions } from "../../components/testing/testingPermissions.ts";
import { isTestCaseRunCancellable } from "../../components/testing/runStatus.ts";
import { RunStatusTag } from "../../components/testing/TestingTags.tsx";
import {
  TESTING_TESTS_RUN_FEATURE,
  useTestingFilter,
} from "../../hooks/filter/useTestingFilter.ts";
import {
  TESTING_SELECTION_COLUMN_WIDTH,
  testCaseRunsListSource,
  useTestingEntityList,
} from "../../hooks/testing/useTestingEntityList.ts";
import { useTestsRunStarter } from "../../hooks/testing/useTestsRunStarter.tsx";
import { useNotificationService } from "../../hooks/useNotificationService.tsx";
import { useTableInfiniteScroll } from "../../hooks/useTableInfiniteScroll.ts";
import { confirmAndRun } from "../../misc/confirm-utils.ts";
import { formatOptional, formatTimestamp } from "../../misc/format-utils.ts";
import { ProtectedButton } from "../../permissions/ProtectedButton.tsx";
import { useRegisterChainHeaderActions } from "../ChainHeaderActionsContext.tsx";

// Every column is held to what it actually renders. The three that carry a uuid
// take 270: the glyphs of a uuid are not all the same width, so the widest ones
// need 248px and the 260 they used to get wrapped them onto a second line. The
// four that follow take what their own content asks for — a 54px status tag, a
// 117px timestamp, and a count narrower than its own heading — rather than the
// slack they used to carry at the expense of the ones above.
const COLUMN_WIDTHS = {
  id: 270,
  test_case_name: 220,
  tests_run_id: 270,
  chain_id: 180,
  status: 80,
  start: 140,
  finish: 140,
  errors: 80,
  session_id: 270,
};

/**
 * Session routes of the runs on the page, keyed by the external session id a run
 * records. A lookup that finds nothing leaves the id out, and the cell then
 * renders the raw id without a link. Every id is asked for once.
 */
function useSessionPaths(runs: TestCaseRunView[]): Map<string, string> {
  const [paths, setPaths] = useState<Map<string, string>>(new Map());
  const requested = useRef(new Set<string>());

  useEffect(() => {
    const pending = [
      ...new Set(
        runs
          .map((run) => run.sessionId)
          .filter((id): id is string => !!id && !requested.current.has(id)),
      ),
    ];
    if (pending.length === 0) {
      return;
    }
    pending.forEach((id) => requested.current.add(id));
    void Promise.all(
      pending.map(async (externalId) => {
        try {
          const session = await api.getSessionByExternalId(externalId);
          return session?.id
            ? ([
                externalId,
                `/chains/${session.chainId}/sessions/${session.id}`,
              ] as const)
            : undefined;
        } catch {
          return undefined;
        }
      }),
    ).then((resolved) => {
      const found = resolved.filter((entry) => entry !== undefined);
      if (found.length > 0) {
        setPaths((previous) => new Map([...previous, ...found]));
      }
    });
  }, [runs]);

  return paths;
}

export type TestCaseRunsProps = {
  /** `chain-tab` lists the runs of one chain; `run-page` those of one test run. */
  variant?: "chain-tab" | "run-page";
};

export const TestCaseRuns: React.FC<TestCaseRunsProps> = ({
  variant = "chain-tab",
}) => {
  const { chainId, runId } = useParams<{ chainId: string; runId: string }>();
  const navigate = useNavigate();
  const notificationService = useNotificationService();
  const [searchString, setSearchString] = useState("");
  const [detailsRun, setDetailsRun] = useState<TestCaseRunView | null>(null);
  const tableWrapperRef = useRef<HTMLDivElement>(null);

  const { filters, filterButton } = useTestingFilter("testCaseRuns", chainId);
  const permissions = useMemo(() => getTestingPermissions(chainId), [chainId]);
  const sectionPath = chainId
    ? `/chains/${chainId}/testing`
    : "/admintools/testing";

  const scopeFilters = useMemo<TestingFilter[] | undefined>(
    () =>
      runId
        ? [
            {
              feature: TESTING_TESTS_RUN_FEATURE,
              condition: TestingFilterCondition.IS,
              values: [runId],
            },
          ]
        : undefined,
    [runId],
  );

  const {
    items,
    isLoading,
    allLoaded,
    loadMore,
    refresh,
    getChainName,
    exportEntities,
    sortBy,
    sortOrder,
    handleTableChange,
    selectedRowKeys,
    hasSelection,
    selectAllMatching,
    rowSelection,
    clearSelection,
    collectTargetIds,
  } = useTestingEntityList<TestCaseRunView>({
    source: testCaseRunsListSource,
    chainId,
    filters,
    searchString,
    // Newest first: a run list is read from its latest attempt backwards.
    initialSortBy: "start",
    initialSortOrder: TestingSortOrder.DESC,
    scopeFilters,
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
        selected.has(String(run.id)) && isTestCaseRunCancellable(run.status),
    );
  }, [items, selectedRowKeys, selectAllMatching]);

  const sessionPaths = useSessionPaths(items);

  const errorsPath = useCallback(
    (run: TestCaseRunView) =>
      runId
        ? `/admintools/testing/test-runs/${runId}/${run.id}`
        : `${sectionPath}/test-case-runs/${run.id}`,
    [runId, sectionPath],
  );

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
      notificationService.requestFailed(
        "Failed to export test case runs",
        error,
      );
    }
  }, [selectedRowKeys, collectTargetIds, exportEntities, notificationService]);

  const { isStarting, startRun } = useTestsRunStarter({
    chainId,
    source: TestsRunSource.TEST_CASE_RUNS,
    collectTargetIds,
  });

  const cancelSelected = useCallback(async () => {
    try {
      const ids = await collectTargetIds();
      if (ids.length === 0) {
        return;
      }
      await api.cancelTestCaseRuns(ids);
      clearSelection();
      refresh();
    } catch (error) {
      notificationService.requestFailed(
        "Failed to cancel test case runs",
        error,
      );
    }
  }, [collectTargetIds, clearSelection, refresh, notificationService]);

  // The service cancels the cases that have not started yet and leaves a running
  // one alone, so the wording promises no more than that.
  const handleCancel = useCallback(() => {
    if (selectedRowKeys.length === 0) {
      return;
    }
    const target = selectAllMatching
      ? "all test case runs that match the filters"
      : `${selectedRowKeys.length} test case run${selectedRowKeys.length === 1 ? "" : "s"}`;
    confirmAndRun({
      title: "Cancel Test Case Runs",
      content: `Cancel ${target}? A case that already started keeps running.`,
      onOk: cancelSelected,
    });
  }, [selectedRowKeys, selectAllMatching, cancelSelected]);

  const renderTestCaseCell = useCallback(
    (run: TestCaseRunView) => {
      if (!run.testCaseName) {
        return formatOptional(null);
      }
      if (!run.testCaseId) {
        return run.testCaseName;
      }
      return (
        <a
          onClick={(event) => {
            event.stopPropagation();
            void navigate(`${sectionPath}/test-cases/${run.testCaseId}`);
          }}
        >
          {run.testCaseName}
        </a>
      );
    },
    [navigate, sectionPath],
  );

  const renderSessionCell = useCallback(
    (run: TestCaseRunView) => {
      if (!run.sessionId) {
        return formatOptional(null);
      }
      const path = sessionPaths.get(run.sessionId);
      if (!path) {
        return run.sessionId;
      }
      return (
        <a
          onClick={(event) => {
            event.stopPropagation();
            void navigate(path);
          }}
        >
          {run.sessionId}
        </a>
      );
    },
    [navigate, sessionPaths],
  );

  const columnDefinitions = useMemo<ColumnsTypeWithSettings<TestCaseRunView>>(
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
              void navigate(errorsPath(run));
            }}
          >
            {run.id}
          </a>
        ),
      },
      {
        title: "Test Case",
        key: "test_case_name",
        sorter: true,
        render: (_, run) => renderTestCaseCell(run),
      },
      ...(runId
        ? [
            {
              title: "Chain",
              key: "chain_id",
              sorter: true,
              render: (_: unknown, run: TestCaseRunView) =>
                !run.chainId ? (
                  formatOptional(null)
                ) : (
                  <a
                    onClick={(event) => {
                      event.stopPropagation();
                      void navigate(`/chains/${run.chainId}`);
                    }}
                  >
                    {getChainName(run.chainId)}
                  </a>
                ),
            },
          ]
        : [
            {
              title: "Test Run",
              key: "tests_run_id",
              render: (_: unknown, run: TestCaseRunView) =>
                !run.testsRunId ? (
                  formatOptional(null)
                ) : (
                  <a
                    onClick={(event) => {
                      event.stopPropagation();
                      void navigate(
                        `/admintools/testing/test-runs/${run.testsRunId}`,
                      );
                    }}
                  >
                    {run.testsRunId}
                  </a>
                ),
            },
          ]),
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
        title: "Errors",
        dataIndex: "errors",
        key: "errors",
        sorter: true,
        // A count of zero stays plain: the errors page would open empty, and the
        // Id cell already leads there for a run worth inspecting.
        render: (_, run) =>
          run.errors > 0 ? (
            <a
              onClick={(event) => {
                event.stopPropagation();
                void navigate(errorsPath(run));
              }}
            >
              {run.errors}
            </a>
          ) : (
            run.errors
          ),
      },
      {
        title: "Session",
        key: "session_id",
        render: (_, run) => renderSessionCell(run),
      },
    ],
    [
      runId,
      navigate,
      errorsPath,
      getChainName,
      renderTestCaseCell,
      renderSessionCell,
    ],
  );

  const { orderedColumns, columnSettingsButton } =
    useColumnSettingsBasedOnColumnsType<TestCaseRunView>(
      runId ? "testCaseRunsTableRun" : "testCaseRunsTableChain",
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
            "data-testid": "test-case-runs-refresh",
            iconName: "refresh",
            onClick: handleRefresh,
          }}
        />
        <ProtectedButton
          require={permissions.execute}
          tooltipProps={{ title: "Restart selected test case runs" }}
          buttonProps={{
            "data-testid": "test-case-runs-restart",
            iconName: "play",
            loading: isStarting,
            disabled: isStarting || !hasSelection,
            onClick: () => void startRun(),
          }}
        />
        <ProtectedButton
          require={permissions.execute}
          tooltipProps={{ title: "Cancel selected test case runs" }}
          buttonProps={{
            "data-testid": "test-case-runs-cancel",
            iconName: "stop",
            disabled: !canCancelSelection,
            onClick: handleCancel,
          }}
        />
        <ProtectedButton
          require={permissions.export}
          tooltipProps={{ title: "Export selected test case runs" }}
          buttonProps={{
            "data-testid": "test-case-runs-export",
            iconName: "cloudDownload",
            disabled: !hasSelection,
            onClick: () => void handleExport(),
          }}
        />
      </>
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
    ],
  );

  const toolbar = useMemo(
    () => (
      <TableToolbar
        variant={variant === "run-page" ? "admin" : "chain-tab"}
        search={{
          value: searchString,
          onChange: setSearchString,
          placeholder: "Search test case runs...",
          allowClear: true,
        }}
        filterButton={filterButton}
        columnSettingsButton={columnSettingsButton}
        actions={toolbarActions}
      />
    ),
    [variant, searchString, filterButton, columnSettingsButton, toolbarActions],
  );

  // Re-registered on the state the toolbar reads, not on the toolbar node, which
  // is a fresh element on every render and would loop through the header's own
  // re-render.
  useRegisterChainHeaderActions(variant === "chain-tab" ? toolbar : undefined, [
    variant,
    searchString,
    sortBy,
    sortOrder,
    selectedRowKeys,
    selectAllMatching,
    canCancelSelection,
    allLoaded,
    filters,
    permissions,
    isStarting,
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
        <Table<TestCaseRunView>
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
          locale={{ emptyText: tableEmpty("No test case runs to display") }}
          scroll={tableScroll(scrollX, items.length)}
          components={components}
          onChange={handleTableChange}
          onRow={(run) => ({
            onClick: () => setDetailsRun(run),
          })}
        />
      </div>
    </TablePageLayout>
  );

  return (
    <>
      {variant === "run-page" ? (
        <Flex vertical className={commonStyles.container}>
          <AdminToolsHeader
            title="Test Case Runs"
            iconName="carryOut"
            toolbar={toolbar}
          />
          {table}
        </Flex>
      ) : (
        table
      )}
      <TestCaseRunDrawer
        run={detailsRun}
        chainName={getChainName(detailsRun?.chainId)}
        testCasePath={
          detailsRun?.testCaseId
            ? `${sectionPath}/test-cases/${detailsRun.testCaseId}`
            : undefined
        }
        errorsPath={detailsRun ? errorsPath(detailsRun) : undefined}
        sessionPath={
          detailsRun?.sessionId
            ? sessionPaths.get(detailsRun.sessionId)
            : undefined
        }
        open={detailsRun !== null}
        onClose={() => setDetailsRun(null)}
      />
    </>
  );
};

export default TestCaseRuns;
