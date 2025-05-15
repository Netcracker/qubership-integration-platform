import { Modal } from "antd";
import { useModalContext } from "../../ModalContextProvider.tsx";
import React from "react";
import { NotImplemented } from "../../pages/NotImplemented.tsx";

type SnapshotSequenceDiagramProps = {
  snapshotId: string;
};

export const SnapshotSequenceDiagram: React.FC<
  SnapshotSequenceDiagramProps
  // @ts-ignore
> = ({ snapshotId }) => {
  const { closeContainingModal } = useModalContext();

  return (
    <Modal
      title="Snapshot Sequence Diagram"
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
