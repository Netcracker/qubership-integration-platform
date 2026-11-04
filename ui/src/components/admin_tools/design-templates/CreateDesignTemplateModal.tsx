import { Button, Form, Input, Modal, UploadFile } from "antd";
import Dragger from "antd/es/upload/Dragger";
import React from "react";
import { useModalContext } from "../../../ModalContextProvider";
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
  fileList: UploadFile[];
};

export const CreateDesignTemplateModal: React.FC<
  CreateDesignTemplateModalProps
> = ({ onTemplateCreated }) => {
  const notificationService = useNotificationService();
  const { closeContainingModal } = useModalContext();
  const [form] = Form.useForm<DesignTemplateFormValues>();

  const handleSave = async (values: DesignTemplateFormValues) => {
    try {
      const template = await api.createOrUpdateDetailedDesignTemplate(
        values.name.trim(),
        await values.fileList[0].originFileObj!.text(),
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
      title="Create Template"
      centered
      open={true}
      onCancel={closeContainingModal}
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
          <Input placeholder="Enter template name" />
        </Form.Item>

        <Form.Item
          name="fileList"
          valuePropName="fileList"
          getValueFromEvent={(info: { fileList: UploadFile[] }) =>
            info.fileList
          }
          rules={[
            { required: true, type: "array", message: "Attach a .ftl file" },
          ]}
        >
          <Dragger
            rootClassName="flex-dragger"
            multiple={false}
            maxCount={1}
            accept=".ftl"
            beforeUpload={() => false}
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
            }}
          >
            <p className="ant-upload-drag-icon">
              <OverridableIcon name="inbox" />
            </p>
            <p className="ant-upload-text">Drag .ftl file here to attach</p>
          </Dragger>
        </Form.Item>

        <div
          style={{
            display: "flex",
            justifyContent: "flex-end",
            gap: 8,
          }}
        >
          <Button size="middle" onClick={closeContainingModal}>
            Cancel
          </Button>
          <Button type="primary" size="middle" htmlType="submit">
            Create
          </Button>
        </div>
      </Form>
    </Modal>
  );
};
