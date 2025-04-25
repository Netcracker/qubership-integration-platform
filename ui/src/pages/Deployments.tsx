import React from "react";
import { FloatButton, Table } from "antd";
import { useDeployments } from "../hooks/useDeployments.tsx";
import { useParams } from "react-router";
import { PlusOutlined } from "@ant-design/icons";

export const Deployments: React.FC = () => {
  const { chainId } = useParams<{ chainId: string }>();
  const { columns, deployments, createDeployment } = useDeployments(chainId);

  const onClick = async () => {
    if (!chainId) return;
    await createDeployment(chainId);
  };

  return (
      <>
        <Table
          columns={columns}
          dataSource={deployments}
          pagination={false}
          rowKey="name"
          scroll={{ y: "calc(100vh - 200px)" }}
        />
        <FloatButton
          icon={<PlusOutlined />}
          onClick={onClick}
        />
      </>
  );
};