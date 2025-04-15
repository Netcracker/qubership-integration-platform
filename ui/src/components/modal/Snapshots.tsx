import React from "react";
import { Button, Drawer, Table } from "antd";
import { useModalContext } from "../../ModalContextProvider.tsx";
import { useSnapshots } from "../../hooks/useSnapshots.tsx";

type SnapshotProps = {
  chainId: string;
};

export const Snapshots: React.FC<SnapshotProps> = ({ chainId }) => {
  const { closeContainingModal } = useModalContext();
  const { columns, snapshots, createSnapshot } = useSnapshots(chainId);

  const onClick = async () => {
    if (!chainId) return;
    await createSnapshot(chainId);
  };

  return (
    <Drawer size="large" open onClose={closeContainingModal}>
      <Button onClick={onClick}>Create snapshot</Button>
      <Table
        columns={columns}
        dataSource={snapshots}
        pagination={false}
        rowKey="name"
      />
    </Drawer>
  );
};
