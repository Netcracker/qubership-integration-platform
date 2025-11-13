import { Button, Modal } from "antd";
import { useModalContext } from "../../ModalContextProvider";

type UnsavedChangesModalProps = {
  onYes: () => void;
  onNo?: () => void;
};

export const UnsavedChangesModal: React.FC<UnsavedChangesModalProps> = (
  props: UnsavedChangesModalProps,
) => {
  const { closeContainingModal } = useModalContext();

  const handleYes = () => {
    closeContainingModal();
    props.onYes();
  };

  const handleNo = () => {
    closeContainingModal();
    props.onNo?.();
  };

  return (
    <Modal
      title="Unsaved Changes"
      open={true}
      onCancel={handleNo}
      onOk={handleYes}
      footer={[
        <Button
          key="submit"
          type="primary"
          htmlType={"submit"}
          onClick={handleYes}
        >
          Yes
        </Button>,
        <Button key="cancel" onClick={handleNo}>
          No
        </Button>,
      ]}
    >
      You have made changes, that haven&apos;t been saved. Are you sure you want
      to leave the window and discard the changes?
    </Modal>
  );
};
