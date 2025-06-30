import { api } from "../api/api.ts";
import { EngineDomain } from "../api/apiTypes.ts";
import { useCallback, useEffect, useState } from "react";
import { useNotificationService } from "./useNotificationService.tsx";

export const useDomains = () => {
  const [isLoading, setIsLoading] = useState(false);
  const [domains, setDomains] = useState<EngineDomain[]>();
  const notificationService = useNotificationService();

  const getDomains = useCallback(async () => {
    try {
      setIsLoading(true);
      const data = await api.getDomains();
      setDomains(data);
    } catch (error) {
      notificationService.requestFailed("Failed to load domains", error);
    } finally {
      setIsLoading(false);
    }
  }, [notificationService]);

  useEffect(() => {
    void getDomains();
  }, [getDomains]);

  return { isLoading, domains };
};
