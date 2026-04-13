import React, {
  UIEvent,
  useCallback,
  useEffect,
  useMemo,
  useState,
} from "react";
import { Flex, message, Table } from "antd";
import { useNavigate, useParams } from "react-router";
import { TableProps } from "antd/lib/table";
import {
  Checkpoint,
  ExecutionStatus,
  PaginationOptions,
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
import { CompactSearch } from "../components/table/CompactSearch.tsx";
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
import chainPageStyles from "./Chain.module.css";
import { confirmAndRun } from "../misc/confirm-utils.ts";
import { ProtectedButton } from "../permissions/ProtectedButton.tsx";
import { useColumnSettingsBasedOnColumnsType } from "../components/table/useColumnSettingsButton.tsx";
import {
  attachResizeToColumns,
  sumScrollXForColumns,
  useTableColumnResize,
} from "../components/table/useTableColumnResize.tsx";
import commonStyles from "../components/admin_tools/CommonStyle.module.css";

type SessionTableItem = Session & {
  children?: SessionTableItem[];
  checkpoints?: Checkpoint[];
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

export const Sessions: React.FC = () => {
  const { chainId } = useParams<{ chainId: string }>();
  const { showModal } = useModalsContext();
  const navigate = useNavigate();
  const [isLoading, setIsLoading] = useState(false);
  const [sessions, setSessions] = useState<Session[]>([]);
  const [selectedRowKeys, setSelectedRowKeys] = useState<React.Key[]>([]);
  const [offset, setOffset] = useState(0);
  const [allSessionsLoaded, setAllSessionsLoaded] = useState(false);
  const [filters, setFilters] = useState<SessionFilterAndSearchRequest>({
    filterRequestList: [],
    searchString: "",
  });
  const [tableData, setTableData] = useState<SessionTableItem[]>([]);
  const [checkpointMap, setCheckpointMap] = useState<Map<string, Checkpoint[]>>(
    new Map(),
  );
  const [messageApi, contextHolder] = message.useMessage();
  const notificationService = useNotificationService();

  const updateTableData = useCallback(() => {
    setIsLoading(true);
    const rowMap = new Map<string, SessionTableItem>(
      sessions.map((session) => [
        session.id,
        { ...session, checkpoints: checkpointMap.get(session.id) },
      ]),
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
    setTableData(items);
    setIsLoading(false);
  }, [checkpointMap, sessions]);

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
    async (offset: number) => {
      const options: PaginationOptions = { offset };
      setIsLoading(true);
      try {
        const response = await api.getSessions(chainId, filters, options);
        setSessions((sessions) => [
          ...(offset ? sessions : []),
          ...response.sessions,
        ]);
        setOffset(response.offset);
        setAllSessionsLoaded(response.sessions.length === 0);
        const ids = response.sessions.map((session) => session.id);
        const checkpointSessions = await api.getCheckpointSessions(ids);
        setCheckpointMap((checkpointMap) => {
          const m = new Map(offset ? checkpointMap.entries() : []);
          checkpointSessions.forEach((session) =>
            m.set(session.id, session.checkpoints),
          );
          return m;
        });
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
        messageApi.info(
          "Session was retried successfully. Please update table to see session result.",
        );
      } catch (error) {
        notificationService.requestFailed("Failed to retry session", error);
      }
    },
    [messageApi, notificationService],
  );

  const buildColumns = useCallback(
    (): TableProps<SessionTableItem>["columns"] => [
      {
        title: "ID",
        dataIndex: "id",
        key: "id",
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

  const tableColumnDefinitions = useMemo(
    () => buildColumns() ?? [],
    [buildColumns],
  );

  const sessionsColumnSettingsKey = chainId
    ? "sessionsTableChain"
    : "sessionsTableAdmin";

  const { orderedColumns, columnSettingsButton } =
    useColumnSettingsBasedOnColumnsType<SessionTableItem>(
      sessionsColumnSettingsKey,
      tableColumnDefinitions,
    );

  const sessionsColumnResize = useTableColumnResize({
    id: 220,
    chainName: 160,
    executionStatus: 140,
    started: 168,
    finished: 168,
    loggingLevel: 120,
    duration: 100,
    snapshotName: 160,
    engineAddress: 200,
  });

  const columnsWithResize = useMemo(
    () =>
      attachResizeToColumns(
        orderedColumns,
        sessionsColumnResize.columnWidths,
        sessionsColumnResize.createResizeHandlers,
        { minWidth: 80 },
      ),
    [
      orderedColumns,
      sessionsColumnResize.columnWidths,
      sessionsColumnResize.createResizeHandlers,
    ],
  );

  const scrollX = useMemo(
    () =>
      sumScrollXForColumns(
        columnsWithResize,
        sessionsColumnResize.columnWidths,
        {
          expandColumnWidth: SESSIONS_EXPAND_COLUMN_WIDTH,
          selectionColumnWidth: SESSIONS_SELECTION_COLUMN_WIDTH,
        },
      ),
    [columnsWithResize, sessionsColumnResize.columnWidths],
  );

  const onScroll = useCallback(
    async (event: UIEvent<HTMLDivElement>) => {
      if (isLoading || allSessionsLoaded) return;
      const target = event.target as HTMLDivElement;
      const isScrolledToTheEnd =
        target.scrollTop + target.clientHeight + 1 >= target.scrollHeight;
      if (isScrolledToTheEnd) {
        await fetchSessions(offset);
      }
    },
    [isLoading, allSessionsLoaded, offset, fetchSessions],
  );

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

  useEffect(() => {
    void updateTableData();
  }, [updateTableData]);

  const chainTabToolbar = useMemo(
    () => (
      <Flex
        className={chainPageStyles.chainTabToolbarRow}
        align="center"
        gap={8}
        wrap="wrap"
      >
        <CompactSearch
          value={filters.searchString}
          onChange={(value) =>
            setFilters((prev) => ({ ...prev, searchString: value }))
          }
          placeholder="Search sessions..."
          allowClear
          className={commonStyles.searchField}
          style={{ minWidth: 160, maxWidth: 360, flex: "0 1 auto" }}
        />
        <Flex align="center" gap={8} wrap="wrap" style={{ flexShrink: 0 }}>
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
          {columnSettingsButton}
        </Flex>
      </Flex>
    ),
    [
      filters.searchString,
      columnSettingsButton,
      chainId,
      onRefreshSessions,
      onDeleteBtnClick,
      onExportBtnClick,
      onImportBtnClick,
    ],
  );

  useRegisterChainHeaderActions(chainTabToolbar, [
    filters.searchString,
    chainId,
    selectedRowKeys,
    onRefreshSessions,
  ]);

  return (
    <>
      {contextHolder}
      <TablePageLayout>
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
          scroll={tableData.length > 0 ? { x: scrollX, y: "" } : { x: scrollX }}
          components={sessionsColumnResize.resizableHeaderComponents}
          onScroll={(event) => void onScroll(event)}
          onChange={(_, tableFilters) => setTableFilters(tableFilters)}
        />
      </TablePageLayout>
    </>
  );
};
