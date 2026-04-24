import { ChainElementCodeResponse } from "../api/apiTypes.ts";
import { api } from "../api/api.ts";
import { useNotificationService } from "./useNotificationService.tsx";
import { useCallback, useEffect, useRef, useState } from "react";

/**
 * Fetches the YAML code view of a chain's elements.
 *
 * Fetch fires when `chainId` or `cacheKey` transitions to a new value.
 * Pass a stable signature derived from the elements (e.g. via
 * `buildElementsSignature`) to avoid spurious requests when the elements
 * array reference changes but content is identical.
 */
export const useElementsAsCode = (
  chainId: string,
  cacheKey?: string | number,
): {
  elementAsCode: ChainElementCodeResponse | undefined;
  refresh: () => Promise<void>;
} => {
  const [elementAsCode, setElementAsCode] =
    useState<ChainElementCodeResponse>();
  const notificationService = useNotificationService();
  const notificationRef = useRef(notificationService);
  notificationRef.current = notificationService;

  const getElementsAsCode = useCallback(async () => {
    if (!chainId) return;
    try {
      const response = await api.getElementsAsCode(chainId);
      setElementAsCode(response);
    } catch (error) {
      notificationRef.current.requestFailed(
        "Failed to get elements as code",
        error,
      );
    }
  }, [chainId]);

  useEffect(() => {
    if (!chainId || !cacheKey) return;
    void getElementsAsCode();
  }, [chainId, cacheKey, getElementsAsCode]);

  return {
    elementAsCode,
    refresh: getElementsAsCode,
  };
};
