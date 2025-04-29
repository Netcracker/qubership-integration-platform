import { api } from "../api/api.ts";
import { Snapshot } from "../api/apiTypes.ts";
import { useEffect, useState } from "react";
import { notification } from "antd";

export const useSnapshots = (chainId?: string) => {
  const [isLoading, setIsLoading] = useState(false);
  const [snapshots, setSnapshots] = useState<Snapshot[]>();

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
      notification.error({
        message: "Request failed",
        description: "Failed to load snapshots",
      });
    } finally {
      setIsLoading(false);
    }
  };

  return { isLoading, snapshots, setSnapshots };
};
