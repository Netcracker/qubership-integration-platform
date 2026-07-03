import { api } from "../api/api.ts";
import { Chain, RestApiError } from "../api/apiTypes.ts";
import { VSCodeExtensionApi } from "../api/rest/vscodeExtensionApi.ts";
import { useCallback, useEffect, useState } from "react";
import { useNotificationService } from "./useNotificationService.tsx";

export const useChain = (chainId?: string, options?: { filePath?: string }) => {
  const [isLoading, setIsLoading] = useState(Boolean(chainId));
  const [chain, setChain] = useState<Chain>();
  const [error, setError] = useState<Error | null>(null);
  const notificationService = useNotificationService();

  const updateChain = useCallback(
    async (chain: Partial<Chain>) => {
      if (!chainId) return;
      setIsLoading(true);
      try {
        return await api.updateChain(chainId, chain);
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
    setError(null);
    try {
      const chainData =
        options?.filePath && api instanceof VSCodeExtensionApi
          ? await api.getChain(chainId, options.filePath)
          : await api.getChain(chainId);
      setChain(chainData);
      return chainData;
    } catch (error) {
      setError(error as Error);
      if (error instanceof RestApiError && error.responseCode === 404) {
        return;
      }
      notificationService.requestFailed("Failed to load chain", error);
    } finally {
      setIsLoading(false);
    }
  }, [chainId, notificationService, options?.filePath]);

  useEffect(() => {
    void getChain().then(setChain);
  }, [getChain]);

  return { isLoading, chain, setChain, updateChain, getChain, error };
};
