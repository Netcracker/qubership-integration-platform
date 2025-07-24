import React, { useState } from "react";
import { Modal, Upload, Table, Button, message, Typography, Tag } from "antd";
import { InboxOutlined } from "@ant-design/icons";
import type { RcFile } from "antd/es/upload";
import { useModalContext } from "../../ModalContextProvider";
import { api } from "../../api/api";
import { ImportSystemResult, SystemImportStatus } from "../../api/apiTypes";
import { getErrorMessage } from '../../misc/error-utils';
import { useNotificationService } from "../../hooks/useNotificationService";

interface Props {
  onSuccess?: () => void;
}

const statusColor = {
  CREATED: "green",
  UPDATED: "blue",
  ERROR: "red",
  NO_ACTION: "default",
  IGNORED: "default",
};

const ImportServicesModal: React.FC<Props> = ({ onSuccess }) => {
  const { closeContainingModal } = useModalContext();
  const notify = useNotificationService();
  const [file, setFile] = useState<RcFile | null>(null);
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<ImportSystemResult[] | null>(null);

  const handleCancel = () => {
    closeContainingModal();
  };

  const handleImport = async () => {
    if (!file) {
      message.warning("Choose a file first");
      return;
    }
    setLoading(true);
    try {
      console.log("Import services from file");
      const res = await api.importSystems(file);
      console.log(res);
      setResult(res);
      onSuccess?.();
    } catch (e: unknown) {
      notify.requestFailed(getErrorMessage(e, 'Import failed'), e);
    } finally {
      setLoading(false);
    }
  };

  return (
    <Modal
      title="Import Services from file (zip)"
      open={true}
      onCancel={handleCancel}
      footer={null}
      width={700}
      maskClosable={true}
      destroyOnHidden
      afterClose={() => setResult(null)}
    >
      <div tabIndex={-1}>
        {!result && (
          <>
            <Upload.Dragger
              name="file"
              accept=".zip"
              beforeUpload={(file) => {
                setFile(file);
                return false;
              }}
              showUploadList={file ? { showRemoveIcon: true } : false}
              onRemove={() => setFile(null)}
            >
              <p className="ant-upload-drag-icon">
                <InboxOutlined />
              </p>
              <p className="ant-upload-text">Drag a ZIP file or click to choose</p>
            </Upload.Dragger>
            <Button
              type="primary"
              onClick={() => void handleImport}
              loading={loading}
              disabled={!file}
              style={{ marginTop: 16 }}
              block
            >
              Import
            </Button>
          </>
        )}
        {result && (
          <>
            <Typography.Title level={5} style={{ marginTop: 16 }}>
              Import result:
            </Typography.Title>
            <Table
              rowKey="id"
              columns={[
                {
                  title: "Name",
                  dataIndex: "name",
                  key: "name",
                  render: (text: string) => (
                    <Typography.Text ellipsis style={{ maxWidth: 180 }} strong>
                      {text}
                    </Typography.Text>
                  ),
                },
                {
                  title: "Status",
                  dataIndex: "status",
                  key: "status",
                  render: (status: SystemImportStatus) => (
                    <Tag color={statusColor[status] || "default"} style={{ fontSize: 16 }}>
                      {status.charAt(0) + status.slice(1).toLowerCase()}
                    </Tag>
                  ),
                },
                {
                  title: "Message",
                  dataIndex: "message",
                  key: "message",
                  render: (msg: string) => msg || "—",
                },
              ]}
              dataSource={result}
              pagination={false}
              style={{ marginTop: 8 }}
              size="middle"
            />
            <Button
              style={{ marginTop: 24 }}
              onClick={closeContainingModal}
              block
            >
              Close
            </Button>
          </>
        )}
      </div>
    </Modal>
  );
};

export default ImportServicesModal;
