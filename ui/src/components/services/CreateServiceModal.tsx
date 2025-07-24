import React, { useState } from "react";
import { Modal, Input, Button, Form, Select } from "antd";
import { IntegrationSystemType } from "../../api/apiTypes";
import { getErrorMessage } from "../../misc/error-utils";

interface CreateServiceModalProps {
  open: boolean;
  onCancel: () => void;
  onCreate: (name: string, description: string | undefined, type: IntegrationSystemType) => Promise<unknown>;
  loading: boolean;
  error?: string | null;
}

type CreateServiceFormValues = {
  name: string;
  description?: string;
  type: IntegrationSystemType;
};

export const CreateServiceModal: React.FC<CreateServiceModalProps> = ({ open, onCancel, onCreate, loading, error }) => {
  const [form] = Form.useForm<CreateServiceFormValues>();
  const [submitError, setSubmitError] = useState<string | null>(null);

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
        initialValues={{ type: IntegrationSystemType.EXTERNAL }}
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
          <Select disabled={loading}>
            <Select.Option value={IntegrationSystemType.EXTERNAL}>External</Select.Option>
            <Select.Option value={IntegrationSystemType.INTERNAL}>Internal</Select.Option>
            <Select.Option value={IntegrationSystemType.IMPLEMENTED}>Implemented</Select.Option>
          </Select>
        </Form.Item>
        {error && <div style={{ color: 'red', marginBottom: 8 }}>{error}</div>}
        {submitError && <div style={{ color: 'red', marginBottom: 8 }}>{submitError}</div>}
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
