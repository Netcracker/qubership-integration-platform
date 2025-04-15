import { Button, Form, Input, Modal } from "antd";
import React, { useState } from "react";
import { api } from "../../api/api.ts";
import { useModalContext } from "../../ModalContextProvider.tsx";

type Props = {
  loadChains: () => void;
};

export const ChainCreate: React.FC<Props> = ({ loadChains }) => {
  const [confirmLoading, setConfirmLoading] = useState(false);
  const [chainName, setChainName] = useState<string>("");
  const { closeContainingModal } = useModalContext();

  const handleOk = async () => {
    setConfirmLoading(true);
    await api.createChain({ name: chainName, labels: [] }); //TODO save real chain object
    closeContainingModal();
    loadChains();
  };

  const handleCancel = () => {
    closeContainingModal();
  };

  return (
    <Modal
      title="Title"
      open={true}
      onCancel={handleCancel}
      footer={[
        <Button key="cancel" onClick={handleCancel}>
          Cancel
        </Button>,
        <Button
          key="submit"
          type="primary"
          form={chainName}
          htmlType={"submit"}
          loading={confirmLoading}
        >
          Submit
        </Button>,
      ]}
    >
      <Form id={chainName} onFinish={handleOk}>
        <Input
          placeholder="Chain Name"
          value={chainName}
          onChange={(event) => setChainName(event.target.value)}
        />
      </Form>
    </Modal>
  );
};
