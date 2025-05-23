import React, { UIEvent, useEffect, useState } from "react";
import {
  Button,
  Flex,
  FloatButton,
  message,
  Modal,
  Table,
} from "antd";
import { useNavigate, useParams } from "react-router";
import {
  DeleteOutlined,
  ExportOutlined,
  ImportOutlined,
  MoreOutlined,
  RedoOutlined,
} from "@ant-design/icons";
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
import FloatButtonGroup from "antd/lib/float-button/FloatButtonGroup";
import {
  FilterDropdownProps,
  TableRowSelection,
} from "antd/lib/table/interface";
import { api } from "../api/api.ts";
import { SessionStatus } from "../components/sessions/SessionStatus.tsx";
import { useModalsContext } from "../Modals.tsx";
import { downloadFile } from "../misc/download-utils.ts";
import { ImportSessions } from "../components/modal/ImportSessions.tsx";
import Search from "antd/lib/input/Search";
import {
  TimestampColumnFilterDropdown,
  TimestampFilter,
  TimestampFilterCondition,
} from "../components/table/TimestampColumnFilterDropdown.tsx";
import type { FilterValue } from "antd/es/table/interface";
import {
  TextColumnFilterDropdown,
  TextFilter,
  TextFilterCondition,
} from "../components/table/TextColumnFilterDropdown.tsx";
import { useNotificationService } from "../hooks/useNotificationService.tsx";

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
  const timestampFilter: TimestampFilter = JSON.parse(filterStr);
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
  const [columns, setColumns] = useState<
    TableProps<SessionTableItem>["columns"]
  >([]);
  const [tableData, setTableData] = useState<SessionTableItem[]>([]);
  const [checkpointMap, setCheckpointMap] = useState<Map<string, Checkpoint[]>>(
    new Map(),
  );
  const [messageApi, contextHolder] = message.useMessage();
  const notificationService = useNotificationService();

  useEffect(() => {
    fetchSessions(true);
  }, [chainId, filters]);

  useEffect(() => {
    setColumns(buildColumns());
  }, [chainId]);

  useEffect(() => {
    updateTableData();
  }, [sessions, checkpointMap]);

  const updateTableData = async () => {
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
        // @ts-ignore
        parentItem.children = parentItem?.children
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
  };

  const setTableFilters = (
    tableFilters: Record<string, FilterValue | null>,
  ) => {
    const filterRequestList: SessionFilterRequest[] = [];
    if (tableFilters.chainName) {
      const textFilter: TextFilter = JSON.parse(
        tableFilters.chainName[0].toString(),
      );
      filterRequestList.push({
        feature: SessionFilterFeature.CHAIN_NAME,
        condition: convertTextFilterCondition(textFilter.condition),
        value: textFilter.value,
      });
    }
    if (tableFilters.engineAddress) {
      const textFilter: TextFilter = JSON.parse(
        tableFilters.engineAddress[0].toString(),
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
    if (tableFilters.status) {
      filterRequestList.push({
        feature: SessionFilterFeature.STATUS,
        condition: SessionFilterCondition.IN,
        value: tableFilters.status.join(","),
      });
    }
    setFilters({ ...filters, filterRequestList });
  };

  const fetchSessions = async (reset?: boolean) => {
    const options: PaginationOptions = { offset: reset ? 0 : offset };
    setIsLoading(true);
    try {
      const response = await api.getSessions(chainId, filters, options);
      setSessions([...(reset ? [] : sessions), ...response.sessions]);
      setOffset(response.offset);
      setAllSessionsLoaded(response.sessions.length === 0);
      const ids = response.sessions.map((session) => session.id);
      const checkpointSessions = await api.getCheckpointSessions(ids);
      const m = new Map(reset ? [] : checkpointMap.entries());
      checkpointSessions.forEach((session) =>
        m.set(session.id, session.checkpoints),
      );
      setCheckpointMap(m);
    } catch (error) {
      notificationService.requestFailed("Failed to fetch sessions", error);
    } finally {
      setIsLoading(false);
    }
  };

  const retryFromLastCheckpoint = async (
    chainId: string,
    sessionId: string,
  ) => {
    try {
      await api.retrySessionFromLastCheckpoint(chainId, sessionId);
      messageApi.info(
        "Session was retried successfully. Please update table to see session result.",
      );
    } catch (error) {
      notificationService.requestFailed("Failed to retry session", error);
    }
  };

  const buildColumns = (): TableProps<SessionTableItem>["columns"] => [
    {
      title: "ID",
      dataIndex: "id",
      key: "id",
      render: (_, item) =>
        isCorrelationItem(item) ? (
          <span style={{ fontWeight: 600 }}>{item.id}</span>
        ) : (
          <Flex gap={8}>
            <a
              onClick={async () =>
                navigate(`/chains/${item.chainId}/sessions/${item.id}`)
              }
            >
              {item.id}
            </a>
            {item.checkpoints && item.checkpoints?.length > 0 ? (
              <Button
                size="small"
                type="text"
                icon={<RedoOutlined />}
                onClick={async () =>
                  retryFromLastCheckpoint(item.chainId, item.id)
                }
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
            render: (_: any, item: SessionTableItem) => (
              <a onClick={async () => navigate(`/chains/${item.chainId}`)}>
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
  ];

  const onScroll = async (event: UIEvent<HTMLDivElement>) => {
    const target = event.target as HTMLDivElement;
    const isScrolledToTheEnd =
      target.scrollTop + target.clientHeight + 1 >= target.scrollHeight;
    if (!allSessionsLoaded && isScrolledToTheEnd) {
      await fetchSessions();
    }
  };

  const onSelectChange = (newSelectedRowKeys: React.Key[]) => {
    setSelectedRowKeys(newSelectedRowKeys);
  };

  const onDeleteBtnClick = async () => {
    if (selectedRowKeys.length === 0) return;
    Modal.confirm({
      title: "Delete Sessions",
      content: `Are you sure you want to delete ${selectedRowKeys.length} session(s)?`,
      onOk: async () => deleteSelectedSessions(),
    });
  };

  const deleteSelectedSessions = async () => {
    try {
      const ids = selectedRowKeys.map((key) => key.toString());
      if (isAllSessionsSelected()) {
        await api.deleteSessionsByChainId(chainId);
        setSessions([]);
      } else {
        await api.deleteSessions(ids);
        setSessions(
          sessions?.filter((session) => !ids.some((id) => session.id === id)) ??
            [],
        );
      }
    } catch (error) {
      notificationService.requestFailed("Failed to delete sessions", error);
    }
  };

  const isAllSessionsSelected = (): boolean => {
    return selectedRowKeys.length === sessions.length;
  };

  const onExportBtnClick = async () => {
    if (selectedRowKeys.length === 0) return;
    try {
      const ids = selectedRowKeys.map((key) => key.toString());
      const file = await api.exportSessions(ids);
      downloadFile(file);
    } catch (error) {
      notificationService.requestFailed("Failed to export sessions", error);
    }
  };

  const onImportBtnClick = async () => {
    showModal({
      component: (
        <ImportSessions
          onSuccess={(importedSessions) =>
            setSessions([...importedSessions, ...sessions])
          }
        />
      ),
    });
  };

  const onRetryBtnClick = async () => {
    const promises = selectedRowKeys
      .map((key) => key.toString())
      .map((id) => sessions.find((session) => session.id === id))
      .filter((session) => !!session)
      .map(async (session) => {
        try {
          await api.retryFromLastCheckpoint(session.chainId, session.id);
          notificationService.info("Session retried", `Session ${session.id} was retried successfully`);
        } catch (error) {
          notificationService.requestFailed(`Failed to retry session ${session.id}`, error);
        }
      });
    await Promise.all(promises);
  };

  const rowSelection: TableRowSelection<SessionTableItem> = {
    type: "checkbox",
    selectedRowKeys,
    checkStrictly: false,
    onChange: onSelectChange,
  };

  return (
    <>
      {contextHolder}
      <Flex vertical gap={16} style={{ height: "100%" }}>
        <Search
          placeholder="Full text search"
          allowClear
          onSearch={(value) => setFilters({ ...filters, searchString: value })}
        />
        <Table<SessionTableItem>
          size="small"
          className="flex-table"
          columns={columns}
          rowSelection={rowSelection}
          dataSource={tableData}
          pagination={false}
          loading={isLoading}
          rowKey="id"
          sticky
          scroll={{ y: "" }}
          onScroll={onScroll}
          onChange={(_, tableFilters) => setTableFilters(tableFilters)}
        />
        <FloatButtonGroup trigger="hover" icon={<MoreOutlined />}>
          <FloatButton
            tooltip="Retry selected sessions"
            icon={<RedoOutlined />}
            onClick={onRetryBtnClick}
          />
          {chainId ? null : (
            <FloatButton
              tooltip="Import sessions"
              icon={<ImportOutlined />}
              onClick={onImportBtnClick}
            />
          )}
          <FloatButton
            tooltip="Export selected sessions"
            icon={<ExportOutlined />}
            onClick={onExportBtnClick}
          />
          <FloatButton
            tooltip="Delete selected sessions"
            icon={<DeleteOutlined />}
            onClick={onDeleteBtnClick}
          />
        </FloatButtonGroup>
      </Flex>
    </>
  );
};
