import { ErrorDetails } from "../components/modal/ErrorDetails";
import { useModalsContext } from "../Modals";
import {
  NotificationItem,
  useNotificationLog,
} from "../components/notifications/contexts/NotificationLogContext.tsx";
import { type ReactNode, useCallback, useEffect, useMemo, useRef } from "react";
import { ApiError } from "../api/apiTypes.ts";
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
  requestFailed(description: string, exception: unknown): void;

  errorWithDetails(
    message: string,
    description: string,
    exception: unknown,
  ): void;

  info(message: string, description?: ReactNode): void;
  warning(message: string, description?: ReactNode): void;
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
      const data = (error as ApiError)?.responseBody;

      showModal({
        component: (
          <ErrorDetails
            service={data?.serviceName ?? ""}
            timestamp={data?.errorDate ?? ""}
            message={data?.errorMessage ?? ""}
            stacktrace={data?.stacktrace ?? ""}
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
      requestFailed: (description: string, error: unknown) => {
        const item = buildErrorNotification(
          "Request failed",
          description,
          error,
        );
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
      info: (message: string, description?: ReactNode) => {
        const item = buildInfoNotification(message, description);
        addToHistory(item);
        notificationApi.info(item);
      },
      warning: (message: string, description?: ReactNode) => {
        const item = buildWarningNotification(message, description);
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
