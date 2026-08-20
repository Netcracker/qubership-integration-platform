import React, { useCallback, useEffect, useMemo, useState } from "react";
import { Breadcrumb, Flex, Table, Typography } from "antd";
import type { TableRowSelection } from "antd/lib/table/interface";
import { useNavigate, useParams } from "react-router";
import { api } from "../../api/api.ts";
import { TestCaseRunView, TestingValidationError } from "../../api/apiTypes.ts";
import { AdminToolsHeader } from "../../components/admin_tools/AdminToolsHeader.tsx";
import commonStyles from "../../components/admin_tools/CommonStyle.module.css";
import { TablePageLayout } from "../../components/TablePageLayout.tsx";
import { nameLinkStyle } from "../../components/table/nameLinkStyle.ts";
import { tableEmpty } from "../../components/table/tableEmpty.tsx";
import { tableScroll } from "../../components/table/tableScroll.ts";
import { matchesByFields } from "../../components/table/tableSearch.ts";
import { TableToolbar } from "../../components/table/TableToolbar.tsx";
import {
  ColumnsTypeWithSettings,
  useColumnSettingsBasedOnColumnsType,
} from "../../components/table/useColumnSettingsButton.tsx";
import { useColumnsWithResizeAndScroll } from "../../components/table/useColumnsWithResizeAndScroll.tsx";
import { getTestingPermissions } from "../../components/testing/testingPermissions.ts";
import { RunStatusTag } from "../../components/testing/TestingTags.tsx";
import { TESTING_SELECTION_COLUMN_WIDTH } from "../../hooks/testing/useTestingEntityList.ts";
import { useNotificationService } from "../../hooks/useNotificationService.tsx";
import { downloadFile } from "../../misc/download-utils.ts";
import { formatOptional, formatTimestamp } from "../../misc/format-utils.ts";
import { toStringIds } from "../../misc/selection-utils.ts";
import { ProtectedButton } from "../../permissions/ProtectedButton.tsx";
import { useRegisterChainHeaderActions } from "../ChainHeaderActionsContext.tsx";

const COLUMN_WIDTHS = {
  matcher: 260,
  description: 280,
  message: 460,
};

// Shared empties, so resetting a screen that is already empty changes no state
// and costs no render.
const NO_ERRORS: TestingValidationError[] = [];
const NO_SELECTION: React.Key[] = [];

function errorMatchesSearch(
  error: TestingValidationError,
  term: string,
): boolean {
  return matchesByFields(term, [
    error.matcher?.name,
    error.matcherId,
    error.matcher?.description,
    error.message,
  ]);
}

/**
 * The validation errors of one test case run, reached from the case run list of a
 * chain and from the run drill-down of the admin section. The whole list arrives in
 * one request, so search and selection work on the loaded rows.
 */
export const TestCaseRunErrors: React.FC = () => {
  const { chainId, runId, caseRunId } = useParams<{
    chainId?: string;
    runId?: string;
    caseRunId: string;
  }>();
  const navigate = useNavigate();
  const notificationService = useNotificationService();

  const [errors, setErrors] = useState<TestingValidationError[]>(NO_ERRORS);
  const [run, setRun] = useState<TestCaseRunView | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [searchString, setSearchString] = useState("");
  const [selectedRowKeys, setSelectedRowKeys] =
    useState<React.Key[]>(NO_SELECTION);
  const [reloadToken, setReloadToken] = useState(0);

  const permissions = useMemo(() => getTestingPermissions(chainId), [chainId]);
  const sectionPath = chainId
    ? `/chains/${chainId}/testing`
    : "/admintools/testing";

  // Another case run brings other rows: the ones on screen and the choice made
  // in them go before it is read, so a read that fails cannot leave the rows of
  // the case run before it on screen or hand their ids to the export. A refresh
  // keeps them, which is why this does not follow the reload token.
  useEffect(() => {
    setErrors(NO_ERRORS);
    setRun(null);
    setSelectedRowKeys(NO_SELECTION);
  }, [caseRunId]);

  // The run is read beside the errors: it names the case the rules belong to,
  // which is what the rule links and the breadcrumb need. Either request may fail
  // on its own, so neither takes the other down with it.
  useEffect(() => {
    if (!caseRunId) {
      return;
    }
    let canceled = false;
    setIsLoading(true);
    void Promise.all([
      api.getTestCaseRunErrors(caseRunId).catch((error) => {
        notificationService.requestFailed(
          "Failed to load the validation errors",
          error,
        );
        return null;
      }),
      api.getTestCaseRun(caseRunId).catch((error) => {
        notificationService.requestFailed(
          "Failed to load the test case run",
          error,
        );
        return null;
      }),
    ])
      .then(([loadedErrors, loadedRun]) => {
        if (canceled) {
          return;
        }
        if (loadedErrors) {
          setErrors(loadedErrors);
        }
        if (loadedRun) {
          setRun(loadedRun);
        }
      })
      .finally(() => {
        if (!canceled) {
          setIsLoading(false);
        }
      });
    return () => {
      canceled = true;
    };
  }, [caseRunId, reloadToken, notificationService]);

  const rows = useMemo(
    () => errors.filter((error) => errorMatchesSearch(error, searchString)),
    [errors, searchString],
  );

  // Rows the search has hidden are not rows the export may carry, so the choice
  // does not survive a change to it.
  useEffect(() => {
    setSelectedRowKeys([]);
  }, [searchString]);

  const handleRefresh = useCallback(() => {
    setSelectedRowKeys([]);
    setReloadToken((token) => token + 1);
  }, []);

  // This screen keeps its own selection rather than useTestingEntityList's, so
  // the flag the other testing lists read off the hook is derived here.
  const hasSelection = selectedRowKeys.length > 0;

  const handleExport = useCallback(async () => {
    if (selectedRowKeys.length === 0) {
      return;
    }
    try {
      downloadFile(
        await api.exportTestCaseRunErrors(toStringIds(selectedRowKeys)),
      );
    } catch (error) {
      notificationService.requestFailed(
        "Failed to export the validation errors",
        error,
      );
    }
  }, [selectedRowKeys, notificationService]);

  const renderRuleCell = useCallback(
    (error: TestingValidationError) => {
      const label = error.matcher?.name || error.matcherId;
      if (!label) {
        return formatOptional(null);
      }
      if (!run?.testCaseId) {
        return label;
      }
      return (
        <a
          style={nameLinkStyle}
          onClick={(event) => {
            event.stopPropagation();
            void navigate(
              `${sectionPath}/test-cases/${run.testCaseId}/response-validation`,
            );
          }}
        >
          {label}
        </a>
      );
    },
    [navigate, run?.testCaseId, sectionPath],
  );

  const columnDefinitions = useMemo<
    ColumnsTypeWithSettings<TestingValidationError>
  >(
    () => [
      {
        title: "Rule",
        key: "matcher",
        settings: { visibilityLocked: true, orderLocked: true },
        render: (_, error) => renderRuleCell(error),
      },
      {
        title: "Description",
        key: "description",
        render: (_, error) => formatOptional(error.matcher?.description),
      },
      {
        title: "Message",
        key: "message",
        render: (_, error) => formatOptional(error.message),
      },
    ],
    [renderRuleCell],
  );

  const { orderedColumns, columnSettingsButton } =
    useColumnSettingsBasedOnColumnsType<TestingValidationError>(
      "testCaseRunErrorsTable",
      columnDefinitions,
    );

  const { columnsWithResize, scrollX, components } =
    useColumnsWithResizeAndScroll(orderedColumns, COLUMN_WIDTHS, {
      selectionColumnWidth: TESTING_SELECTION_COLUMN_WIDTH,
    });

  const rowSelection: TableRowSelection<TestingValidationError> = {
    type: "checkbox",
    selectedRowKeys,
    onChange: setSelectedRowKeys,
  };

  const runSummary = useMemo(
    () =>
      run ? (
        <Flex align="center" gap={8}>
          <RunStatusTag status={run.status} />
          <Typography.Text type="secondary">
            {formatTimestamp(run.start)} – {formatTimestamp(run.finish)}
          </Typography.Text>
        </Flex>
      ) : null,
    [run],
  );

  const toolbarActions = useMemo(
    () => (
      <>
        <ProtectedButton
          require={permissions.view}
          tooltipProps={{ title: "Refresh", placement: "bottom" }}
          buttonProps={{
            "data-testid": "test-case-run-errors-refresh",
            iconName: "refresh",
            onClick: handleRefresh,
          }}
        />
        <ProtectedButton
          require={permissions.export}
          tooltipProps={{ title: "Export selected validation errors" }}
          buttonProps={{
            "data-testid": "test-case-run-errors-export",
            iconName: "cloudDownload",
            disabled: !hasSelection,
            onClick: () => void handleExport(),
          }}
        />
      </>
    ),
    [permissions, handleRefresh, hasSelection, handleExport],
  );

  const toolbar = useMemo(
    () => (
      <TableToolbar
        variant={chainId ? "chain-tab" : "admin"}
        leading={runSummary}
        search={{
          value: searchString,
          onChange: setSearchString,
          placeholder: "Search validation errors...",
          allowClear: true,
        }}
        columnSettingsButton={columnSettingsButton}
        actions={toolbarActions}
      />
    ),
    [chainId, runSummary, searchString, columnSettingsButton, toolbarActions],
  );

  // Re-registered on the state the toolbar reads, not on the toolbar node, which
  // is a fresh element on every render and would loop through the header's own
  // re-render.
  useRegisterChainHeaderActions(chainId ? toolbar : undefined, [
    chainId,
    searchString,
    selectedRowKeys,
    run,
    permissions,
  ]);

  const caseRunTitle = run?.testCaseName ?? caseRunId ?? "";

  const breadcrumb = (
    <Breadcrumb
      items={
        chainId
          ? [
              {
                title: (
                  <a
                    onClick={() =>
                      void navigate(`${sectionPath}/test-case-runs`)
                    }
                  >
                    Test Case Runs
                  </a>
                ),
              },
              { title: caseRunTitle },
            ]
          : [
              {
                title: (
                  <a
                    onClick={() =>
                      void navigate("/admintools/testing/test-runs")
                    }
                  >
                    Test Runs
                  </a>
                ),
              },
              {
                title: (
                  <a
                    onClick={() =>
                      void navigate(`/admintools/testing/test-runs/${runId}`)
                    }
                  >
                    {runId}
                  </a>
                ),
              },
              { title: caseRunTitle },
            ]
      }
    />
  );

  const table = (
    <TablePageLayout>
      <Table<TestingValidationError>
        size="small"
        className="flex-table"
        columns={columnsWithResize}
        rowSelection={rowSelection}
        dataSource={rows}
        pagination={false}
        loading={isLoading}
        rowKey="id"
        sticky
        style={{ flex: 1, minHeight: 0 }}
        locale={{ emptyText: tableEmpty("No validation errors to display") }}
        scroll={tableScroll(scrollX, rows.length)}
        components={components}
      />
    </TablePageLayout>
  );

  return chainId ? (
    <Flex vertical gap={8} style={{ flex: 1, minHeight: 0, minWidth: 0 }}>
      {breadcrumb}
      {table}
    </Flex>
  ) : (
    <Flex vertical className={commonStyles.container}>
      <AdminToolsHeader
        title="Validation Errors"
        iconName="carryOut"
        toolbar={toolbar}
      />
      {breadcrumb}
      {table}
    </Flex>
  );
};

export default TestCaseRunErrors;
