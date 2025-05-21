import { api } from "../api/api.ts";
import { Chain } from "../api/apiTypes.ts";
import { useEffect, useState } from "react";
import { notification } from "antd";

export const useChain = (chainId?: string) => {
  const [chain, setChain] = useState<Chain>();

  useEffect(() => {
    getChain().then(setChain);
  }, []);

  const updateChain = async (chain: Partial<Chain>) => {
    if (!chainId) return;
    try {
      return api.updateChain(chainId, chain);
    } catch (error) {
      notification.error({
        message: "Request failed",
        description: "Failed to update chain",
      });
    }
  };

  const getChain = async () => {
    if (!chainId) return;
    try {
      return api.getChain(chainId);
    } catch (error) {
      notification.error({
        message: "Request failed",
        description: "Failed to get chain",
      });
    }
  };

  return { chain, setChain, updateChain };
};
