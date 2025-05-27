import { ErrorDetails } from "../components/modal/ErrorDetails";
import { useModalsContext } from "../Modals";
import { notification } from "antd";

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

  const showDetailsClick = (error: any) => {
    const data = error?.response?.data;

    showModal({
      component: (
        <ErrorDetails
          service={data?.serviceName}
          timestamp={data?.errorDate}
          message={data?.errorMessage}
          stacktrace={data?.stacktrace}
        />
      ),
    });
  }

  return {
    requestFailed: (description: string, error: any) => {
      notification.error({
        message: "Request failed",
        description: <NotificationDescriptionWithDetails description={description} showDetails={() => showDetailsClick(error)} />
      });
    },
    errorWithDetails: (message: string, description: string, error: any) => {
      notification.error({
        message: message,
        description: <NotificationDescriptionWithDetails description={description} showDetails={() => showDetailsClick(error)} />
      });
    },
    info: (message: string, description: string) => {
      notification.info({
        message: message,
        description: description
      });
    }
  }
}
