import React, { useState } from "react";
import { Modal, Upload, Button, Typography, Spin, message, Input, Form } from "antd";
import { InboxOutlined } from "@ant-design/icons";
import type { RcFile } from "antd/es/upload";
import { useModalContext } from "../../ModalContextProvider";
import { api } from "../../api/api";
import { getErrorMessage } from '../../misc/error-utils';
import { useNotificationService } from "../../hooks/useNotificationService";

interface Props {
  systemId?: string; 
  specificationGroupId?: string; 
  groupMode?: boolean; 
  onSuccess?: () => void;
}

const ImportSpecificationsModal: React.FC<Props> = ({ systemId, specificationGroupId, groupMode, onSuccess }) => {
  const { closeContainingModal } = useModalContext();
  const notify = useNotificationService();
  const [files, setFiles] = useState<RcFile[]>([]);
  const [loading, setLoading] = useState(false);
  const [polling, setPolling] = useState(false);
  const [progressText, setProgressText] = useState<string | null>(null);
  const [name, setName] = useState<string>("");
  const [nameTouched, setNameTouched] = useState(false);

  const isGroupMode = groupMode || (!!systemId && !specificationGroupId);

  const handleCancel = () => {
    closeContainingModal();
  };

  const handleImport = async () => {
    if (!files.length) {
      message.warning("Choose at least one file");
      return;
    }
    if (isGroupMode) {
      if (!name.trim()) {
        message.warning("Name is required");
        return;
      }
    }
    setLoading(true);
    setProgressText("Uploading...");
    try {
      let res;
      if (isGroupMode) {
        res = await api.importSpecificationGroup(
          systemId!,
          name.trim(),
          files,
        );
      } else {
        res = await api.importSpecification(
          specificationGroupId!,
          files,
        );
      }
      setProgressText("Processing...");
      setPolling(true);
      await pollStatus(res.id);
    } catch (e: unknown) {
      handleError(e, 'Import failed');
    }
  };

  const pollStatus = async (importId: string) => {
    try {
      const result = await api.getImportSpecificationResult(importId);
      if (result.done) {
        setProgressText(result.warningMessage ? result.warningMessage : "Import complete");
        setTimeout(() => {
          setLoading(false);
          setPolling(false);
          setProgressText(null);
          closeContainingModal();
          onSuccess?.();
        }, 1200);
      } else {
        setTimeout(() => void pollStatus(importId), 1200);
      }
    } catch (e: unknown) {
      handleError(e, 'Failed to get import status');
    }
  };

  const handleFilesChange = (fileList: RcFile[]) => {
    setFiles(fileList);
    if (!nameTouched && fileList.length > 0 && isGroupMode) {
      const base = fileList[0].name.replace(/\.[^.]+$/, "");
      setName(base);
    }
  };

  const handleError = (e: unknown, fallbackMessage: string) => {
    setLoading(false);
    setPolling(false);
    setProgressText(null);
    notify.requestFailed(getErrorMessage(e, fallbackMessage), e);
  };

  return (
    <Modal
      title={
        isGroupMode ? "Import Specification Group" : "Import Specification"
      }
      open={true}
      onCancel={handleCancel}
      footer={null}
      width={500}
      maskClosable={true}
      destroyOnHidden
    >
      <div>
        {isGroupMode && (
          <Form.Item
            label="Name"
            required
            validateStatus={!name.trim() && nameTouched ? "error" : ""}
            help={!name.trim() && nameTouched ? "Name is required" : undefined}
            style={{ marginBottom: 12 }}
          >
            <Input
              value={name}
              onChange={(e) => {
                setName(e.target.value);
                setNameTouched(true);
              }}
              onBlur={() => setNameTouched(true)}
              placeholder="Enter group name"
              autoFocus
            />
          </Form.Item>
        )}
        <Upload.Dragger
          name="files"
          multiple
          accept=".yaml,.yml,.json,.xml,.zip"
          beforeUpload={(_file, fileList) => {
            handleFilesChange(fileList);
            return false;
          }}
          showUploadList={files.length > 0 ? { showRemoveIcon: true } : false}
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
            Drag one or more specification files or click to choose
          </p>
        </Upload.Dragger>
        <Button
          type="primary"
          onClick={() => void handleImport}
          loading={loading || polling}
          disabled={
            !files.length || (isGroupMode && !name.trim()) || loading || polling
          }
          style={{ marginTop: 16 }}
          block
        >
          Import
        </Button>
        {(loading || polling) && (
          <div style={{ marginTop: 24, textAlign: "center" }}>
            <Spin />
            <Typography.Text style={{ display: "block", marginTop: 8 }}>
              {progressText}
            </Typography.Text>
          </div>
        )}
      </div>
    </Modal>
  );
};

export default ImportSpecificationsModal;
