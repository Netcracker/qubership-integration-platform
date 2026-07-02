import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { Flex, Table } from "antd";
import { message } from "../misc/antd-app.ts";
import { useNavigate, useParams } from "react-router";
import {
  Checkpoint,
  CheckpointSession,
  ExecutionStatus,
  Session,
  SessionFilterAndSearchRequest,
  SessionFilterCondition,
  SessionFilterFeature,
  SessionFilterRequest,
} from "../api/apiTypes.ts";
import {
  capitalize,
  formatDuration,
  formatSnakeCased,
  formatUTCSessionDate,
} from "../misc/format-utils.ts";
import {
  FilterDropdownProps,
  TableRowSelection,
} from "antd/lib/table/interface";
import { api } from "../api/api.ts";
import { SessionStatus } from "../components/sessions/SessionStatus.tsx";
import { useModalsContext } from "../Modals.tsx";
import { downloadFile } from "../misc/download-utils.ts";
import { ImportSessions } from "../components/modal/ImportSessions.tsx";
import {
  isTimestampFilter,
  TimestampColumnFilterDropdown,
  TimestampFilter,
  TimestampFilterCondition,
} from "../components/table/TimestampColumnFilterDropdown.tsx";
import type { FilterValue } from "antd/es/table/interface";
import {
  isTextFilter,
  TextColumnFilterDropdown,
  TextFilter,
  TextFilterCondition,
} from "../components/table/TextColumnFilterDropdown.tsx";
import { useNotificationService } from "../hooks/useNotificationService.tsx";
import { parseJson } from "../misc/json-helper.ts";
import { TablePageLayout } from "../components/TablePageLayout.tsx";
import { filterOutByIds, toStringIds } from "../misc/selection-utils.ts";
import { useRegisterChainHeaderActions } from "./ChainHeaderActionsContext.tsx";
import { confirmAndRun } from "../misc/confirm-utils.ts";
import { ProtectedButton } from "../permissions/ProtectedButton.tsx";
import {
  ColumnsTypeWithSettings,
  useColumnSettingsBasedOnColumnsType,
} from "../components/table/useColumnSettingsButton.tsx";
import { tableScroll } from "../components/table/tableScroll.ts";
import { useColumnsWithResizeAndScroll } from "../components/table/useColumnsWithResizeAndScroll.tsx";
import { tableEmpty } from "../components/table/tableEmpty.tsx";
import commonStyles from "../components/admin_tools/CommonStyle.module.css";
import { TableToolbar } from "../components/table/TableToolbar.tsx";
import { AdminToolsHeader } from "../components/admin_tools/AdminToolsHeader.tsx";

type SessionWithCheckpoints = Session & {
  checkpoints?: Checkpoint[];
};

type SessionTableItem = SessionWithCheckpoints & {
  children?: SessionTableItem[];
};

function sortByStartTime(items?: SessionTableItem[]) {
  items?.sort((i1, i2) => compareTimestamps(i1.started, i2.started));
  items?.forEach((i) => sortByStartTime(i.children));
}

function isCorrelationItem(item: SessionTableItem) {
  return item.correlationId === item.id;
}

function convertTimestampFilterCondition(
  condition: TimestampFilterCondition,
): SessionFilterCondition {
  switch (condition) {
    case "is-before":
      return SessionFilterCondition.IS_BEFORE;
    case "is-after":
      return SessionFilterCondition.IS_AFTER;
    case "is-within":
      return SessionFilterCondition.IS_WITHIN;
  }
}

function convertTextFilterCondition(
  condition: TextFilterCondition,
): SessionFilterCondition {
  switch (condition) {
    case "contains":
      return SessionFilterCondition.CONTAINS;
    case "not-contains":
      return SessionFilterCondition.DOES_NOT_CONTAIN;
    case "starts-with":
      return SessionFilterCondition.STARTS_WITH;
    case "ends-with":
      return SessionFilterCondition.ENDS_WITH;
    default:
      return SessionFilterCondition.CONTAINS;
  }
}

function convertTimestampFilter(
  feature: SessionFilterFeature,
  filterStr: string,
): SessionFilterRequest {
  const timestampFilter = parseJson<TimestampFilter>(
    filterStr,
    isTimestampFilter,
  );
  const condition = convertTimestampFilterCondition(timestampFilter.condition);
  return {
    feature,
    condition,
    value: timestampFilter.value?.[0].toString() ?? "",
  };
}

function compareTimestamps(s1: string, s2: string): number {
  return Date.parse(s2) - Date.parse(s1);
}

/** rc-table expand column when rows have nested `children`. */
const SESSIONS_EXPAND_COLUMN_WIDTH = 48;
const SESSIONS_SELECTION_COLUMN_WIDTH = 48;

const INITIAL_PAGE_COUNT = 2;

export const SESSIONS_LOAD_SENTINEL_TEST_ID = "sessions-load-sentinel";

type SessionsProps = {
  variant?: "chain-tab" | "admin-page";
};

export const Sessions: React.FC<SessionsProps> = ({
  variant = "chain-tab",
}) => {
  const { chainId } = useParams<{ chainId: string }>();
  const { showModal } = useModalsContext();
  const navigate = useNavigate();
  const [isLoading, setIsLoading] = useState(true);
  const [sessions, setSessions] = useState<SessionWithCheckpoints[]>([]);
  const [selectedRowKeys, setSelectedRowKeys] = useState<React.Key[]>([]);
  const [offset, setOffset] = useState(0);
  const [allSessionsLoaded, setAllSessionsLoaded] = useState(false);
  const [filters, setFilters] = useState<SessionFilterAndSearchRequest>({
    filterRequestList: [],
    searchString: "",
  });
  const notificationService = useNotificationService();
  const tableWrapperRef = useRef<HTMLDivElement>(null);

  const tableData = useMemo<SessionTableItem[]>(() => {
    const rowMap = new Map<string, SessionTableItem>(
      sessions.map((session) => [session.id, { ...session }]),
    );
    const itemsByCorrelationId = new Map<string, SessionTableItem[]>();
    const items: SessionTableItem[] = [];
    Array.from(rowMap.values()).forEach((item) => {
      if (item.parentSessionId && rowMap.has(item.parentSessionId)) {
        const parentItem = rowMap.get(item.parentSessionId);
        parentItem!.children = parentItem?.children
          ? [...parentItem.children, item]
          : [item];
      } else if (item.correlationId) {
        if (itemsByCorrelationId.has(item.correlationId)) {
          itemsByCorrelationId.get(item.correlationId)?.push(item);
        } else {
          itemsByCorrelationId.set(item.correlationId, [item]);
        }
      } else {
        items.push(item);
      }
    });

    const correlationIdItems = Array.from(itemsByCorrelationId.entries()).map(
      ([correlationId, items]) => {
        const { started, finished } = items.reduce(
          (acc, item) => ({
            started:
              compareTimestamps(acc.started, item.started) > 0
                ? acc.started
                : item.started,
            finished:
              compareTimestamps(acc.finished, item.finished) < 0
                ? acc.finished
                : item.finished,
          }),
          { started: items[0].started, finished: items[0].finished },
        );
        const duration = Date.parse(finished) - Date.parse(started);
        return {
          ...items[0],
          id: correlationId,
          children: items,
          started,
          finished,
          duration,
          importedSession: false,
          externalSessionCipId: "",
          domain: "",
          engineAddress: "",
          loggingLevel: "",
          snapshotName: "",
          parentSessionId: "",
        };
      },
    );
    items.push(...correlationIdItems);

    sortByStartTime(items);
    return items;
  }, [sessions]);

  const setTableFilters = (
    tableFilters: Record<string, FilterValue | null>,
  ) => {
    const filterRequestList: SessionFilterRequest[] = [];
    if (tableFilters.chainName) {
      const textFilter = parseJson<TextFilter>(
        tableFilters.chainName[0].toString(),
        isTextFilter,
      );
      filterRequestList.push({
        feature: SessionFilterFeature.CHAIN_NAME,
        condition: convertTextFilterCondition(textFilter.condition),
        value: textFilter.value,
      });
    }
    if (tableFilters.engineAddress) {
      const textFilter = parseJson<TextFilter>(
        tableFilters.engineAddress[0].toString(),
        isTextFilter,
      );
      filterRequestList.push({
        feature: SessionFilterFeature.ENGINE,
        condition: convertTextFilterCondition(textFilter.condition),
        value: textFilter.value,
      });
    }
    if (tableFilters.started) {
      filterRequestList.push(
        convertTimestampFilter(
          SessionFilterFeature.START_TIME,
          tableFilters.started[0].toString(),
        ),
      );
    }
    if (tableFilters.finished) {
      filterRequestList.push(
        convertTimestampFilter(
          SessionFilterFeature.FINISH_TIME,
          tableFilters.finished[0].toString(),
        ),
      );
    }
    if (tableFilters.executionStatus) {
      filterRequestList.push({
        feature: SessionFilterFeature.STATUS,
        condition: SessionFilterCondition.IN,
        value: tableFilters.executionStatus.join(","),
      });
    }
    setFilters((prevFilters) => ({ ...prevFilters, filterRequestList }));
  };

  const fetchSessions = useCallback(
    async (initialOffset: number) => {
      const isInitialLoad = initialOffset === 0;
      setIsLoading(true);
      try {
        const pagesToFetch = isInitialLoad ? INITIAL_PAGE_COUNT : 1;
        const fetched: Session[] = [];
        let currentOffset = initialOffset;
        let reachedEnd = false;
        for (let i = 0; i < pagesToFetch; i++) {
          const response = await api.getSessions(chainId, filters, {
            offset: currentOffset,
          });
          fetched.push(...response.sessions);
          currentOffset = response.offset;
          if (response.sessions.length === 0) {
            reachedEnd = true;
            break;
          }
        }

        const ids = fetched.map((s) => s.id);
        const checkpointSessions: CheckpointSession[] =
          ids.length > 0 ? await api.getCheckpointSessions(ids) : [];
        const checkpointsById = new Map<string, Checkpoint[]>();
        checkpointSessions.forEach((cs) =>
          checkpointsById.set(cs.id, cs.checkpoints),
        );
        const withCheckpoints: SessionWithCheckpoints[] = fetched.map((s) => ({
          ...s,
          checkpoints: checkpointsById.get(s.id),
        }));

        setSessions((prev) =>
          isInitialLoad ? withCheckpoints : [...prev, ...withCheckpoints],
        );
        setOffset(currentOffset);
        setAllSessionsLoaded(reachedEnd);
      } catch (error) {
        notificationService.requestFailed("Failed to fetch sessions", error);
      } finally {
        setIsLoading(false);
      }
    },
    [chainId, filters, notificationService],
  );

  const retryFromLastCheckpoint = useCallback(
    async (chainId: string, sessionId: string) => {
      try {
        await api.retrySessionFromCheckpoint(chainId, sessionId);
        message.info(
          "Session was retried successfully. Please update table to see session result.",
        );
      } catch (error) {
        notificationService.requestFailed("Failed to retry session", error);
      }
    },
    [notificationService],
  );

  const tableColumnDefinitions = useMemo<
    ColumnsTypeWithSettings<SessionTableItem>
  >(
    () => [
      {
        title: "ID",
        dataIndex: "id",
        key: "id",
        settings: {
          visibilityLocked: true,
          orderLocked: true,
        },
        render: (_, item) =>
          isCorrelationItem(item) ? (
            <span style={{ fontWeight: 600 }}>{item.id}</span>
          ) : (
            <Flex gap={4}>
              <a
                onClick={() =>
                  void navigate(`/chains/${item.chainId}/sessions/${item.id}`)
                }
              >
                {item.id}
              </a>
              {item.checkpoints && item.checkpoints?.length > 0 ? (
                <ProtectedButton
                  require={{ session: ["execute"] }}
                  tooltipProps={{}}
                  buttonProps={{
                    size: "small",
                    type: "text",
                    iconName: "redo",
                    onClick: () =>
                      void retryFromLastCheckpoint(item.chainId, item.id),
                  }}
                />
              ) : null}
            </Flex>
          ),
      },
      ...(chainId
        ? []
        : [
            {
              title: "Chain",
              dataIndex: "chainName",
              key: "chainName",
              filterDropdown: (props: FilterDropdownProps) => (
                <TextColumnFilterDropdown {...props} enableExact={false} />
              ),
              render: (_: unknown, item: SessionTableItem) => (
                <a onClick={() => void navigate(`/chains/${item.chainId}`)}>
                  {item.chainName}
                </a>
              ),
            },
          ]),
      {
        title: "Status",
        dataIndex: "executionStatus",
        key: "executionStatus",
        filters: Object.values(ExecutionStatus).map((value) => ({
          value,
          text: formatSnakeCased(value),
        })),
        render: (_, item) =>
          isCorrelationItem(item) ? (
            <>{`${item.children?.length} session${item.children?.length === 1 ? "" : "s"}`}</>
          ) : (
            <SessionStatus status={item.executionStatus} />
          ),
      },
      {
        title: "Start time",
        dataIndex: "started",
        key: "started",
        filterDropdown: (props) => <TimestampColumnFilterDropdown {...props} />,
        render: (_, session) => (
          <>{formatUTCSessionDate(session.started, true)}</>
        ),
      },
      {
        title: "Finish time",
        dataIndex: "finished",
        key: "finished",
        filterDropdown: (props) => <TimestampColumnFilterDropdown {...props} />,
        render: (_, session) => (
          <>{formatUTCSessionDate(session.finished, true)}</>
        ),
      },
      {
        title: "Session level",
        dataIndex: "loggingLevel",
        key: "loggingLevel",
        render: (_, session) => <>{capitalize(session.loggingLevel)}</>,
      },
      {
        title: "Duration",
        dataIndex: "duration",
        key: "duration",
        render: (_, session) => <>{formatDuration(session.duration)}</>,
      },
      {
        title: "Snapshot",
        dataIndex: "snapshotName",
        key: "snapshotName",
        render: (_, session) => <>{session.snapshotName}</>,
      },
      {
        title: "Engine",
        dataIndex: "engineAddress",
        key: "engineAddress",
        filterDropdown: (props) => (
          <TextColumnFilterDropdown {...props} enableExact={false} />
        ),
        render: (_, item) =>
          isCorrelationItem(item) ? (
            <></>
          ) : (
            <>{`${item.domain} (${item.engineAddress})`}</>
          ),
      },
    ],
    [chainId, navigate, retryFromLastCheckpoint],
  );

  const sessionsColumnSettingsKey = chainId
    ? "sessionsTableChain"
    : "sessionsTableAdmin";

  const { orderedColumns, columnSettingsButton } =
    useColumnSettingsBasedOnColumnsType<SessionTableItem>(
      sessionsColumnSettingsKey,
      tableColumnDefinitions,
    );

  const { columnsWithResize, scrollX, components } =
    useColumnsWithResizeAndScroll(
      orderedColumns,
      {
        id: 220,
        chainName: 160,
        executionStatus: 140,
        started: 168,
        finished: 168,
        loggingLevel: 120,
        duration: 100,
        snapshotName: 110,
        engineAddress: 150,
      },
      {
        expandColumnWidth: SESSIONS_EXPAND_COLUMN_WIDTH,
        selectionColumnWidth: SESSIONS_SELECTION_COLUMN_WIDTH,
      },
    );

  /* Sentinel is appended as a sibling of `<table>` inside `.ant-table-body`
   * (not inside the table) because Antd's sticky table syncs header/body
   * column widths via a shared colgroup — any extra cell would desync them. */
  useEffect(() => {
    if (isLoading || allSessionsLoaded) return;
    const wrapper = tableWrapperRef.current;
    if (!wrapper) return;
    const root = wrapper.querySelector(".ant-table-body");
    if (!root) return;

    const sentinel = document.createElement("div");
    sentinel.dataset.testid = SESSIONS_LOAD_SENTINEL_TEST_ID;
    sentinel.style.height = "1px";
    root.appendChild(sentinel);

    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) {
          void fetchSessions(offset);
        }
      },
      { root, rootMargin: "200px" },
    );
    observer.observe(sentinel);

    return () => {
      observer.disconnect();
      sentinel.remove();
    };
  }, [isLoading, allSessionsLoaded, offset, fetchSessions]);

  const onSelectChange = (newSelectedRowKeys: React.Key[]) => {
    setSelectedRowKeys(newSelectedRowKeys);
  };

  const deleteSelectedSessions = useCallback(async () => {
    try {
      const ids = toStringIds(selectedRowKeys);
      if (selectedRowKeys.length === sessions.length) {
        await api.deleteSessionsByChainId(chainId);
        setSessions([]);
      } else {
        await api.deleteSessions(ids);
        setSessions((prevSessions) => filterOutByIds(prevSessions, ids));
      }
    } catch (error) {
      notificationService.requestFailed("Failed to delete sessions", error);
    }
  }, [selectedRowKeys, sessions.length, chainId, notificationService]);

  const onDeleteBtnClick = useCallback(() => {
    if (selectedRowKeys.length === 0) return;
    confirmAndRun({
      title: "Delete Sessions",
      content: `Are you sure you want to delete ${selectedRowKeys.length} session(s)?`,
      onOk: deleteSelectedSessions,
    });
  }, [selectedRowKeys, deleteSelectedSessions]);

  const onExportBtnClick = useCallback(async () => {
    if (selectedRowKeys.length === 0) return;
    try {
      const ids = toStringIds(selectedRowKeys);
      const file = await api.exportSessions(ids);
      downloadFile(file);
    } catch (error) {
      notificationService.requestFailed("Failed to export sessions", error);
    }
  }, [selectedRowKeys, notificationService]);

  const onImportBtnClick = useCallback(() => {
    showModal({
      component: (
        <ImportSessions
          onSuccess={(importedSessions) =>
            setSessions((prevSessions) => [
              ...importedSessions,
              ...prevSessions,
            ])
          }
        />
      ),
    });
  }, [showModal]);

  const onRefreshSessions = useCallback(() => {
    setSelectedRowKeys([]);
    void fetchSessions(0);
  }, [fetchSessions]);

  const rowSelection: TableRowSelection<SessionTableItem> = {
    type: "checkbox",
    selectedRowKeys,
    checkStrictly: false,
    onChange: onSelectChange,
  };

  useEffect(() => {
    void fetchSessions(0);
  }, [fetchSessions]);

  const sessionsToolbarActions = useMemo(
    () => (
      <>
        <ProtectedButton
          require={{ session: ["read"] }}
          tooltipProps={{ title: "Refresh", placement: "bottom" }}
          buttonProps={{
            "data-testid": "sessions-refresh",
            iconName: "refresh",
            onClick: () => onRefreshSessions(),
          }}
        />
        <ProtectedButton
          require={{ session: ["export"] }}
          tooltipProps={{ title: "Export selected sessions" }}
          buttonProps={{
            iconName: "cloudDownload",
            onClick: () => void onExportBtnClick(),
          }}
        />
        {chainId ? null : (
          <ProtectedButton
            require={{ session: ["import"] }}
            tooltipProps={{ title: "Import sessions" }}
            buttonProps={{
              iconName: "cloudUpload",
              onClick: onImportBtnClick,
            }}
          />
        )}
        <ProtectedButton
          require={{ session: ["delete"] }}
          tooltipProps={{ title: "Delete selected sessions" }}
          buttonProps={{
            iconName: "delete",
            onClick: onDeleteBtnClick,
          }}
        />
      </>
    ),
    [
      chainId,
      onRefreshSessions,
      onDeleteBtnClick,
      onExportBtnClick,
      onImportBtnClick,
    ],
  );

  const sessionsToolbar = useMemo(
    () => (
      <TableToolbar
        variant={variant === "admin-page" ? "admin" : "chain-tab"}
        search={{
          value: filters.searchString,
          onChange: (value) =>
            setFilters((prev) => ({ ...prev, searchString: value })),
          placeholder: "Search sessions...",
          allowClear: true,
        }}
        columnSettingsButton={columnSettingsButton}
        actions={sessionsToolbarActions}
      />
    ),
    [
      variant,
      filters.searchString,
      columnSettingsButton,
      sessionsToolbarActions,
    ],
  );

  useRegisterChainHeaderActions(
    variant === "chain-tab" ? sessionsToolbar : undefined,
    [
      filters.searchString,
      chainId,
      selectedRowKeys,
      onRefreshSessions,
      variant,
    ],
  );

  const sessionsTable = (
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
        <Table<SessionTableItem>
          size="small"
          className="flex-table"
          columns={columnsWithResize}
          rowSelection={rowSelection}
          dataSource={tableData}
          pagination={false}
          loading={isLoading}
          rowKey="id"
          sticky
          style={{ flex: 1, minHeight: 0 }}
          locale={{ emptyText: tableEmpty("No sessions recorded") }}
          scroll={tableScroll(scrollX, tableData.length)}
          components={components}
          onChange={(_, tableFilters) => setTableFilters(tableFilters)}
        />
      </div>
    </TablePageLayout>
  );

  return (
    <>
      {variant === "admin-page" ? (
        <Flex vertical className={commonStyles.container}>
          <AdminToolsHeader
            title="Sessions"
            iconName="snippets"
            toolbar={sessionsToolbar}
          />
          {sessionsTable}
        </Flex>
      ) : (
        sessionsTable
      )}
    </>
  );
};
