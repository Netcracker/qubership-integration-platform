import { api } from "../api/api.ts";
import { Snapshot } from "../api/apiTypes.ts";
import { useEffect, useState } from "react";
import { useNotificationService } from "./useNotificationService.tsx";

export const useSnapshots = (chainId?: string) => {
  const [isLoading, setIsLoading] = useState(false);
  const [snapshots, setSnapshots] = useState<Snapshot[]>();
  const notificationService = useNotificationService();

  useEffect(() => {
    if (!chainId) return;
    getSnapshots(chainId);
  }, [chainId]);

  const getSnapshots = async (chainId: string) => {
    try {
      setIsLoading(true);
      const data = await api.getSnapshots(chainId);
      setSnapshots(data);
    } catch (error) {
      notificationService.requestFailed("Failed to load snapshots", error);
    } finally {
      setIsLoading(false);
    }
  };

  return { isLoading, snapshots, setSnapshots };
};
