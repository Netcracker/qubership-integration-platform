import React, { useState, useEffect } from "react";
import { Modal, Input, Button, Form, Select } from "antd";
import { IntegrationSystemType } from "../../api/apiTypes";
import { getErrorMessage } from "../../misc/error-utils";
import { capitalize } from "../../misc/format-utils";

interface CreateServiceModalProps {
  open: boolean;
  onCancel: () => void;
  onCreate: (name: string, description: string | undefined, type: IntegrationSystemType) => Promise<unknown>;
  loading: boolean;
  error?: string | null;
  defaultType?: IntegrationSystemType;
}

type CreateServiceFormValues = {
  name: string;
  description?: string;
  type: IntegrationSystemType;
};

const getServiceTypeLabel = (type: IntegrationSystemType): string => {
  const result: string | undefined = IntegrationSystemType[type];
  return result ? capitalize(result) : "Service";
};

export const CreateServiceModal: React.FC<CreateServiceModalProps> = ({
  open,
  onCancel,
  onCreate,
  loading,
  error,
  defaultType = IntegrationSystemType.EXTERNAL
}) => {
  const [form] = Form.useForm<CreateServiceFormValues>();
  const [submitError, setSubmitError] = useState<string | null>(null);

  useEffect(() => {
    if (open) {
      const serviceTypeLabel = getServiceTypeLabel(defaultType);
      form.setFieldsValue({
        type: defaultType,
        name: `New ${serviceTypeLabel} service`
      });
    }
  }, [open, defaultType, form]);

  const handleTypeChange = (newType: IntegrationSystemType) => {
    const currentName = form.getFieldValue('name') as string;
    const serviceTypeLabel = getServiceTypeLabel(newType);
    const expectedName = `New ${serviceTypeLabel} service`;

    if (currentName && currentName.startsWith('New ') && currentName.endsWith(' service')) {
      form.setFieldValue('name', expectedName);
    }
  };

  const handleOk = async (values: CreateServiceFormValues) => {
    try {
      setSubmitError(null);
      await onCreate(values.name, values.description, values.type);
      form.resetFields();
    } catch (e) {
      setSubmitError(getErrorMessage(e));
    }
  };

  return (
    <Modal
      open={open}
      title="Create service"
      onCancel={() => { form.resetFields(); onCancel(); }}
      footer={null}
      destroyOnHidden={true}
    >
      <Form<CreateServiceFormValues>
        form={form}
        layout="vertical"
        onFinish={(values) => { void handleOk(values); }}
        initialValues={{ type: defaultType }}
      >
        <Form.Item
          label="Name"
          name="name"
          rules={[{ required: true, message: "Enter service name" }]}
        >
          <Input disabled={loading} autoFocus maxLength={128} />
        </Form.Item>
        <Form.Item label="Description" name="description">
          <Input.TextArea disabled={loading} maxLength={512} />
        </Form.Item>
        <Form.Item label="Service type" name="type" rules={[{ required: true, message: "Select service type" }]}>
          <Select disabled={loading} onChange={handleTypeChange}>
            <Select.Option value={IntegrationSystemType.EXTERNAL}>External</Select.Option>
            <Select.Option value={IntegrationSystemType.INTERNAL}>Internal</Select.Option>
            <Select.Option value={IntegrationSystemType.IMPLEMENTED}>Implemented</Select.Option>
            <Select.Option value={IntegrationSystemType.CONTEXT}>Content</Select.Option>
          </Select>
        </Form.Item>
        {error && <div style={{ color: 'var(--vscode-errorForeground, #d73a49)', marginBottom: 8 }}>{error}</div>}
        {submitError && <div style={{ color: 'var(--vscode-errorForeground, #d73a49)', marginBottom: 8 }}>{submitError}</div>}
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
          <Button onClick={() => { form.resetFields(); onCancel(); }} disabled={loading}>
            Cancel
          </Button>
          <Button type="primary" htmlType="submit" loading={loading} disabled={loading}>
            Create
          </Button>
        </div>
      </Form>
    </Modal>
  );
};
