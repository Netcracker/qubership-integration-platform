import { useEffect, useState } from "react";
import { Chain } from "../api/apiTypes.ts";
import { api } from "../api/api.ts";
import { Button, notification } from "antd";
import { TableProps } from "antd/lib/table";

export const useChains = () => {
  const [chains, setChains] = useState<Chain[]>([]);
  const [isLoading, setIsLoading] = useState<boolean>(true);

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
      notification.error({
        message: "Request failed",
        description: "Failed to load chains",
      });
    } finally {
      setIsLoading(false);
    }
  };

  const deleteChain = async (id: string) => {
    try {
      await api.deleteChain(id);
      setChains(chains.filter((chain) => chain.id !== id));
    } catch (error) {
      notification.error({ message: "Api call failed" });
    }
  };

  return { chains, isLoading, loadChains, columns };
};
