import { Button, Form, Input, Modal, UploadFile, message } from "antd";
import React, { useState } from "react";
import { useModalContext } from "../../../ModalContextProvider";
import Dragger from "antd/es/upload/Dragger";
import { OverridableIcon } from "../../../icons/IconProvider";
import { api } from "../../../api/api";
import { useNotificationService } from "../../../hooks/useNotificationService";
import { DetailedDesignTemplate } from "../../../api/apiTypes";
import { removeExtension } from "../../../misc/file-utils";
import { getErrorMessage } from "../../../misc/error-utils";

type CreateDesignTemplateModalProps = {
  onTemplateCreated: (template: DetailedDesignTemplate) => void;
};

type DesignTemplateFormValues = {
  name: string;
};

export const CreateDesignTemplateModal: React.FC<
  CreateDesignTemplateModalProps
> = ({ onTemplateCreated }) => {
  const notificationService = useNotificationService();
  const { closeContainingModal } = useModalContext();
  const [form] = Form.useForm<DesignTemplateFormValues>();
  const [fileList, setFileList] = useState<UploadFile[]>([]);

  const handleSave = async (values: DesignTemplateFormValues) => {
    const file = fileList[0]?.originFileObj;
    if (!file) {
      void message.warning("Attach a .ftl file");
      return;
    }
    try {
      const template = await api.createOrUpdateDetailedDesignTemplate(
        values.name.trim(),
        await file.text(),
      );
      closeContainingModal();
      onTemplateCreated(template);
    } catch (error) {
      notificationService.requestFailed(
        getErrorMessage(error, "Failed to create or update design template"),
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
      footer={null}
    >
      <Form<DesignTemplateFormValues>
        form={form}
        layout="vertical"
        id="designTemplateForm"
        onFinish={(values) => void handleSave(values)}
      >
        <Form.Item
          name="name"
          label="Name"
          rules={[{ required: true, message: "Name is required" }]}
        >
          <Input />
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
            const currentName = form.getFieldValue("name") as
              | string
              | undefined;
            if (status === "removed") {
              if (nameWithoutExtension === currentName?.trim()) {
                form.setFieldValue("name", "");
              }
            } else if (status === undefined && !currentName?.trim()) {
              form.setFieldValue("name", nameWithoutExtension);
            }

            setFileList(info.fileList);
          }}
        >
          <p className="ant-upload-drag-icon">
            <OverridableIcon name="inbox" />
          </p>
          <p className="ant-upload-text">Drop .ftl file here to attach</p>
        </Dragger>

        <div
          style={{
            display: "flex",
            justifyContent: "flex-end",
            gap: 8,
            marginTop: 16,
          }}
        >
          <Button size="middle" onClick={closeContainingModal}>
            Cancel
          </Button>
          <Button
            type="primary"
            size="middle"
            htmlType="submit"
            disabled={fileList.length === 0}
          >
            Save
          </Button>
        </div>
      </Form>
    </Modal>
  );
};
