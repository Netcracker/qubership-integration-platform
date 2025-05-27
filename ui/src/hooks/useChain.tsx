import { api } from "../api/api.ts";
import { Chain } from "../api/apiTypes.ts";
import { useEffect, useState } from "react";
import { useNotificationService } from "./useNotificationService.tsx";

export const useChain = (chainId?: string) => {
  const [chain, setChain] = useState<Chain>();
  const notificationService = useNotificationService();

  useEffect(() => {
    getChain().then(setChain);
  }, []);

  const updateChain = async (chain: Partial<Chain>) => {
    if (!chainId) return;
    try {
      return api.updateChain(chainId, chain);
    } catch (error) {
      notificationService.requestFailed("Failed to update chain", error);
    }
  };

  const getChain = async () => {
    if (!chainId) return;
    try {
      return api.getChain(chainId);
    } catch (error) {
      notificationService.requestFailed("Failed to load snapshots", error);
    }
  };

  return { chain, setChain, updateChain };
};
