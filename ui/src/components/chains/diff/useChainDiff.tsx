import { Chain } from "../../../api/apiTypes.ts";
import { useCallback, useEffect, useState } from "react";
import { useNotificationService } from "../../../hooks/useNotificationService.tsx";
import { api } from "../../../api/api.ts";
import { Change } from "./compare/types.ts";
import { compareChains as doCompareChains } from "./compare/compare.ts";

export const useChainDiff = (chainId1: string, chainId2: string) => {
  const [chain1, setChain1] = useState<Chain | undefined>();
  const [chain2, setChain2] = useState<Chain | undefined>();
  const [changes, setChanges] = useState<Change[]>([]);

  const [isLoading, setIsLoading] = useState<boolean>(false);
  const [isChain1Loading, setIsChain1Loading] = useState<boolean>(false);
  const [isChain2Loading, setIsChain2Loading] = useState<boolean>(false);
  const [isComparing, setIsComparing] = useState<boolean>(false);

  const [selectedChangeId, setSelectedChangeId] = useState<
    string | undefined
  >();
  const notificationService = useNotificationService();

  const loadChain = useCallback(
    async (
      id: string,
      setLoading: (state: boolean) => void,
    ): Promise<Chain | undefined> => {
      try {
        setLoading(true);
        return await api.getChain(id);
      } catch (e) {
        notificationService.requestFailed("Failed to load chain", e);
      } finally {
        setLoading(false);
      }
    },
    [notificationService],
  );

  const compareChains = useCallback(
    async (chain1: Chain, chain2: Chain): Promise<Change[]> => {
      try {
        setIsComparing(true);
        return await (async () =>
          Promise.resolve(doCompareChains(chain1, chain2)))();
      } catch (e) {
        notificationService.errorWithDetails("Failed to compare chains", "", e);
        return [];
      } finally {
        setIsComparing(false);
      }
    },
    [notificationService],
  );

  useEffect(() => {
    void loadChain(chainId1, setIsChain1Loading).then(setChain1);
  }, [chainId1, loadChain]);

  useEffect(() => {
    void loadChain(chainId2, setIsChain2Loading).then(setChain2);
  }, [chainId2, loadChain]);

  useEffect(() => {
    if (!chain1 || !chain2) {
      return;
    }
    void compareChains(chain1, chain2).then(setChanges);
  }, [chain1, chain2, compareChains]);

  useEffect(() => {
    setIsLoading(isChain1Loading || isChain2Loading || isComparing);
  }, [isChain1Loading, isChain2Loading, isComparing]);

  return {
    isLoading,
    chain1,
    chain2,
    changes,
    selectedChangeId,
    setSelectedChangeId,
  };
};
