import React, { useCallback, useEffect, useMemo, useState } from "react";
import {
  Button,
  Empty,
  Flex,
  InputNumber,
  Modal,
  Space,
  Table,
  TableProps,
  Tooltip,
} from "antd";
import { OverridableIcon } from "../../../icons/IconProvider.tsx";
import { treeExpandIcon } from "../../table/TreeExpandIcon.tsx";
import { LiveExchange, SessionsLoggingLevel } from "../../../api/apiTypes.ts";
import commonStyles from "../CommonStyle.module.css";
import { useNotificationService } from "../../../hooks/useNotificationService.tsx";
import { api } from "../../../api/api.ts";
import {
  formatDuration,
  formatTimestamp,
  PLACEHOLDER,
} from "../../../misc/format-utils.ts";
import { useNavigate } from "react-router";
import { useLiveExchangeFilters } from "./useLiveExchangeFilters.tsx";
import { ProtectedButton } from "../../../permissions/ProtectedButton.tsx";
import { useColumnSettingsBasedOnColumnsType } from "../../table/useColumnSettingsButton.tsx";
import { TablePageLayout } from "../../TablePageLayout.tsx";
import { TableToolbar } from "../../table/TableToolbar.tsx";
import {
  attachResizeToColumns,
  sumScrollXForColumns,
  useTableColumnResize,
} from "../../table/useTableColumnResize.tsx";
import {
  createActionsColumnBase,
  disableResizeBeforeActions,
} from "../../table/actionsColumn.ts";

import { AdminToolsHeader } from "../AdminToolsHeader.tsx";

/** rc-table expand column when rows have nested `exchanges` (same as Sessions). */
const LIVE_EXCHANGES_EXPAND_COLUMN_WIDTH = 48;

type LiveExchangeGroup = Omit<
  LiveExchange,
  "exchangeId" | "duration" | "main"
> & {
  exchanges: LiveExchange[];
};

function isLiveExchangeGroup(obj: unknown): obj is LiveExchangeGroup {
  return typeof obj === "object" && obj !== null && "exchanges" in obj;
}

type LiveExchangeTableItem = LiveExchangeGroup | LiveExchange;

function buildTableItems(exchanges: LiveExchange[]): LiveExchangeTableItem[] {
  const exchangeMap = new Map<string | undefined, LiveExchange[]>();
  exchanges.forEach((exchange) => {
    if (exchangeMap.has(exchange.sessionId)) {
      // @ts-expect-error value is initialized
      exchangeMap.get(exchange.sessionId).push(exchange);
    } else {
      exchangeMap.set(exchange.sessionId, [exchange]);
    }
  });
  return Array.from(exchangeMap.values()).map((exchanges) =>
    exchanges.length > 1
      ? {
          ...exchanges[0],
          exchanges,
        }
      : exchanges[0],
  );
}

function liveExchangeRowKey(record: LiveExchangeTableItem): React.Key {
  if (isLiveExchangeGroup(record)) {
    return `group-${record.sessionId ?? record.exchangeId}`;
  }
  return record.exchangeId;
}

export const LiveExchanges: React.FC = () => {
  const notificationService = useNotificationService();
  const navigate = useNavigate();
  const { filters, filterButton } = useLiveExchangeFilters();

  const [exchanges, setExchanges] = useState<LiveExchange[]>([]);
  const [items, setItems] = useState<LiveExchangeTableItem[]>([]);
  const [isLoading, setIsLoading] = useState<boolean>(false);
  const [limit, setLimit] = useState<number>(10);

  const terminateExchange = useCallback(
    async (liveExchange: LiveExchange) => {
      try {
        await api.terminateExchange(
          liveExchange.podIp,
          liveExchange.deploymentId,
          liveExchange.exchangeId,
        );
        setExchanges((exchanges) =>
          exchanges.filter((e) => e.exchangeId !== liveExchange.exchangeId),
        );
      } catch (error) {
        notificationService.requestFailed(
          "Failed to terminate exchange",
          error,
        );
      }
    },
    [notificationService],
  );

  const showTerminateExchangeModal = useCallback(
    (liveExchange: LiveExchange) => {
      Modal.confirm({
        title: "Terminate Exchange",
        content:
          "Are you sure you want to terminate current exchange? That will cause current session to end with error.",
        onOk: () => void terminateExchange(liveExchange),
      });
    },
    [terminateExchange],
  );

  const columns = useMemo((): TableProps<LiveExchangeTableItem>["columns"] => {
    return [
      {
        title: "Session ID",
        key: "sessionId",
        dataIndex: "sessionId",
        sorter: (a: LiveExchangeTableItem, b: LiveExchangeTableItem) =>
          (b.sessionId ?? "").localeCompare(a.sessionId ?? ""),
        render: (_, item) =>
          item.sessionLogLevel === SessionsLoggingLevel.DEBUG ||
          item.sessionLogLevel === SessionsLoggingLevel.INFO ? (
            <a
              onClick={() =>
                void navigate(
                  `/chains/${item.chainId}/sessions/${item.sessionId}`,
                )
              }
            >
              {item.sessionId}
            </a>
          ) : (
            item.sessionId
          ),
      },
      {
        title: "Chain",
        key: "chainName",
        dataIndex: "chainName",
        sorter: (a: LiveExchangeTableItem, b: LiveExchangeTableItem) =>
          (b.chainName ?? b.chainId ?? "").localeCompare(
            a.chainName ?? a.chainId ?? "",
          ),
        render: (_, liveExchange) => (
          <a onClick={() => void navigate(`/chains/${liveExchange.chainId}`)}>
            {liveExchange?.chainName ?? liveExchange.chainId}
          </a>
        ),
      },
      {
        title: "Session Duration",
        key: "sessionDuration",
        dataIndex: "sessionDuration",
        sorter: (a: LiveExchangeTableItem, b: LiveExchangeTableItem) =>
          (b.sessionDuration ?? 0) - (a.sessionDuration ?? 0),
        render: (_, liveExchange) => (
          <>{formatDuration(liveExchange.sessionDuration)}</>
        ),
      },
      {
        title: "Exchange Duration",
        key: "duration",
        dataIndex: "duration",
        sorter: (a: LiveExchangeTableItem, b: LiveExchangeTableItem) =>
          (isLiveExchangeGroup(b) ? 0 : (b.duration ?? 0)) -
          (isLiveExchangeGroup(a) ? 0 : (a.duration ?? 0)),
        render: (_, item) => (
          <>
            {isLiveExchangeGroup(item)
              ? PLACEHOLDER
              : formatDuration(item.duration)}
          </>
        ),
      },
      {
        title: "Session Started",
        key: "sessionStartTime",
        dataIndex: "sessionStartTime",
        sorter: (a: LiveExchangeTableItem, b: LiveExchangeTableItem) =>
          (b.sessionStartTime ?? 0) - (a.sessionStartTime ?? 0),
        render: (_, item) => (
          <>
            {item.sessionStartTime
              ? formatTimestamp(item.sessionStartTime, true)
              : PLACEHOLDER}
          </>
        ),
      },
      {
        title: "Main thread",
        key: "main",
        dataIndex: "main",
        sorter: (a: LiveExchangeTableItem, b: LiveExchangeTableItem) =>
          (!isLiveExchangeGroup(b) && b.main ? 1 : 0) -
          (!isLiveExchangeGroup(a) && a.main ? 1 : 0),
        render: (_, item) =>
          !isLiveExchangeGroup(item) && item.main ? (
            <OverridableIcon name="check" />
          ) : (
            ""
          ),
      },
      {
        title: "Pod IP",
        key: "podIp",
        dataIndex: "podIp",
        sorter: (a: LiveExchangeTableItem, b: LiveExchangeTableItem) =>
          (b.podIp ?? "").localeCompare(a.podIp ?? ""),
      },
      {
        ...createActionsColumnBase<LiveExchangeTableItem>(),
        render: (_, item) =>
          isLiveExchangeGroup(item) ? (
            <></>
          ) : (
            <ProtectedButton
              require={{ liveExchange: ["execute"] }}
              tooltipProps={{ title: "Terminate exchange" }}
              buttonProps={{
                type: "text",
                iconName: "stop",
                onClick: () => void showTerminateExchangeModal(item),
              }}
            />
          ),
      },
    ];
  }, [navigate, showTerminateExchangeModal]);

  const { orderedColumns, columnSettingsButton } =
    useColumnSettingsBasedOnColumnsType<LiveExchangeTableItem>(
      "liveExchangesTable",
      columns,
    );

  const liveExchangesColumnResize = useTableColumnResize({
    sessionId: 200,
    chainName: 200,
    sessionDuration: 200,
    duration: 200,
    sessionStartTime: 200,
    main: 100,
    podIp: 100,
  });

  const columnsWithResize = useMemo(
    () =>
      disableResizeBeforeActions(
        attachResizeToColumns(
          orderedColumns,
          liveExchangesColumnResize.columnWidths,
          liveExchangesColumnResize.createResizeHandlers,
          { minWidth: 80 },
        ),
      ),
    [
      orderedColumns,
      liveExchangesColumnResize.columnWidths,
      liveExchangesColumnResize.createResizeHandlers,
    ],
  );

  const scrollX = useMemo(
    () =>
      sumScrollXForColumns(
        columnsWithResize,
        liveExchangesColumnResize.columnWidths,
        { expandColumnWidth: LIVE_EXCHANGES_EXPAND_COLUMN_WIDTH },
      ),
    [columnsWithResize, liveExchangesColumnResize.columnWidths],
  );

  const refresh = useCallback(async () => {
    try {
      setIsLoading(true);
      const result = await api.getAndFilterExchanges(limit, filters);
      setExchanges(result);
    } catch (error) {
      notificationService.requestFailed("Failed to get live exchanges", error);
    } finally {
      setIsLoading(false);
    }
  }, [notificationService, limit, filters]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  useEffect(() => {
    setItems(buildTableItems(exchanges));
  }, [exchanges]);

  return (
    <Flex vertical className={commonStyles["container"]}>
      <AdminToolsHeader
        title="Live Exchanges"
        iconName="liveExchanges"
        toolbar={
          <TableToolbar
            variant="admin"
            leading={
              <Flex align="center" gap={8} wrap="wrap">
                <Space direction="horizontal">
                  <span>Exchanges per engine:</span>
                  <InputNumber
                    min={1}
                    defaultValue={10}
                    onChange={(value) => {
                      if (value !== null) setLimit(value);
                    }}
                  />
                </Space>
                {filterButton}
              </Flex>
            }
            columnSettingsButton={columnSettingsButton}
            actions={
              <Tooltip title="Refresh" placement="bottom">
                <Button
                  icon={<OverridableIcon name="refresh" />}
                  onClick={() => void refresh()}
                />
              </Tooltip>
            }
          />
        }
      />
      <Flex vertical flex={1} style={{ minHeight: 0 }}>
        <TablePageLayout>
          <Table<LiveExchangeTableItem>
            className="flex-table"
            size="small"
            columns={columnsWithResize}
            dataSource={items}
            scroll={items.length > 0 ? { x: scrollX, y: "" } : { x: scrollX }}
            pagination={false}
            rowKey={liveExchangeRowKey}
            loading={isLoading}
            sticky
            style={{ flex: 1, minHeight: 0 }}
            expandable={{
              expandIcon: treeExpandIcon(),
              childrenColumnName: "exchanges",
            }}
            components={liveExchangesColumnResize.resizableHeaderComponents}
            locale={{
              emptyText: (
                <Empty
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description="No running chains available at the moment"
                />
              ),
            }}
          />
        </TablePageLayout>
      </Flex>
    </Flex>
  );
};
