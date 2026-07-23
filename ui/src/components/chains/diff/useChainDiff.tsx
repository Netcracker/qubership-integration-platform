import { Chain, ChainSnapshot } from "../../../api/apiTypes.ts";
import { useCallback, useEffect, useMemo, useState } from "react";
import { useNotificationService } from "../../../hooks/useNotificationService.tsx";
import { api } from "../../../api/api.ts";
import { Change } from "./compare/types.ts";
import { compareChains as doCompareChains } from "./compare/compare.ts";

export type ChainItem = {
  kind: "chain";
  id: string;
};

export type SnapshotItem = {
  kind: "snapshot";
  id: string;
};

export type ArchiveItem = {
  kind: "archive";
  id: string;
  archive: File;
};

export type DocumentItem = {
  kind: "document";
  content: Chain;
};

export type ComparableItem =
  | ChainItem
  | SnapshotItem
  | ArchiveItem
  | DocumentItem;

export function asChain(snapshot: ChainSnapshot): Chain {
  return {
    ...snapshot,
    navigationPath: [],
    deployments: [],
    unsavedChanges: false,
    businessDescription: "",
    assumptions: "",
    outOfScope: "",
    containsDeprecatedContainers: false,
    containsDeprecatedElements: false,
    containsUnsupportedElements: false,
  };
}

export const useChainDiff = (item1: ComparableItem, item2: ComparableItem) => {
  const [chain1, setChain1] = useState<Chain | undefined>();
  const [chain2, setChain2] = useState<Chain | undefined>();

  const [isChain1Loading, setIsChain1Loading] = useState<boolean>(false);
  const [isChain2Loading, setIsChain2Loading] = useState<boolean>(false);

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

  const loadSnapshot = useCallback(
    async (
      id: string,
      setLoading: (state: boolean) => void,
    ): Promise<Chain | undefined> => {
      try {
        setLoading(true);
        const snapshot = await api.getChainSnapshot(id);
        return asChain(snapshot);
      } catch (e) {
        notificationService.requestFailed("Failed to load chain snapshot", e);
      } finally {
        setLoading(false);
      }
    },
    [notificationService],
  );

  const loadChainFromArchive = useCallback(
    async (archive: File, id: string, setLoading: (state: boolean) => void) => {
      try {
        setLoading(true);
        return await api.extractChain(archive, id);
      } catch (e) {
        notificationService.requestFailed(
          "Failed to load chain from archive",
          e,
        );
      } finally {
        setLoading(false);
      }
    },
    [notificationService],
  );

  const loadItem = useCallback(
    async (
      item: ComparableItem,
      setLoading: (state: boolean) => void,
    ): Promise<Chain | undefined> => {
      return item.kind === "chain"
        ? loadChain(item.id, setLoading)
        : item.kind === "snapshot"
          ? loadSnapshot(item.id, setLoading)
          : item.kind === "archive"
            ? loadChainFromArchive(item.archive, item.id, setLoading)
            : item.content;
    },
    [loadChain, loadSnapshot, loadChainFromArchive],
  );

  useEffect(() => {
    void loadItem(item1, setIsChain1Loading).then(setChain1);
  }, [item1, loadItem]);

  useEffect(() => {
    void loadItem(item2, setIsChain2Loading).then(setChain2);
  }, [item2, loadItem]);

  // Deriving the comparison synchronously leaves no render frame where both
  // chains are set but the changes describe older inputs. An undefined result
  // means there is no valid comparison: a chain is missing or the compare
  // threw.
  const comparison = useMemo(():
    | { changes: Change[]; error?: never }
    | { changes?: never; error: unknown }
    | undefined => {
    if (!chain1 || !chain2) {
      return undefined;
    }
    try {
      return { changes: doCompareChains(chain1, chain2) };
    } catch (e) {
      return { error: e };
    }
  }, [chain1, chain2]);

  useEffect(() => {
    if (comparison?.error) {
      notificationService.errorWithDetails(
        "Failed to compare chains",
        "",
        comparison.error,
      );
    }
  }, [comparison, notificationService]);

  const changes = comparison?.changes;
  const isLoading = isChain1Loading || isChain2Loading;

  return {
    isLoading,
    chain1,
    chain2,
    changes,
    selectedChangeId,
    setSelectedChangeId,
  };
};
