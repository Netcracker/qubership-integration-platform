import { api } from "../api/api.ts";
import { EngineDomain } from "../api/apiTypes.ts";
import { useEffect, useState } from "react";
import { useNotificationService } from "./useNotificationService.tsx";

export const useDomains = () => {
  const [isLoading, setIsLoading] = useState(false);
  const [domains, setDomains] = useState<EngineDomain[]>();
  const notificationService = useNotificationService();

  useEffect(() => {
    getDomains();
  }, []);

  const getDomains = async () => {
    try {
      setIsLoading(true);
      const data = await api.getDomains();
      const seenIds = new Set();
      const domains: EngineDomain[] = data
        .filter(domain => domain && domain.id.length > 0)
        .map((domain, index) => {
          const uniqueId = seenIds.has(domain.id) ? `${domain.id}-${index}` : domain.id;
          seenIds.add(domain.id);
          return ({
            id: uniqueId,
            name: domain.name,
            namespace: domain.namespace,
            replicas: domain.replicas,
            version: domain.version,
          });
        });
      setDomains(domains);
    } catch (error) {
      notificationService.requestFailed("Failed to load domains", error);
    } finally {
      setIsLoading(false);
    }
  };

  return { isLoading, domains };
};
