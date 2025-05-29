import { api } from "../api/api.ts";
import { Deployment, Event, ObjectType } from "../api/apiTypes.ts";
import { useEffect, useState } from "react";
import { useNotificationService } from "./useNotificationService.tsx";
import { useEventContext } from "../components/notifications/contexts/EventContext.tsx";
import { getDeploymentFromEvent, mergeDeployment } from "../misc/deployment-utils.ts";

export enum DeploymentStatus  {
    DEPLOYED = "DEPLOYED",
    PROCESSING = "PROCESSING",
    FAILED = "FAILED",
    REMOVED = "REMOVED"
}

export type StatusNotificationData = {
    type: "info" | "error",
    message: string;
}

export const StatusNotificationMap: Record<DeploymentStatus, StatusNotificationData> = {
    DEPLOYED: {
        type: "info",
        message: "Has been deployed successfully",
    },
    PROCESSING: {
        type: "info",
        message: "Is progressing",
    },
    FAILED: {
        type: "error",
        message: "Has been failed",
    },
    REMOVED: {
        type: "info",
        message: "Has been removed successfully",
    }
}

export const useDeployments = (chainId?: string) => {
  const [isLoading, setIsLoading] = useState(false);
  const [deployments, setDeployments] = useState<Deployment[]>();
  const notificationService = useNotificationService();
  const { subscribe } = useEventContext();

  useEffect(() => {
    updateDeployments();
  }, [chainId]);


  useEffect(() => {
      return subscribe(ObjectType.DEPLOYMENT, (event: Event) => {
          if (event.data.chainId !== chainId){
              return;
          }
          const eventDeployment = getDeploymentFromEvent(event);
          const eventDeploymentStatus = event.data.state.status;
          showEventNotification(event);
          setDeployments((deploymentsState) => {
              const currentDeployments = deploymentsState ? deploymentsState : [];
              if (currentDeployments.length === 0) {
                  updateDeployments().then((deployments) => {
                      if (deployments) setDeployments(deployments);
                  });
                  return currentDeployments;
              }
              if (eventDeploymentStatus === DeploymentStatus.REMOVED) {
                  return currentDeployments.filter((currentDeployment) => currentDeployment.id !== eventDeployment.id);
              }
              const matchedDeployment = currentDeployments.find((d) => d.id === eventDeployment.id);
              if (matchedDeployment) {
                  return currentDeployments.map((currentDeployment) => currentDeployment.id === eventDeployment.id ? mergeDeployment(currentDeployment, eventDeployment) : currentDeployment);
              }
              return currentDeployments;
            });
        });
      }, [subscribe, chainId]);

  function showEventNotification(event: Event) {
      const chainName = event.data.chainName;
      const status: DeploymentStatus = event.data.state.status;
      const notificationData = StatusNotificationMap[status];
      if (status === DeploymentStatus.FAILED) {
          const errorDetails = event.data.errorDetails;
          notificationService.errorWithDetails(chainName, notificationData.message, errorDetails);
          return;
      }
      notificationService.info(chainName, notificationData.message);
    }

  const getDeployments = async () => {
    if (!chainId) return;
    try {
      setIsLoading(true);
      return await api.getDeployments(chainId);
    } catch (error) {
      notificationService.requestFailed("Failed to load deployments", error);
    } finally {
      setIsLoading(false);
    }
  };

  const updateDeployments = async () => {
    const data = await getDeployments();
    setDeployments(data);
    return data;
  }

  return { isLoading, deployments, setDeployments, setIsLoading };
};
