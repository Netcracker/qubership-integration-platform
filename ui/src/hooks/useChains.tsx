import { useEffect, useState } from "react";
import { Chain } from "../api/apiTypes.ts";
import { api } from "../api/api.ts";
import { Button } from "antd";
import { TableProps } from "antd/lib/table";
import { useNotificationService } from "./useNotificationService.tsx";

export const useChains = () => {
  const [chains, setChains] = useState<Chain[]>([]);
  const [isLoading, setIsLoading] = useState<boolean>(true);
  const notificationService = useNotificationService();

  useEffect(() => {
    loadChains();
  }, []);

  const columns: TableProps<Chain>["columns"] = [
    { title: "ID", dataIndex: "id", key: "id" },
    { title: "Name", dataIndex: "name", key: "name" },
    { title: "Description", dataIndex: "description", key: "description" },
    {
      title: "",
      dataIndex: "options",
      key: "action",
      render: (_text, record: Chain) => (
        <Button
          type="primary"
          onClick={(event) => {
            event.stopPropagation();
            deleteChain(record.id);
          }}
        >
          Delete
        </Button>
      ),
    },
  ];

  const loadChains = async () => {
    try {
      const data = await api.getChains();
      setChains(data);
    } catch (error) {
        notificationService.requestFailed("Failed to load chains", error);
    } finally {
      setIsLoading(false);
    }
  };

  const deleteChain = async (id: string) => {
    try {
      await api.deleteChain(id);
      setChains(chains.filter((chain) => chain.id !== id));
    } catch (error) {
      notificationService.requestFailed("Failed to delete chain", error);
    }
  };

  return { chains, isLoading, loadChains, columns };
};
