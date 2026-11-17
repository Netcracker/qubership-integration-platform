import { Button } from "antd";
import { ErrorDetails } from "../components/modal/ErrorDetails";
import { useModalsContext } from "../Modals";
import {
  NotificationItem,
  useNotificationLog,
} from "../components/notifications/contexts/NotificationLogContext.tsx";
import { type ReactNode, useCallback, useEffect, useMemo, useRef } from "react";
import { getErrorDetails } from "../misc/error-utils.ts";
import { useNotificationApi } from "../components/notifications/contexts/NotificationApiContext.tsx";

type DescriptionWithDetailsProps = {
  description: string;
  showDetails: () => void;
};

const NotificationDescriptionWithDetails = (
  input: DescriptionWithDetailsProps,
) => {
  return (
    <div>
      <div>{input.description}</div>
      <div>
        <a onClick={input.showDetails}>Show Details</a>
      </div>
    </div>
  );
};

interface NotificationService {
  requestFailed(
    description: string,
    exception: unknown,
    retry?: () => void,
  ): void;

  errorWithDetails(
    message: string,
    description: string,
    exception: unknown,
  ): void;

  error(message: string, description?: ReactNode): void;

  info(message: string, description?: ReactNode): void;
  warning(message: string, description?: ReactNode, retry?: () => void): void;
}

export const useNotificationService = (): NotificationService => {
  const { showModal } = useModalsContext();
  const { addToHistory } = useNotificationLog();
  const notificationApi = useNotificationApi();
  const addToHistoryRef = useRef(addToHistory);

  useEffect(() => {
    addToHistoryRef.current = addToHistory;
  }, [addToHistory]);

  const showDetailsClick = useCallback(
    (error: unknown) => {
      const details = getErrorDetails(error);

      showModal({
        component: (
          <ErrorDetails
            service={details.service}
            timestamp={details.timestamp}
            message={details.message}
            stacktrace={details.stacktrace}
          />
        ),
      });
    },
    [showModal],
  );

  const buildInfoNotification = useCallback(
    (message: string, description?: ReactNode): NotificationItem => ({
      type: "info",
      message: message,
      description: description,
    }),
    [],
  );

  const buildWarningNotification = useCallback(
    (message: string, description?: ReactNode): NotificationItem => ({
      type: "warning",
      message: message,
      description: description,
    }),
    [],
  );

  const buildErrorNotification = useCallback(
    (
      message: string,
      description: string,
      error: unknown,
    ): NotificationItem => ({
      type: "error",
      message: message,
      description: (
        <NotificationDescriptionWithDetails
          description={description}
          showDetails={() => showDetailsClick(error)}
        />
      ),
    }),
    [showDetailsClick],
  );

  return useMemo(
    () => ({
      requestFailed: (
        description: string,
        error: unknown,
        retry?: () => void,
      ) => {
        const item = buildErrorNotification(
          "Request failed",
          description,
          error,
        );
        if (retry) {
          const details = item.description;
          item.description = (
            <>
              {details}
              <div>
                <Button type="link" onClick={retry}>
                  Retry refresh
                </Button>
              </div>
            </>
          );
        }
        addToHistory(item);
        notificationApi.error(item);
      },
      errorWithDetails: (
        message: string,
        description: string,
        error: unknown,
      ) => {
        const item = buildErrorNotification(message, description, error);
        addToHistory(item);
        notificationApi.error(item);
      },
      error: (message: string, description?: ReactNode) => {
        const item: NotificationItem = {
          type: "error",
          message,
          description,
        };
        addToHistory(item);
        notificationApi.error(item);
      },
      info: (message: string, description?: ReactNode) => {
        const item = buildInfoNotification(message, description);
        addToHistory(item);
        notificationApi.info(item);
      },
      warning: (
        message: string,
        description?: ReactNode,
        retry?: () => void,
      ) => {
        const item = buildWarningNotification(message, description);
        if (retry)
          item.description = (
            <>
              {description}
              <div>
                <Button type="link" onClick={retry}>
                  Retry refresh
                </Button>
              </div>
            </>
          );
        addToHistory(item);
        notificationApi.warning(item);
      },
    }),
    [
      addToHistory,
      buildErrorNotification,
      buildInfoNotification,
      buildWarningNotification,
      notificationApi,
    ],
  );
};
