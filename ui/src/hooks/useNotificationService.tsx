import { ErrorDetails } from "../components/modal/ErrorDetails";
import { useModalsContext } from "../Modals";
import { notification } from "antd";
import { NotificationItem, useNotificationLog } from "../components/notifications/contexts/NotificationLogContext.tsx";
import { useEffect, useRef } from "react";
import { ApiError } from "../api/admin-tools/variables/types.ts";

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

  errorWithDetails(message: string, description: string, exception: unknown): void;

  info(message: string, description: string): void;
}

export const useNotificationService = (): NotificationService => {
  const { showModal } = useModalsContext();
  const { addToHistory } = useNotificationLog();
  const addToHistoryRef = useRef(addToHistory);

  useEffect(() => {
    addToHistoryRef.current = addToHistory;
  });

  const showDetailsClick = (error: unknown) => {
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
  };

  function buildInfoNotification(
    message: string,
    description: string,
  ): NotificationItem {
    const notification: NotificationItem = {
      type: "info",
      message: message,
      description: description,
    };
    addToHistory(notification);
    return notification;
  }

  function buildErrorNotification(
    message: string,
    description: string,
    error: unknown,
  ): NotificationItem {
    const notification: NotificationItem = {
      type: "error",
      message: message,
      description: (
        <NotificationDescriptionWithDetails
          description={description}
          showDetails={() => showDetailsClick(error)}
        />
      ),
    };
    addToHistory(notification);
    return notification;
  }

  return {
    requestFailed: (description: string, error: unknown) => {
      notification.error(
        buildErrorNotification("Request failed", description, error),
      );
    },
    errorWithDetails: (message: string, description: string, error: unknown) => {
      notification.error(buildErrorNotification(message, description, error));
    },
    info: (message: string, description: string) => {
      notification.info(buildInfoNotification(message, description));
    },
  };
};
