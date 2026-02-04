import React, { useCallback, useEffect, useState } from "react";
import { useModalContext } from "../../ModalContextProvider.tsx";
import { Button, Form, Input, Modal, Select, SelectProps } from "antd";
import { DetailedDesignTemplate } from "../../api/apiTypes.ts";
import { api } from "../../api/api.ts";
import { useNotificationService } from "../../hooks/useNotificationService.tsx";
import { formatDate } from "../../misc/format-utils.ts";

type GenerateDdsFormData = {
  templateId: string;
  fileName: string;
};

export type GenerateDdsModalProps = {
  onSubmit: (templateId: string, fileName: string) => void | Promise<void>;
};

export const GenerateDdsModal: React.FC<GenerateDdsModalProps> = ({
  onSubmit,
}) => {
  const { closeContainingModal } = useModalContext();
  const [confirmLoading, setConfirmLoading] = useState(false);
  const [templates, setTemplates] = useState<DetailedDesignTemplate[]>([]);
  const [options, setOptions] = useState<
    SelectProps<DetailedDesignTemplate>["options"]
  >([]);
  const notificationService = useNotificationService();

  const loadTemplates = useCallback(async () => {
    setConfirmLoading(true);
    try {
      const fetchedTemplates = await api.getDetailedDesignTemplates(false);
      setTemplates(fetchedTemplates);
    } catch (error) {
      notificationService.requestFailed(
        "Failed to load detailed design templates",
        error,
      );
    } finally {
      setConfirmLoading(false);
    }
  }, [notificationService]);

  useEffect(() => {
    void loadTemplates();
  }, [loadTemplates]);

  useEffect(() => {
    setOptions(
      templates.map((template) => ({
        value: template.id,
        label: template.name,
      })),
    );
  }, [templates]);

  return (
    <Modal
      title="Generate Chain Documentation"
      open={true}
      onCancel={closeContainingModal}
      footer={[
        <Button
          key="cancel"
          disabled={confirmLoading}
          onClick={closeContainingModal}
        >
          Cancel
        </Button>,
        <Button
          key="submit"
          type="primary"
          form="generateDdsForm"
          htmlType={"submit"}
          loading={confirmLoading}
        >
          Generate
        </Button>,
      ]}
    >
      <Form<GenerateDdsFormData>
        id="generateDdsForm"
        disabled={confirmLoading}
        labelCol={{ flex: "150px" }}
        wrapperCol={{ flex: "auto" }}
        initialValues={{
          fileName: "chain-documentation-" + formatDate(new Date()),
        }}
        labelWrap
        onFinish={(values) => {
          setConfirmLoading(true);
          try {
            const result = onSubmit?.(values.templateId, values.fileName);
            if (result instanceof Promise) {
              result
                .then(() => {
                  closeContainingModal();
                  setConfirmLoading(false);
                })
                .catch(() => {
                  setConfirmLoading(false);
                });
            } else {
              closeContainingModal();
              setConfirmLoading(false);
            }
          } catch (error) {
            notificationService.errorWithDetails(
              "Failed to submit form",
              "An exception has been throws from the form submit callback",
              error,
            );
            setConfirmLoading(false);
          }
        }}
      >
        <Form.Item
          name="templateId"
          label="Template"
          rules={[{ required: true, message: "Template is required" }]}
        >
          <Select options={options} />
        </Form.Item>
        <Form.Item
          name="fileName"
          label="File name"
          rules={[{ required: true, message: "File name is required" }]}
        >
          <Input />
        </Form.Item>
      </Form>
    </Modal>
  );
};
