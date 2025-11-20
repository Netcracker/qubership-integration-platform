import { Modal, Upload, Table, Button, message, Flex } from "antd";
import type { RcFile } from "antd/es/upload";
import React, { useState } from "react";

import { useModalContext } from "../../../ModalContextProvider.tsx";
import { variablesApi } from "../../../api/admin-tools/variables/variablesApi.ts";
import { useNotificationService } from "../../../hooks/useNotificationService.tsx";
import { VariableImportPreview } from "../../../api/apiTypes.ts";
import { OverridableIcon } from "../../../icons/IconProvider.tsx";

interface Props {
  onSuccess?: () => void;
}

const ImportVariablesModal = ({ onSuccess }: Props) => {
  const { closeContainingModal } = useModalContext();

  const [file, setFile] = useState<RcFile | null>(null);
  const [loadingPreview, setLoadingPreview] = useState<boolean>(false);
  const [importing, setImporting] = useState<boolean>(false);
  const [previewData, setPreviewData] = useState<VariableImportPreview[]>([]);
  const [selectedNames, setSelectedNames] = useState<React.Key[]>([]);
  const notificationService = useNotificationService();

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
      setLoadingPreview(true);
      const response = await variablesApi.importVariablesPreview(formData);
      setPreviewData(response.data ?? []);
    } catch (error) {
      notificationService.requestFailed("Failed to preview", error);
    } finally {
      setLoadingPreview(false);
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
      setImporting(true);
      const response = await variablesApi.importVariables(formData);
      if (response.success) {
        message.success("Imported successfully");
        closeContainingModal();
        onSuccess?.();
      } else {
        notificationService.errorWithDetails(
          "Import failed",
          response.error!.responseBody.errorMessage,
          response.error,
        );
      }
    } finally {
      setImporting(false);
    }
  };

  return (
    <Modal
      title="Import variables"
      open={true}
      onCancel={handleCancel}
      footer={[
        <Button
          key="preview"
          onClick={() => void handlePreview()}
          loading={loadingPreview}
          disabled={importing}
        >
          Preview
        </Button>,
        <Button
          key="import"
          type="primary"
          onClick={() => void handleImport()}
          loading={importing}
          disabled={loadingPreview}
        >
          Import
        </Button>,
      ]}
      width={700}
    >
      <Flex vertical gap={16} style={{ height: "70vh" }}>
        <Upload.Dragger
          rootClassName="flex-dragger"
          name="file"
          accept=".yaml,.yml"
          beforeUpload={(file) => {
            setFile(file);
            return false;
          }}
          showUploadList={file ? { showRemoveIcon: true } : false}
        >
          <p className="ant-upload-drag-icon">
            <OverridableIcon name="inbox" />
          </p>
          <p className="ant-upload-text">Drag a YAML file or click to choose</p>
        </Upload.Dragger>

        {previewData.length > 0 && (
          <Table
            rowKey="name"
            className="flex-table"
            style={{ height: "100%" }}
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
            sticky
            scroll={{ y: "" }}
          />
        )}
      </Flex>
    </Modal>
  );
};

export default ImportVariablesModal;
