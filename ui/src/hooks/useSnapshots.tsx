import { api } from "../api/api.ts";
import { TableProps } from "antd/lib/table";
import { Snapshot } from "../api/apiTypes.ts";
import { useEffect, useState } from "react";
import { notification } from "antd";

export const useSnapshots = (chainId?: string) => {
  const [isLoading, setIsLoading] = useState(false);

  const columns: TableProps<Snapshot>["columns"] = [
    { title: "ID", dataIndex: "id", key: "id" },
    { title: "Name", dataIndex: "name", key: "name" },
  ];

  const [snapshots, setSnapshots] = useState<Snapshot[]>();

  useEffect(() => {
    if (!chainId) return;
    getSnapshots(chainId);
  }, [chainId]);

  const createSnapshot = async (chainId: string) => {
    try {
      await api.createSnapshot(chainId);
    } catch (error) {
      notification.error({
        message: "Request failed",
        description: "Failed to create snapshot",
      });
    }
  };

  const getSnapshots = async (chainId: string) => {
    try {
      setIsLoading(true);
      const data = await api.getSnapshots(chainId);
      setSnapshots(data);
    } catch (error) {
      notification.error({
        message: "Request failed",
        description: "Failed to load snapshots",
      });
    } finally {
      setIsLoading(false);
    }
  };

  return { isLoading, columns, snapshots, createSnapshot };
};
