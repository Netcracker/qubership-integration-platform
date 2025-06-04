import { Modal, Upload, Table, Button, message } from "antd";
import { InboxOutlined } from "@ant-design/icons";
import type { RcFile } from "antd/es/upload";
import { useState } from "react";

import { useModalContext } from "../../../ModalContextProvider.tsx";
import { variablesApi } from "../../../api/admin-tools/variables/variablesApi.ts";

interface Props {
  onSuccess?: () => void;
}

const ImportVariablesModal = ({ onSuccess }: Props) => {
  const { closeContainingModal } = useModalContext();

  const [file, setFile] = useState<RcFile | null>(null);
  const [loading, setLoading] = useState(false);
  const [previewData, setPreviewData] = useState<any[]>([]);
  const [selectedNames, setSelectedNames] = useState<React.Key[]>([]);

  const handleCancel = () => {
    closeContainingModal();
  };

  const handlePreview = async () => {
    if (!file) {
      message.warning("Choose a file first");
      return;
    }

    const formData = new FormData();
    formData.append("file", file);

    try {
      setLoading(true);
      const data = await variablesApi.importVariables(formData, true);
      setPreviewData(data);
    } catch {
      message.error("Failed to preview");
    } finally {
      setLoading(false);
    }
  };

  const handleImport = async () => {
    if (!file) {
      message.warning("Choose a file first");
      return;
    }

    const formData = new FormData();
    formData.append("file", file);

    selectedNames.forEach((name) => {
      formData.append("variablesNames", String(name));
    });

    try {
      setLoading(true);
      await variablesApi.importVariables(formData, false);
      message.success("Imported successfully");
      closeContainingModal();
      onSuccess?.();
    } catch {
      message.error("Import failed");
    } finally {
      setLoading(false);
    }
  };

  return (
    <Modal
      title="Import variables"
      open={true}
      onCancel={handleCancel}
      footer={[
        <Button key="preview" onClick={handlePreview} loading={loading}>
          Preview
        </Button>,
        <Button
          key="import"
          type="primary"
          onClick={handleImport}
          loading={loading}
        >
          Import
        </Button>,
      ]}
      width={700}
    >
      <Upload.Dragger
        name="file"
        accept=".yaml,.yml"
        beforeUpload={(file) => {
          setFile(file);
          return false;
        }}
        showUploadList={file ? { showRemoveIcon: true } : false}
      >
        <p className="ant-upload-drag-icon">
          <InboxOutlined />
        </p>
        <p className="ant-upload-text">Drag a YAML file or click to choose</p>
      </Upload.Dragger>

      {previewData.length > 0 && (
        <Table
          rowKey="name"
          style={{ marginTop: 16 }}
          columns={[
            { title: "Name", dataIndex: "name", key: "name" },
            { title: "Value", dataIndex: "value", key: "value" },
          ]}
          dataSource={previewData}
          rowSelection={{
            selectedRowKeys: selectedNames,
            onChange: setSelectedNames,
          }}
          pagination={false}
          size="small"
        />
      )}
    </Modal>
  );
};

export default ImportVariablesModal;
