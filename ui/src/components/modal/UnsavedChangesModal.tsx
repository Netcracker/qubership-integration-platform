import { Button, Modal } from "antd";
import { useModalContext } from "../../ModalContextProvider";

type UnsavedChangesModalProps = {
  onYes: () => void;
  onNo?: () => void;
  onCancelQuestion?: () => void;
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

  const handleCancelQuestion = () => {
    closeContainingModal();
    props.onCancelQuestion?.();
  };

  return (
    <Modal
      title="Unsaved Changes"
      open={true}
      onCancel={handleCancelQuestion}
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
      Do you want to save changes?
    </Modal>
  );
};
