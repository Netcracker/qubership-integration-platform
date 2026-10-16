import { useEffect, useState, useRef, useMemo, useCallback } from "react";
import { Chain } from "../../api/apiTypes.ts";
import { api } from "../../api/api.ts";
import {
  createCompactChainSchema,
  CompactChainSchema,
} from "./chainContextUtils.ts";
import { useCatalogRefresh } from "../../hooks/useCatalogRefresh.ts";
import { useNotificationService } from "../../hooks/useNotificationService.tsx";

export interface ChainContext {
  chain: Chain;
  compactSchema: CompactChainSchema;
  refresh: () => Promise<void>;
  getFreshContext?: () => Promise<ChainContext>;
}

export function useChainContext(): ChainContext | null {
  const [chain, setChain] = useState<Chain | null>(null);
  const chainRef = useRef(chain);
  const readGeneration = useRef(0);
  const refreshErrorRef = useRef<Error>();
  const notificationService = useNotificationService();
  const [pathname, setPathname] = useState<string>(
    typeof window !== "undefined" ? window.location.pathname : "",
  );
  const lastChainIdRef = useRef<string | null>(null);
  const isDebugEnabled = useMemo(
    () =>
      typeof window !== "undefined" &&
      window.localStorage.getItem("aiDebug") === "true",
    [],
  );

  // Function to manually refresh chain data
  const refreshChain = useCallback(async () => {
    const pathMatch = pathname.match(/^\/chains\/([^/]+)/);
    const chainId = pathMatch ? pathMatch[1] : null;

    if (!chainId) {
      console.log("[useChainContext] refreshChain: no chainId");
      return;
    }

    console.log(
      `[useChainContext] refreshChain: fetching chain data for chainId: ${chainId}`,
    );
    const generation = ++readGeneration.current;
    try {
      const chainData = await api.getChain(chainId);
      if (
        lastChainIdRef.current === chainId &&
        generation === readGeneration.current
      ) {
        console.log(
          `[useChainContext] refreshChain: chain data updated for chainId: ${chainId}`,
          {
            chainName: chainData.name,
            elementsCount: chainData.elements?.length || 0,
          },
        );
        chainRef.current = chainData;
        refreshErrorRef.current = undefined;
        setChain(chainData);
      } else {
        console.log(
          `[useChainContext] refreshChain: chainId mismatch, ignoring update`,
          {
            fetchedChainId: chainId,
            lastChainId: lastChainIdRef.current,
          },
        );
      }
    } catch (error) {
      if (
        lastChainIdRef.current === chainId &&
        generation === readGeneration.current
      )
        refreshErrorRef.current =
          error instanceof Error ? error : new Error(String(error));
      console.error(
        `[useChainContext] refreshChain: failed to refresh chain for chainId: ${chainId}`,
        error,
      );
      if (isDebugEnabled) {
        console.debug("[AI Chat] Failed to refresh chain for context", error);
      }
      throw error;
    }
  }, [pathname, isDebugEnabled]);

  const currentChainId = pathname.startsWith("/chains/")
    ? pathname.split("/")[2]
    : undefined;
  const { refresh: refreshExternal, waitForIdle } = useCatalogRefresh(
    currentChainId ?? "",
    (change) =>
      Boolean(
        currentChainId &&
          (change.chainIds === null ||
            change.chainIds.includes(currentChainId)),
      ),
    async (isCurrent) => {
      if (!currentChainId) return;
      const generation = ++readGeneration.current;
      try {
        const data = await api.getChain(currentChainId);
        if (!isCurrent() || generation !== readGeneration.current) return;
        chainRef.current = data;
        refreshErrorRef.current = undefined;
        setChain(data);
      } catch (error) {
        if (!isCurrent() || generation !== readGeneration.current) return;
        refreshErrorRef.current =
          error instanceof Error ? error : new Error(String(error));
        notificationService.requestFailed(
          "Failed to refresh Rocky's chain context",
          error,
          () => void refreshExternal().catch(() => undefined),
        );
        throw error;
      }
    },
  );

  useEffect(() => {
    const updatePathname = () => {
      if (typeof window !== "undefined") {
        const currentPathname = window.location.pathname;
        if (currentPathname !== pathname) {
          setPathname(currentPathname);
        }
      }
    };

    updatePathname();

    if (typeof window !== "undefined") {
      window.addEventListener("popstate", updatePathname);
      window.addEventListener("pushstate", updatePathname);
      window.addEventListener("replacestate", updatePathname);

      const originalPushState = history.pushState.bind(history);
      const originalReplaceState = history.replaceState.bind(history);

      history.pushState = function (...args) {
        originalPushState.apply(history, args);
        updatePathname();
      };

      history.replaceState = function (...args) {
        originalReplaceState.apply(history, args);
        updatePathname();
      };

      return () => {
        window.removeEventListener("popstate", updatePathname);
        window.removeEventListener("pushstate", updatePathname);
        window.removeEventListener("replacestate", updatePathname);
        history.pushState = originalPushState;
        history.replaceState = originalReplaceState;
      };
    }
  }, [pathname]);

  useEffect(() => {
    const pathMatch = pathname.match(/^\/chains\/([^/]+)/);
    const chainId = pathMatch ? pathMatch[1] : null;

    if (!chainId) {
      if (lastChainIdRef.current !== null) {
        lastChainIdRef.current = null;
        chainRef.current = null;
        refreshErrorRef.current = undefined;
        setChain(null);
      }
      return;
    }

    if (lastChainIdRef.current === chainId) {
      return;
    }

    lastChainIdRef.current = chainId;
    const generation = ++readGeneration.current;

    api
      .getChain(chainId)
      .then((chainData) => {
        if (
          lastChainIdRef.current === chainId &&
          generation === readGeneration.current
        ) {
          chainRef.current = chainData;
          refreshErrorRef.current = undefined;
          setChain(chainData);
        }
      })
      .catch((error) => {
        if (isDebugEnabled) {
          console.debug("[AI Chat] Failed to load chain for context", error);
        }
        if (
          lastChainIdRef.current === chainId &&
          generation === readGeneration.current
        ) {
          chainRef.current = null;
          setChain(null);
        }
      });
  }, [isDebugEnabled, pathname]);

  // Listen for chain update events
  useEffect(() => {
    const pathMatch = pathname.match(/^\/chains\/([^/]+)/);
    const chainId = pathMatch ? pathMatch[1] : null;

    if (!chainId) {
      return;
    }

    console.log(
      `[useChainContext] Setting up chain-updated listener for chainId: ${chainId}`,
    );

    const handleChainUpdated = (event: CustomEvent<string>) => {
      const updatedChainId = event.detail;
      console.log(`[useChainContext] Received chain-updated event:`, {
        updatedChainId,
        currentChainId: chainId,
        lastChainId: lastChainIdRef.current,
        matches:
          updatedChainId === chainId && lastChainIdRef.current === chainId,
      });
      if (updatedChainId === chainId && lastChainIdRef.current === chainId) {
        console.log(
          `[useChainContext] Refreshing chain data for chainId: ${chainId}`,
        );
        void refreshChain().catch(() => undefined);
      }
    };

    window.addEventListener(
      "chain-updated",
      handleChainUpdated as EventListener,
    );

    return () => {
      console.log(
        `[useChainContext] Removing chain-updated listener for chainId: ${chainId}`,
      );
      window.removeEventListener(
        "chain-updated",
        handleChainUpdated as EventListener,
      );
    };
  }, [pathname, refreshChain]);

  const context = useMemo(() => {
    if (!chain) {
      return null;
    }

    const compactSchema = createCompactChainSchema(chain);
    return {
      chain,
      compactSchema,
      refresh: refreshChain,
      getFreshContext: async () => {
        await waitForIdle();
        if (refreshErrorRef.current) throw refreshErrorRef.current;
        const latest = chainRef.current;
        if (!latest || latest.id !== currentChainId)
          throw new Error("The open chain context is not available.");
        return {
          chain: latest,
          compactSchema: createCompactChainSchema(latest),
          refresh: refreshChain,
        };
      },
    };
  }, [chain, refreshChain, waitForIdle, currentChainId]);

  return context;
}
