import { ChainElementCodeResponse } from "../api/apiTypes.ts";
import { api } from "../api/api.ts";
import { useNotificationService } from "./useNotificationService.tsx";
import { useCallback, useEffect, useState } from "react";

export const useElementsAsCode = (
  chainId: string,
  timestamp?: number,
): {
  elementAsCode: ChainElementCodeResponse | undefined;
  refresh: () => Promise<void>;
} => {
  const [elementAsCode, setElementAsCode] =
    useState<ChainElementCodeResponse>();
  const notificationService = useNotificationService();

  const getElementsAsCode = useCallback(async () => {
    try {
      const elementAsCode = await api.getElementsAsCode(chainId);
      setElementAsCode(elementAsCode);
    } catch (error) {
      notificationService.requestFailed(
        "Failed to get elements as code",
        error,
      );
    }
  }, [chainId, notificationService]);

  useEffect(() => {
    if (chainId && timestamp) {
      void getElementsAsCode();
    }
  }, [chainId, getElementsAsCode, timestamp]);

  return {
    elementAsCode,
    refresh: getElementsAsCode,
  };
};
