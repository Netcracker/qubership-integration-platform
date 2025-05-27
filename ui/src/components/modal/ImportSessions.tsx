import { Button, Modal, UploadFile } from "antd";
import { useModalContext } from "../../ModalContextProvider.tsx";
import React, { useState } from "react";
import { ExclamationCircleOutlined, InboxOutlined } from "@ant-design/icons";
import Dragger from "antd/es/upload/Dragger";
import styles from "./ImportSessions.module.css";
import { api } from "../../api/api.ts";
import { Session } from "../../api/apiTypes.ts";
import { useNotificationService } from "../../hooks/useNotificationService.tsx";

type ImportSessionsProps = {
  onSuccess?: (sessions: Session[]) => void;
};

export const ImportSessions: React.FC<ImportSessionsProps> = ({ onSuccess }) => {
  const { closeContainingModal } = useModalContext();
  const [fileList, setFileList] = useState<UploadFile[]>([]);
  const [uploading, setUploading] = useState(false);
  const notificationService = useNotificationService();

  const importSessions = async () => {
    setUploading(true);
    try {
      const files = fileList.map(file => file.originFileObj).filter(file => !!file);
      const sessions = await api.importSessions(files);
      onSuccess?.(sessions);
    } catch (error) {
      notificationService.requestFailed("Failed to import sessions", error);
    } finally {
      setUploading(false);
    }
  }

  return (
    <Modal
      title="Import Sessions"
      centered
      open={true}
      onCancel={async () => closeContainingModal()}
      width={"60%"}
      height={"70%"}
      footer={[
        <Button
          key="clear"
          disabled={uploading}
          onClick={() => setFileList([])}
        >
          Clear
        </Button>,
        <Button
          key="submit"
          type="primary"
          disabled={!fileList || !fileList.length}
          loading={uploading}
          onClick={() => importSessions()}
        >
          Import
        </Button>,
      ]}
    >
      <Dragger
        multiple
        className={styles.uploadDialog}
        fileList={fileList}
        beforeUpload={() => false}
        onChange={(info) => setFileList(info.fileList)}
      >
        <p className="ant-upload-drag-icon">
          <InboxOutlined />
        </p>
        <p className="ant-upload-text">
          Click or drag file to this area to upload
        </p>
        <p className="ant-upload-hint">
          <ExclamationCircleOutlined style={{ marginRight: 8 }} />
          You are about to import outbound session details to the table. Please
          note, that imported sessions are supported via read-only mode and
          references to the chain elements, as well as navigation buttons, won't
          be available.
        </p>
      </Dragger>
    </Modal>
  );
};
