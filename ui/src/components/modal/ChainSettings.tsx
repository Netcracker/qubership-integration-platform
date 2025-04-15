import React from "react";
import { Drawer, Table } from "antd";
import { useModalContext } from "../../ModalContextProvider.tsx";

export const ChainSettings: React.FC = () => {
  const { closeContainingModal } = useModalContext();

  return (
    <Drawer open size="large" onClose={closeContainingModal}>
      <Table />
    </Drawer>
  );
};
