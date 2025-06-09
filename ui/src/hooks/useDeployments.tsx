import { api } from "../api/api.ts";
import { Deployment, Event, ObjectType } from "../api/apiTypes.ts";
import { useEffect, useState } from "react";
import { useNotificationService } from "./useNotificationService.tsx";
import { useEventContext } from "../components/notifications/contexts/EventContext.tsx";
import { useQuery, useQueryClient } from "@tanstack/react-query";

export enum DeploymentStatus {
  DEPLOYED = "DEPLOYED",
  PROCESSING = "PROCESSING",
  FAILED = "FAILED",
  REMOVED = "REMOVED",
}

export type StatusNotificationData = {
  type: "info" | "error";
  message: string;
};

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
  },
};

export const useDeployments = (chainId?: string) => {
  const { subscribe } = useEventContext();
  const [manualLoading, setManualLoading] = useState(false);
  const queryClient = useQueryClient();
  const notificationService = useNotificationService();

  const query = useQuery({
    queryKey: ["deployments", chainId],
    queryFn: async () => {
      if (!chainId) return [];
      return await api.getDeployments(chainId);
    },
    enabled: !!chainId,
    staleTime: 60 * 1000,
    refetchOnWindowFocus: false,
    retry: 1,
  });

  //Rest error handling
  useEffect(() => {
    if (query.error) {
      const error = query.error;
      notificationService.requestFailed("Failed to load deployments", error);
    }
  });

  //Event handling
  useEffect(() => {
    return subscribe(ObjectType.DEPLOYMENT, (event: Event) => {
      if (event.data.chainId !== chainId || !event.data.chainId) return;
      showEventNotification(event);

      return queryClient.invalidateQueries({
        queryKey: ["deployments", event.data.chainId],
        exact: true,
      });
    });
  }, [subscribe, chainId, query, queryClient]);

  function showEventNotification(event: Event) {
    const chainName = event.data.chainName;
    const status: DeploymentStatus = event.data.state.status;
    const notificationData = StatusNotificationMap[status];
    if (status === DeploymentStatus.FAILED) {
      const errorDetails = event.data.errorDetails;
      notificationService.errorWithDetails(
        chainName,
        notificationData.message,
        errorDetails,
      );
      return;
    }
    notificationService.info(chainName, notificationData.message);
  }

  const setDeployments = (items: Deployment[]) => {
    if (!items) return;
    const chainId = items[0]?.chainId ?? undefined;
    return queryClient.invalidateQueries({
      queryKey: ["deployments", chainId],
      exact: true,
    });
  };

  const removeDeployment = (deployment: Deployment) => {
    if (!chainId) return;
    queryClient.setQueryData<Deployment[]>(
      ["deployments", chainId],
      (current) => {
        return Array.isArray(current)
          ? current.filter((d) => d.id !== deployment.id)
          : [];
      },
    );
  };

  const setIsLoading = (value: boolean) => {
    setManualLoading(value);
  };

  return {
    deployments: query.data ?? [],
    isLoading: query.isLoading ?? manualLoading,
    setDeployments,
    removeDeployment,
    setIsLoading,
  };
};
