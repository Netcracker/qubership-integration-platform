import { Button, Modal, Space } from "antd";
import { useModalContext } from "../../ModalContextProvider";
import { OverridableIcon } from "../../icons/IconProvider.tsx";

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
      title={
        <Space>
          <OverridableIcon name={"exclamationCircle"} />
          <span>Unsaved Changes</span>
        </Space>
      }
      open={true}
      onCancel={handleCancelQuestion}
      onOk={handleYes}
      footer={[
        <Button
          key="leave"
          type="primary"
          htmlType={"submit"}
          onClick={handleNo}
        >
          Yes
        </Button>,
        <Button key="keep" danger onClick={handleCancelQuestion}>
          No
        </Button>,
      ]}
    >
      You have made changes, that haven&#39;t been saved. Are you sure you want
      to leave the window and discard the changes?
    </Modal>
  );
};
