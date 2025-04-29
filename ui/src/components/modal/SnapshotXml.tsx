import { Modal, notification } from "antd";
import { useModalContext } from "../../ModalContextProvider.tsx";
import React, { useEffect, useState } from "react";
import TextArea from "antd/lib/input/TextArea";
import { Snapshot } from "../../api/apiTypes.ts";
import { api } from "../../api/api.ts";

type SnapshotXmlViewProps = {
  snapshotId: string;
};

export const SnapshotXmlView: React.FC<SnapshotXmlViewProps> = ({
  snapshotId,
}) => {
  const { closeContainingModal } = useModalContext();
  const [isLoading, setIsLoading] = useState(false);
  const [snapshot, setSnapshot] = useState<Snapshot>();

  useEffect(() => {
    getSnapshot(snapshotId);
  }, [snapshotId]);

  const getSnapshot = async (snapshotId: string) => {
    setIsLoading(true);
    try {
      setSnapshot(await api.getSnapshot(snapshotId));
    } catch (err) {
      notification.error({
        message: "Request failed",
        description: "Failed to get snapshot",
      });
    } finally {
      setIsLoading(false);
    }
  }

  return (
    <Modal
      title="XML Definition"
      centered
      open={true}
      onCancel={async () => closeContainingModal()}
      footer={null}
      width={"90%"}
      loading={isLoading}
    >
      <TextArea
        style={{ height: "80vh", resize: "none" }}
        defaultValue={snapshot?.xmlDefinition}
      ></TextArea>
    </Modal>
  );
};
