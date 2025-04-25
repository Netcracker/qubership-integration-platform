import { api } from "../api/api.ts";
import { Deployment } from "../api/apiTypes.ts";
import { useEffect, useState } from "react";
import { notification } from "antd";

export const useDeployments = (chainId?: string) => {
  const [isLoading, setIsLoading] = useState(false);
  const [deployments, setDeployments] = useState<Deployment[]>();

  useEffect(() => {
    updateDeployments();
  }, [chainId]);

  const getDeployments = async () => {
    if (!chainId) return;
    try {
      setIsLoading(true);
      return await api.getDeployments(chainId);
    } catch (error) {
      notification.error({
        message: "Request failed",
        description: "Failed to load deployments",
      });
    } finally {
      setIsLoading(false);
    }
  };

  const updateDeployments = async () => {
    const data = await getDeployments();
    setDeployments(data);
  }

  return { isLoading, deployments, setDeployments };
};
