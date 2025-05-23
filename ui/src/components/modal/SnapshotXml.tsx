import { Modal } from "antd";
import { useModalContext } from "../../ModalContextProvider.tsx";
import React, { useEffect, useState } from "react";
import { Snapshot } from "../../api/apiTypes.ts";
import { api } from "../../api/api.ts";
import { Editor } from "@monaco-editor/react";
import { useNotificationService } from "../../hooks/useNotificationService.tsx";

type SnapshotXmlViewProps = {
  snapshotId: string;
};

export const SnapshotXmlView: React.FC<SnapshotXmlViewProps> = ({
  snapshotId,
}) => {
  const { closeContainingModal } = useModalContext();
  const [isLoading, setIsLoading] = useState(false);
  const [snapshot, setSnapshot] = useState<Snapshot>();
  const notificationService = useNotificationService();

  useEffect(() => {
    getSnapshot(snapshotId);
  }, [snapshotId]);

  const getSnapshot = async (snapshotId: string) => {
    setIsLoading(true);
    try {
      setSnapshot(await api.getSnapshot(snapshotId));
    } catch (err) {
      notificationService.requestFailed("Failed to get snapshot", err);
    } finally {
      setIsLoading(false);
    }
  };

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
      <Editor
        className="qip-editor"
        height={"80vh"}
        defaultLanguage="xml"
        defaultValue={snapshot?.xmlDefinition}
        options={{ readOnly: true }}
      />
    </Modal>
  );
};
