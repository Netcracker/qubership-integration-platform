import { api } from "../api/api.ts";
import { Chain } from "../api/apiTypes.ts";
import { useEffect, useState } from "react";
import { notification } from "antd";

export const useChain = (chainId?: string) => {
  const [chain, setChain] = useState<Chain>();

  useEffect(() => {
    if (!chainId) return;
    getChain(chainId);
  }, []);

  const updateChain = async (chainId: string, chain: Partial<Chain>) => {
    try {
      await api.updateChain(chainId, chain);
    } catch (error) {
      notification.error({
        message: "Request failed",
        description: "Failed to create snapshot",
      });
    }
  };

  const getChain = async (chainId: string) => {
    try {
      const data = await api.getChain(chainId);
      setChain(data);
    } catch (error) {
      notification.error({
        message: "Request failed",
        description: "Failed to load snapshots",
      });
    }
  };

  return { chain, updateChain };
};
