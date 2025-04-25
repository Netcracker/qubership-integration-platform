import React from "react";
import { FloatButton, Table } from "antd";
import { useSnapshots } from "../hooks/useSnapshots.tsx";
import { useParams } from "react-router";
import { PlusOutlined } from "@ant-design/icons";

export const Snapshots: React.FC = () => {
  const { chainId } = useParams<{ chainId: string }>();
  const { columns, snapshots, createSnapshot } = useSnapshots(chainId);

  const onClick = async () => {
    if (!chainId) return;
    await createSnapshot(chainId);
  };

  return (
    <>
      <Table
        columns={columns}
        dataSource={snapshots}
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
