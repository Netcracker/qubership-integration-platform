import { api } from "../api/api.ts";
import { TableProps } from "antd/lib/table";
import { Deployment } from "../api/apiTypes.ts";
import { useEffect, useState } from "react";
import { notification } from "antd";

export const useDeployments = (chainId?: string) => {
  const columns: TableProps<Deployment>["columns"] = [
    { title: "ID", dataIndex: "id", key: "id" },
    { title: "Name", dataIndex: "name", key: "name" },
  ];

  const [deployments, setDeployments] = useState<Deployment[]>();

  useEffect(() => {
    if (!chainId) return;
    getDeployments(chainId);
  }, []);

  const createDeployment = async (chainId: string) => {
    try {
      // TODO
    } catch (error) {
      notification.error({
        message: "Request failed",
        description: "Failed to create deployment",
      });
    }
  };

  const getDeployments = async (chainId: string) => {
    try {
      const data = await api.getDeployments(chainId);
      setDeployments(data);
    } catch (error) {
      notification.error({
        message: "Request failed",
        description: "Failed to load deployments",
      });
    }
  };

  return { columns, deployments, createDeployment };
};
