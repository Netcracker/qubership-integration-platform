import React, { useCallback, useEffect, useState } from "react";
import { useParams } from "react-router";
import { Flex, FloatButton, Table } from "antd";
import { TableProps } from "antd/lib/table";
import { Session, SessionElement } from "../api/apiTypes.ts";
import {
  formatDuration,
  formatUTCSessionDate,
  PLACEHOLDER,
} from "../misc/format-utils.ts";
import { SessionStatus } from "../components/sessions/SessionStatus.tsx";
import { api } from "../api/api.ts";
import { SessionElementDuration } from "../components/sessions/SessionElementDuration.tsx";
import { useModalsContext } from "../Modals.tsx";
import { SessionElementDetails } from "../components/modal/SessionElementDetails.tsx";
import { downloadFile } from "../misc/download-utils.ts";
import { useNotificationService } from "../hooks/useNotificationService.tsx";
import { OverridableIcon } from "../icons/IconProvider.tsx";

function cleanUpChildren(element: SessionElement) {
  if (element.children?.length === 0) {
    element.children = undefined;
  } else {
    element.children?.forEach((child) => cleanUpChildren(child));
  }
}

export const SessionPage: React.FC = () => {
  const { sessionId } = useParams<{ sessionId: string }>();
  const [isLoading, setIsLoading] = useState(false);
  const [session, setSession] = useState<Session>();
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

  const showElementDetails = (element: SessionElement) => {
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
  };

  const onExportBtnClick = async () => {
    if (!sessionId) {
      return;
    }
    try {
      const file = await api.exportSessions([sessionId]);
      downloadFile(file);
    } catch (error) {
      notificationService.requestFailed("Failed to export session", error);
    }
  };

  const columns: TableProps<SessionElement>["columns"] = [
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
            <OverridableIcon name="link"
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
      title: "Start time",
      dataIndex: "started",
      key: "started",
      render: (_, element) => (
        <>{formatUTCSessionDate(element.started, true)}</>
      ),
    },
    {
      title: "Finish time",
      dataIndex: "finished",
      key: "finished",
      render: (_, element) => (
        <>{formatUTCSessionDate(element.finished, true)}</>
      ),
    },
    {
      title: "Element Type",
      dataIndex: "camelName",
      key: "camelName",
      render: (_, element) => <>{element.camelName}</>,
    },
  ];
  return (
    <Flex vertical gap={16} style={{ height: "100%" }}>
      <Flex
        vertical={false}
        justify="space-between"
        style={{ paddingLeft: 8, paddingRight: 8 }}
      >
        <span>
          {session ? formatUTCSessionDate(session.started) : PLACEHOLDER}
        </span>
        {session?.executionStatus ? (
          <SessionStatus
            status={session?.executionStatus}
            suffix={`in ${formatDuration(session.duration)}`}
          />
        ) : (
          <span>{PLACEHOLDER}</span>
        )}
      </Flex>
      <Table<SessionElement>
        size="small"
        rowKey="elementId"
        columns={columns}
        dataSource={session?.sessionElements}
        pagination={false}
        loading={isLoading}
        className="flex-table"
        scroll={{ y: "" }}
      />
      <FloatButton
        tooltip={{ title: "Export session", placement: "left" }}
        icon={<OverridableIcon name="cloudDownload" />}
        onClick={() => void onExportBtnClick()}
      />
    </Flex>
  );
};
