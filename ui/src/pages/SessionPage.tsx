import React, { useCallback, useEffect, useMemo, useState } from "react";
import { useParams } from "react-router";
import { Button, Table, Tag, Tooltip } from "antd";
import { TableProps } from "antd/lib/table";
import { ExecutionStatus, Session, SessionElement } from "../api/apiTypes.ts";
import { formatDuration, PLACEHOLDER } from "../misc/format-utils.ts";
import { SessionStatus } from "../components/sessions/SessionStatus.tsx";
import { api } from "../api/api.ts";
import { SessionElementDuration } from "../components/sessions/SessionElementDuration.tsx";
import { useModalsContext } from "../Modals.tsx";
import { SessionElementDetails } from "../components/modal/SessionElementDetails.tsx";
import { downloadFile } from "../misc/download-utils.ts";
import { useNotificationService } from "../hooks/useNotificationService.tsx";
import { OverridableIcon } from "../icons/IconProvider.tsx";
import {
  attachResizeToColumns,
  sumScrollXForColumns,
  useTableColumnResize,
} from "../components/table/useTableColumnResize.tsx";
import { treeExpandIcon } from "../components/table/TreeExpandIcon.tsx";
import { useRegisterChainHeaderActions } from "./ChainHeaderActionsContext.tsx";
import { TableToolbar } from "../components/table/TableToolbar.tsx";
import { useColumnSettingsBasedOnColumnsType } from "../components/table/useColumnSettingsButton.tsx";
import { TablePageLayout } from "../components/TablePageLayout.tsx";
import {
  matchesByFields,
  normalizeSearchTerm,
} from "../components/table/tableSearch.ts";

/** rc-table expand column when rows have nested `children`. */
const SESSION_ELEMENT_EXPAND_COLUMN_WIDTH = 48;

const sessionElementExpandIcon = treeExpandIcon<SessionElement>();

function cleanUpChildren(element: SessionElement) {
  if (element.children?.length === 0) {
    element.children = undefined;
  } else {
    element.children?.forEach((child) => cleanUpChildren(child));
  }
}

/** Keys of rows that must be expanded to reveal a descendant with an error status. */
function collectExpandedRowKeysForErrorBranches(
  elements: SessionElement[] | undefined,
): string[] {
  const keys = new Set<string>();
  const visit = (el: SessionElement): boolean => {
    const selfError =
      el.executionStatus === ExecutionStatus.COMPLETED_WITH_ERRORS;
    let childHasError = false;
    for (const c of el.children ?? []) {
      if (visit(c)) {
        childHasError = true;
      }
    }
    if (childHasError) {
      keys.add(el.elementId);
    }
    return selfError || childHasError;
  };
  elements?.forEach(visit);
  return [...keys];
}

function collectAllExpandableRowKeys(
  elements: SessionElement[] | undefined,
): string[] {
  const keys: string[] = [];
  const visit = (el: SessionElement) => {
    if (el.children?.length) {
      keys.push(el.elementId);
      el.children.forEach(visit);
    }
  };
  elements?.forEach(visit);
  return keys;
}

function filterSessionElementsTree(
  elements: SessionElement[] | undefined,
  term: string,
  session: Session | undefined,
): SessionElement[] | undefined {
  if (!elements?.length) {
    return elements;
  }
  if (!normalizeSearchTerm(term)) {
    return elements;
  }

  const include = (el: SessionElement): SessionElement | null => {
    const mappedChildren = (el.children ?? []).map(include);
    const filteredChildren = mappedChildren.filter(
      (c): c is SessionElement => c !== null,
    );

    const matchesSelf = matchesByFields(term, [
      el.elementName,
      el.camelName,
      el.executionStatus,
      String(el.duration ?? ""),
      el.chainElementId,
      session?.snapshotName,
    ]);

    if (matchesSelf) {
      return { ...el, children: el.children };
    }
    if (filteredChildren.length > 0) {
      return { ...el, children: filteredChildren };
    }
    return null;
  };

  return elements.map(include).filter((c): c is SessionElement => c !== null);
}

export const SessionPage: React.FC = () => {
  const { chainId, sessionId } = useParams<{
    chainId: string;
    sessionId: string;
  }>();
  const [isLoading, setIsLoading] = useState(false);
  const [session, setSession] = useState<Session>();
  const [expandedRowKeys, setExpandedRowKeys] = useState<string[]>([]);
  const [searchTerm, setSearchTerm] = useState("");
  const { showModal } = useModalsContext();
  const notificationService = useNotificationService();

  const getSession = useCallback(async () => {
    if (!sessionId) {
      return;
    }
    setIsLoading(true);
    try {
      const loadedSession = await api.getSession(sessionId);
      loadedSession.sessionElements?.forEach((element) =>
        cleanUpChildren(element),
      );
      setSession(loadedSession);
    } catch (error) {
      notificationService.requestFailed("Failed to get session", error);
    } finally {
      setIsLoading(false);
    }
  }, [notificationService, sessionId]);

  useEffect(() => {
    void getSession();
  }, [getSession, sessionId]);

  useEffect(() => {
    setExpandedRowKeys(
      collectExpandedRowKeysForErrorBranches(session?.sessionElements),
    );
  }, [session]);

  const showElementDetails = useCallback(
    (element: SessionElement) => {
      if (!session) {
        return;
      }
      showModal({
        component: (
          <SessionElementDetails
            session={session}
            elementId={element.elementId}
          />
        ),
      });
    },
    [session, showModal],
  );

  const onExportBtnClick = useCallback(async () => {
    if (!sessionId) {
      return;
    }
    try {
      const file = await api.exportSessions([sessionId]);
      downloadFile(file);
    } catch (error) {
      notificationService.requestFailed("Failed to export session", error);
    }
  }, [notificationService, sessionId]);

  const tableColumnDefinitions: TableProps<SessionElement>["columns"] = useMemo(
    () => [
      {
        title: "Element Name",
        dataIndex: "elementName",
        key: "elementName",
        render: (_, element) => (
          <>
            <a
              style={{ marginRight: 8 }}
              onClick={() => showElementDetails(element)}
            >
              {element.elementName}
            </a>
            {session?.chainId && element.chainElementId ? (
              <OverridableIcon
                name="link"
                onClick={() =>
                  window.open(
                    `/chains/${element.actualElementChainId ?? session?.chainId}/graph/${element.chainElementId}`,
                    "_blank",
                  )
                }
              />
            ) : null}
          </>
        ),
      },
      {
        title: "Snapshot",
        key: "snapshot",
        hidden: true,
        render: () => session?.snapshotName ?? PLACEHOLDER,
      },
      {
        title: "Status",
        dataIndex: "executionStatus",
        key: "executionStatus",
        render: (_, item) => <SessionStatus status={item.executionStatus} />,
      },
      {
        title: "Duration",
        dataIndex: "duration",
        key: "duration",
        render: (_, element) => (
          <SessionElementDuration
            duration={element.duration}
            sessionDuration={session?.duration ?? 1}
            status={element.executionStatus}
          />
        ),
      },
      {
        title: "Element Type",
        dataIndex: "camelName",
        key: "camelName",
        render: (_, element) => <>{element.camelName}</>,
      },
    ],
    [session, showElementDetails],
  );

  const sessionElementsColumnSettingsKey = chainId
    ? `sessionElementsTable-${chainId}`
    : "sessionElementsTable";

  const { orderedColumns, columnSettingsButton } =
    useColumnSettingsBasedOnColumnsType<SessionElement>(
      sessionElementsColumnSettingsKey,
      tableColumnDefinitions,
    );

  const sessionElementsColumnResize = useTableColumnResize({
    elementName: 280,
    snapshot: 80,
    executionStatus: 140,
    duration: 120,
    camelName: 160,
  });

  const expandAllRows = useCallback(() => {
    setExpandedRowKeys(collectAllExpandableRowKeys(session?.sessionElements));
  }, [session?.sessionElements]);

  const collapseAllRows = useCallback(() => {
    setExpandedRowKeys([]);
  }, []);

  const columnsWithResize = useMemo(
    () =>
      attachResizeToColumns(
        orderedColumns,
        sessionElementsColumnResize.columnWidths,
        sessionElementsColumnResize.createResizeHandlers,
        { minWidth: 80 },
      ),
    [
      orderedColumns,
      sessionElementsColumnResize.columnWidths,
      sessionElementsColumnResize.createResizeHandlers,
    ],
  );

  const scrollX = useMemo(
    () =>
      sumScrollXForColumns(
        columnsWithResize,
        sessionElementsColumnResize.columnWidths,
        { expandColumnWidth: SESSION_ELEMENT_EXPAND_COLUMN_WIDTH },
      ),
    [columnsWithResize, sessionElementsColumnResize.columnWidths],
  );

  const filteredSessionElements = useMemo(
    () =>
      filterSessionElementsTree(session?.sessionElements, searchTerm, session),
    [session, searchTerm],
  );

  useEffect(() => {
    if (!normalizeSearchTerm(searchTerm)) {
      return;
    }
    const validIds = new Set<string>();
    const walk = (els: SessionElement[] | undefined) => {
      if (!els) return;
      for (const e of els) {
        validIds.add(e.elementId);
        walk(e.children);
      }
    };
    walk(filteredSessionElements);
    setExpandedRowKeys((keys) => keys.filter((k) => validIds.has(k)));
  }, [filteredSessionElements, searchTerm]);

  const sessionToolbarActions = useMemo(
    () => (
      <>
        <Tooltip title="Expand all session elements" placement="bottom">
          <Button
            icon={<OverridableIcon name="expandAll" />}
            onClick={expandAllRows}
          />
        </Tooltip>
        <Tooltip title="Collapse all session elements" placement="bottom">
          <Button
            icon={<OverridableIcon name="collapseAll" />}
            onClick={collapseAllRows}
          />
        </Tooltip>
        <Tooltip title="Export session" placement="bottom">
          <Button
            icon={<OverridableIcon name="cloudDownload" />}
            onClick={() => void onExportBtnClick()}
          />
        </Tooltip>
      </>
    ),
    [collapseAllRows, expandAllRows, onExportBtnClick],
  );

  const sessionToolbarLeading = useMemo(
    () => (
      <>
        <Tag>{session?.snapshotName ?? PLACEHOLDER}</Tag>
        {session?.executionStatus ? (
          <SessionStatus
            status={session?.executionStatus}
            suffix={`in ${formatDuration(session.duration)}`}
          />
        ) : (
          <span>{PLACEHOLDER}</span>
        )}
      </>
    ),
    [session],
  );

  const chainTabToolbar = useMemo(
    () => (
      <TableToolbar
        variant="chain-tab"
        leading={sessionToolbarLeading}
        search={{
          value: searchTerm,
          onChange: setSearchTerm,
          placeholder: "Search session elements...",
          allowClear: true,
          style: { minWidth: 160, maxWidth: 360, flex: "0 1 auto" },
        }}
        columnSettingsButton={columnSettingsButton}
        actions={sessionToolbarActions}
      />
    ),
    [
      searchTerm,
      columnSettingsButton,
      sessionToolbarLeading,
      sessionToolbarActions,
    ],
  );

  useRegisterChainHeaderActions(chainTabToolbar, [
    searchTerm,
    session,
    expandAllRows,
    collapseAllRows,
  ]);

  return (
    <TablePageLayout>
      <Table<SessionElement>
        size="small"
        rowKey="elementId"
        columns={columnsWithResize}
        dataSource={filteredSessionElements}
        pagination={false}
        loading={isLoading}
        className="flex-table"
        style={{ flex: 1, minHeight: 0 }}
        scroll={{ x: scrollX, y: "" }}
        components={sessionElementsColumnResize.resizableHeaderComponents}
        expandable={{
          expandIcon: ({ record, ...rest }) => {
            const hideArrow = !record.children?.length;
            const props = { ...rest, record };
            return sessionElementExpandIcon(
              hideArrow ? { ...props, expandable: false } : props,
            );
          },
          expandedRowKeys,
          onExpandedRowsChange: (keys) => setExpandedRowKeys(keys.map(String)),
        }}
      />
    </TablePageLayout>
  );
};
