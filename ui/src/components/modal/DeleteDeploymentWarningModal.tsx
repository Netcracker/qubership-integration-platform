import React from "react";
import { Button, Modal } from "antd";
import { useModalContext } from "../../ModalContextProvider.tsx";

type DeleteDeploymentWarningModalProps = {
  onDelete: () => void;
};

export const DeleteDeploymentWarningModal: React.FC<
  DeleteDeploymentWarningModalProps
> = ({ onDelete }) => {
  const { closeContainingModal } = useModalContext();

  const handleDelete = () => {
    closeContainingModal();
    onDelete();
  };

  return (
    <Modal
      title="Delete Deployment"
      open={true}
      onCancel={closeContainingModal}
      footer={[
        <Button key="cancel" onClick={closeContainingModal}>
          Cancel
        </Button>,
        <Button key="delete" type="primary" danger onClick={handleDelete}>
          Delete
        </Button>,
      ]}
    >
      Are you sure you want to permanently delete this deployment?
    </Modal>
  );
};
