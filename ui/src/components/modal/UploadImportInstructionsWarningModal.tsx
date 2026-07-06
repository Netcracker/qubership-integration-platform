import React from "react";
import { Button, Modal } from "antd";
import { useModalContext } from "../../ModalContextProvider.tsx";

type UploadImportInstructionsWarningModalProps = {
  onUpload: () => void;
};

export const UploadImportInstructionsWarningModal: React.FC<
  UploadImportInstructionsWarningModalProps
> = ({ onUpload }) => {
  const { closeContainingModal } = useModalContext();

  const handleUpload = () => {
    closeContainingModal();
    onUpload();
  };

  return (
    <Modal
      title="Upload Import Instructions"
      open={true}
      onCancel={closeContainingModal}
      footer={[
        <Button key="cancel" onClick={closeContainingModal}>
          Cancel
        </Button>,
        <Button key="upload" type="primary" danger onClick={handleUpload}>
          Upload
        </Button>,
      ]}
    >
      The Delete instruction will be executed immediately and will not be
      stored.
    </Modal>
  );
};
