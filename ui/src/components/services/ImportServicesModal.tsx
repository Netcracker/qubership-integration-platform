import React, { useState } from "react";
import { Modal, Upload, Table, Button, message, Typography, Tag } from "antd";
import { InboxOutlined } from "@ant-design/icons";
import type { RcFile } from "antd/es/upload";
import { useModalContext } from "../../ModalContextProvider";
import { api } from "../../api/api";
import { ImportSystemResult, SystemImportStatus } from "../../api/apiTypes";
import { getErrorMessage } from '../../misc/error-utils';
import { useNotificationService } from "../../hooks/useNotificationService";
import { validateFiles } from "./utils";

interface Props {
  onSuccess?: () => void;
}

const SUPPORTED_EXTENSIONS = ['.zip'];

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
  const [files, setFiles] = useState<RcFile[]>([]);
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<ImportSystemResult[] | null>(null);

  const handleCancel = () => {
    closeContainingModal();
  };

  const handleImport = async () => {
    const validation = validateFiles(files, SUPPORTED_EXTENSIONS);
    if (!validation.valid) {
      message.warning(validation.message);
      return;
    }
    setLoading(true);
    try {
      const res = await api.importSystems(files[0]);
      setResult(res);
      onSuccess?.();
    } catch (e: unknown) {
      notify.requestFailed(getErrorMessage(e, 'Import failed'), e);
    } finally {
      setLoading(false);
    }
  };

  const handleFilesChange = (fileList: RcFile[]) => {
    if (fileList.length > 1) {
      message.warning("Only one Zip file is allowed");
      return;
    }

    const validation = validateFiles(fileList, SUPPORTED_EXTENSIONS);
    if (!validation.valid) {
      message.warning(validation.message);
      return;
    }
    setFiles(fileList);
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
            <div style={{ marginBottom: 16 }}>
              <Typography.Text type="secondary">
                Supported file type: Zip archive only
              </Typography.Text>
              <br />
              <Typography.Text type="secondary">
                Maximum file size: 25MB
              </Typography.Text>
            </div>

            <Upload.Dragger
              name="files"
              multiple={false}
              accept=".zip"
              beforeUpload={(_file, fileList) => {
                handleFilesChange(fileList);
                return false;
              }}
              showUploadList={files.length > 0 ? {
                showRemoveIcon: true,
                showPreviewIcon: false,
                showDownloadIcon: false
              } : false}
              onRemove={(file) => {
                setFiles((prev) => prev.filter((f) => f.uid !== file.uid));
                return true;
              }}
              fileList={files}
            >
              <p className="ant-upload-drag-icon">
                <InboxOutlined />
              </p>
              <p className="ant-upload-text">
                Drag a Zip file or click to choose
              </p>
              <p className="ant-upload-hint">
                Supports: Zip archives with service configurations only
              </p>
            </Upload.Dragger>

            <Button
              type="primary"
              onClick={() => void handleImport()}
              loading={loading}
              disabled={!files.length}
              style={{ marginTop: 16 }}
              block
            >
              Import Zip File
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
