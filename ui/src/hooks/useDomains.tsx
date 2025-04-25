import { api } from "../api/api.ts";
import { EngineDomain } from "../api/apiTypes.ts";
import { useEffect, useState } from "react";
import { notification } from "antd";

export const useDomains = () => {
  const [isLoading, setIsLoading] = useState(false);
  const [domains, setDomains] = useState<EngineDomain[]>();

  useEffect(() => {
    getDomains();
  }, []);

  const getDomains = async () => {
    try {
      setIsLoading(true);
      const data = await api.getDomains();
      setDomains(data);
    } catch (error) {
      notification.error({
        message: "Request failed",
        description: "Failed to load domains",
      });
    } finally {
      setIsLoading(false);
    }
  };

  return { isLoading, domains };
};
