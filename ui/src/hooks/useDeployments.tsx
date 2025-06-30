import { api } from "../api/api.ts";
import {
  Deployment,
  DeploymentStatus,
  DeploymentUpdate,
  Event,
  ObjectType,
} from "../api/apiTypes.ts";
import { useCallback, useEffect, useState } from "react";
import { useNotificationService } from "./useNotificationService.tsx";
import { useEventContext } from "../components/notifications/contexts/EventContext.tsx";

export type StatusNotificationData = {
  type: "info" | "error";
  message: string;
};

export const StatusNotificationMap: Record<
  DeploymentStatus,
  StatusNotificationData
> = {
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
  },
};

export const useDeployments = (chainId?: string) => {
  const { subscribe } = useEventContext();
  const notificationService = useNotificationService();
  const [deployments, setDeployments] = useState<Deployment[]>([]);
  const [isLoading, setIsLoading] = useState(false);

  const getDeployments = useCallback(async () => {
    if (!chainId) {
      return [];
    }
    setIsLoading(true);
    try {
      return api.getDeployments(chainId);
    } catch (error) {
      notificationService.requestFailed("Failed to load deployments", error);
      return [];
    } finally {
      setIsLoading(false);
    }
  }, [chainId, notificationService]);

  useEffect(() => {
    void getDeployments().then(setDeployments);
  }, [getDeployments]);

  const showEventNotification = useCallback(
    (data: DeploymentUpdate) => {
      const chainName = data.chainName;
      const status: DeploymentStatus = data.state.status;
      const notificationData = StatusNotificationMap[status];
      if (status === DeploymentStatus.FAILED) {
        const errorDetails = data.state.error;
        notificationService.errorWithDetails(
          chainName,
          notificationData.message,
          errorDetails,
        );
        return;
      }
      notificationService.info(chainName, notificationData.message);
    },
    [notificationService],
  );

  useEffect(() => {
    return subscribe(ObjectType.DEPLOYMENT, (event: Event) => {
      const data: DeploymentUpdate = event.data as DeploymentUpdate;
      if (data.chainId !== chainId || !data.chainId) return;
      void getDeployments().then(setDeployments);
      showEventNotification(data);
    });
  }, [chainId, getDeployments, showEventNotification, subscribe]);

  const removeDeployment = useCallback(
    (deployment: Deployment) => {
      setDeployments(deployments.filter((d) => d.id !== deployment.id));
    },
    [deployments],
  );

  return {
    isLoading,
    deployments,
    setDeployments,
    removeDeployment,
  };
};
