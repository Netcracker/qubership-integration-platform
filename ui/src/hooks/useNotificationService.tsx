import { ArgsProps } from "antd/es/notification";
import { ErrorDetails } from "../components/modal/ErrorDetails";
import { useModalsContext } from "../Modals";
import { notification } from "antd";
import { useNotificationLog } from "../components/notifications/contexts/NotificationLogContext.tsx";
import { useEffect, useRef } from "react";

type DescriptionWithDetailsProps = {
  description: string;
  showDetails: (error: any) => void;
}

const NotificationDescriptionWithDetails = (input: DescriptionWithDetailsProps) => {
  return (<div>
    <div>{input.description}</div>
    <div><a onClick={input.showDetails}>Show Details</a></div>
  </div>);
}

interface NotificationService {
  requestFailed(description: string, exception: any): void;
  errorWithDetails(message: string, description: string, exception: any): void;
  info(message: string, description: string): void;
}

export const useNotificationService = (): NotificationService => {
  const { showModal } = useModalsContext();
  const { addToHistory } = useNotificationLog();
  const addToHistoryRef  = useRef(addToHistory)

  useEffect(() => {
        addToHistoryRef.current = addToHistory;
    });

  const showDetailsClick = (error: any) => {
    const data = error?.responseBody;

    showModal({
      component: (
        <ErrorDetails
          service={data?.serviceName}
          timestamp={data?.errorDate}
          message={data?.errorMessage}
          stacktrace={data?.stackTrace}
        />
      ),
    });
  }

  function buildInfoNotification(message: string, description: string): ArgsProps {
    const notificationConfiguration : ArgsProps = {
      type: "info",
      message: message,
      description: description
    };
    addToHistory(notificationConfiguration);
    return notificationConfiguration;
  }

  function buildErrorNotification(message: string, description: string, error: any): ArgsProps {
    const notificationConfiguration : ArgsProps = {
        type: "error",
        message: message,
        description: <NotificationDescriptionWithDetails description={description} showDetails={() => showDetailsClick(error)} />
      };
    addToHistory(notificationConfiguration);
    return notificationConfiguration
  }

  return {
    requestFailed: (description: string, error: any) => {
      notification.error(buildErrorNotification("Request failed", description, error));
    },
    errorWithDetails: (message: string, description: string, error: any) => {
      notification.error(buildErrorNotification(message, description, error));
    },
    info: (message: string, description: string) => {
      notification.info(buildInfoNotification(message, description));
    }
  }
}
