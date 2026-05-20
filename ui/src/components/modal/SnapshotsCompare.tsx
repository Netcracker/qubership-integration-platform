import { Modal } from "antd";
import { useModalContext } from "../../ModalContextProvider.tsx";
import React from "react";
import { NotImplemented } from "../../pages/NotImplemented.tsx";

type SnapshotCompareProps = {
  oneId: string;
  otherId: string;
};

// @ts-expect-error Not implemented yet, so properties are not used.
export const SnapshotsCompare: React.FC<SnapshotCompareProps> = ({
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  oneId,
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  otherId,
}) => {
  const { closeContainingModal } = useModalContext();

  return (
    <Modal
      title="Chain Difference"
      centered
      open={true}
      onCancel={closeContainingModal}
      footer={null}
      width={"90%"}
    >
      <NotImplemented style={{ height: "80vh", resize: "none" }} />
    </Modal>
  );
};
