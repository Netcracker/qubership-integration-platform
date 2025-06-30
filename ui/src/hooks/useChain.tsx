import { api } from "../api/api.ts";
import { Chain } from "../api/apiTypes.ts";
import { useCallback, useEffect, useState } from "react";
import { useNotificationService } from "./useNotificationService.tsx";

export const useChain = (chainId?: string) => {
  const [isLoading, setIsLoading] = useState(false);
  const [chain, setChain] = useState<Chain>();
  const notificationService = useNotificationService();

  const updateChain = useCallback(
    async (chain: Partial<Chain>) => {
      if (!chainId) return;
      setIsLoading(true);
      try {
        return api.updateChain(chainId, chain);
      } catch (error) {
        notificationService.requestFailed("Failed to update chain", error);
      } finally {
        setIsLoading(false);
      }
    },
    [chainId, notificationService],
  );

  const getChain = useCallback(async () => {
    if (!chainId) return;
    setIsLoading(true);
    try {
      return api.getChain(chainId);
    } catch (error) {
      notificationService.requestFailed("Failed to load snapshots", error);
    } finally {
      setIsLoading(false);
    }
  }, [chainId, notificationService]);

  useEffect(() => {
    void getChain().then(setChain);
  }, [getChain]);

  return { isLoading, chain, setChain, updateChain };
};
