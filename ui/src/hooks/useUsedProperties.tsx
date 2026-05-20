import { api } from "../api/api.ts";
import { UsedProperty } from "../api/apiTypes.ts";
import { useCallback, useEffect, useState } from "react";
import { useNotificationService } from "./useNotificationService.tsx";

export const useUsedProperties = (chainId: string) => {
  const [isLoading, setIsLoading] = useState(false);
  const [properties, setProperties] = useState<UsedProperty[]>([]);
  const notificationService = useNotificationService();

  const getUsedProperties = useCallback(async () => {
    try {
      setIsLoading(true);
      const responseData = await api.getUsedProperties(chainId);
      setProperties(responseData);
    } catch (error) {
      notificationService.requestFailed(
        "Failed to load Used Properties",
        error,
      );
    } finally {
      setIsLoading(false);
    }
  }, [chainId, notificationService]);

  useEffect(() => {
    if (chainId) {
      void getUsedProperties();
    }
  }, [chainId, getUsedProperties]);

  return {
    properties,
    isLoading,
    refresh: getUsedProperties,
  };
};
