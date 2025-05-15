import { Modal } from "antd";
import { useModalContext } from "../../ModalContextProvider.tsx";
import React from "react";
import { NotImplemented } from "../../pages/NotImplemented.tsx";

type SnapshotCompareProps = {
  oneId: string;
  otherId: string;
};

// @ts-ignore
export const SnapshotsCompare: React.FC<SnapshotCompareProps> = ({
  oneId,
  otherId,
}) => {
  const { closeContainingModal } = useModalContext();

  return (
    <Modal
      title="Chain Difference"
      centered
      open={true}
      onCancel={async () => closeContainingModal()}
      footer={null}
      width={"90%"}
    >
      <NotImplemented style={{ height: "80vh", resize: "none" }} />
    </Modal>
  );
};
