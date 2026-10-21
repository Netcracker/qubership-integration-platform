import { api } from "../api/api.ts";
import { Chain, RestApiError } from "../api/apiTypes.ts";
import { VSCodeExtensionApi } from "../api/rest/vscodeExtensionApi.ts";
import { useCallback, useEffect, useRef, useState } from "react";
import { useNotificationService } from "./useNotificationService.tsx";

export const useChain = (chainId?: string, options?: { filePath?: string }) => {
  const [isLoading, setIsLoading] = useState(Boolean(chainId));
  const [chain, setChain] = useState<Chain>();
  const [error, setError] = useState<Error | null>(null);
  const notificationService = useNotificationService();
  const activeKey = `${chainId ?? ""}:${options?.filePath ?? ""}`;
  const readGeneration = useRef(0);
  const activeKeyRef = useRef(activeKey);
  activeKeyRef.current = activeKey;

  const updateChain = useCallback(
    async (chain: Partial<Chain>) => {
      if (!chainId) return;
      setIsLoading(true);
      try {
        return await api.updateChain(chainId, chain);
      } catch (error) {
        notificationService.requestFailed("Failed to update chain", error);
        throw error;
      } finally {
        setIsLoading(false);
      }
    },
    [chainId, notificationService],
  );

  const getChain = useCallback(
    async (background = false, isCurrent: () => boolean = () => true) => {
      if (!chainId) return;
      const generation = ++readGeneration.current;
      if (!background) {
        setIsLoading(true);
        setError(null);
      }
      try {
        const chainData =
          options?.filePath && api instanceof VSCodeExtensionApi
            ? await api.getChain(chainId, options.filePath)
            : await api.getChain(chainId);
        if (
          activeKeyRef.current !== activeKey ||
          generation !== readGeneration.current ||
          !isCurrent()
        )
          return;
        setError(null);
        if (!background) setChain(chainData);
        return chainData;
      } catch (error) {
        if (
          activeKeyRef.current !== activeKey ||
          generation !== readGeneration.current ||
          !isCurrent()
        )
          return;
        if (background) throw error;
        setError(error as Error);
        if (error instanceof RestApiError && error.responseCode === 404) {
          return;
        }
        notificationService.requestFailed("Failed to load chain", error);
      } finally {
        if (!background && activeKeyRef.current === activeKey)
          setIsLoading(false);
      }
    },
    [chainId, notificationService, options?.filePath, activeKey],
  );

  useEffect(() => {
    void getChain();
  }, [getChain]);

  return { isLoading, chain, setChain, updateChain, getChain, error };
};
