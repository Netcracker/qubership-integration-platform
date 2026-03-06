import { Form, Input, Modal, UploadFile } from "antd";
import React, { useState } from "react";
import { useModalContext } from "../../../ModalContextProvider";
import Dragger from "antd/es/upload/Dragger";
import { OverridableIcon } from "../../../icons/IconProvider";
import { api } from "../../../api/api";
import { useNotificationService } from "../../../hooks/useNotificationService";
import { DetailedDesignTemplate } from "../../../api/apiTypes";
import { removeExtension } from "../../../misc/file-utils";

type CreateDesignTemplateModalProps = {
  onTemplateCreated: (template: DetailedDesignTemplate) => void;
};

export const CreateDesignTemplateModal: React.FC<
  CreateDesignTemplateModalProps
> = ({ onTemplateCreated }) => {
  const notificationService = useNotificationService();
  const { closeContainingModal } = useModalContext();
  const [fileList, setFileList] = useState<UploadFile[]>([]);
  const [templateName, setTemplateName] = useState<string>("");

  const handleCreate = async () => {
    try {
      const template = await api.createOrUpdateDetailedDesignTemplate(
        templateName,
        await fileList[0].originFileObj!.text(),
      );
      closeContainingModal();
      onTemplateCreated(template);
    } catch (error) {
      notificationService.requestFailed(
        "Failed to create or update design template",
        error,
      );
    }
  };

  return (
    <Modal
      title="Import Template"
      centered
      open={true}
      onCancel={closeContainingModal}
      width={"60vw"}
      okText="Create"
      onOk={() => void handleCreate()}
      okButtonProps={{
        disabled: fileList.length === 0 || templateName.trim().length === 0,
      }}
    >
      <Form.Item
        label="Name"
        rules={[{ required: true, message: "Name is required" }]}
      >
        <Input
          value={templateName}
          onChange={(e) => setTemplateName(e.target.value)}
        />
      </Form.Item>

      <Dragger
        key={1}
        rootClassName="flex-dragger"
        multiple={false}
        maxCount={1}
        accept=".ftl"
        beforeUpload={() => false}
        fileList={fileList}
        onChange={(info) => {
          const { status, name } = info.file;
          const nameWithoutExtension = removeExtension(name);
          if (status === "removed") {
            if (nameWithoutExtension === templateName) {
              setTemplateName("");
            }
          } else if (status === undefined && templateName.trim().length === 0) {
            setTemplateName(nameWithoutExtension);
          }

          setFileList(info.fileList);
        }}
      >
        <p className="ant-upload-drag-icon">
          <OverridableIcon name="inbox" />
        </p>
        <p className="ant-upload-text">Drop .ftl file here to attach</p>
      </Dragger>
    </Modal>
  );
};
