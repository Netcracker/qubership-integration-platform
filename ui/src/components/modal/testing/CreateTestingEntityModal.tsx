import React, { useEffect, useState } from "react";
import { Button, Form, Input, Modal, Select } from "antd";
import { Element } from "../../../api/apiTypes.ts";
import { useChainElements } from "../../../hooks/testing/useChainElements.ts";
import { useNotificationService } from "../../../hooks/useNotificationService.tsx";
import { useModalContext } from "../../../ModalContextProvider.tsx";

export type CreateTestingEntityFormData = {
  name: string;
  elementId?: string;
  description?: string;
};

export type CreateTestingEntityModalProps<T> = {
  chainId: string;
  onCreated: (entity: T) => void;
  /** Each entity keeps its own form id: it is what the footer button submits. */
  formId: string;
  title: string;
  nameTestId: string;
  /** Every noun is spelled out rather than derived, articles and case included. */
  nameRequiredMessage: string;
  createFailedMessage: string;
  elementLabel: string;
  elementPlaceholder: string;
  /** Must be defined outside the component: `useChainElements` keys on it. */
  elementPredicate: (element: Element) => boolean;
  create: (
    values: CreateTestingEntityFormData,
    elements: Element[],
  ) => Promise<T>;
};

/** The scaffolding both testing create modals share; each brings its own defaults. */
export function CreateTestingEntityModal<T>({
  chainId,
  onCreated,
  formId,
  title,
  nameTestId,
  nameRequiredMessage,
  createFailedMessage,
  elementLabel,
  elementPlaceholder,
  elementPredicate,
  create,
}: CreateTestingEntityModalProps<T>): React.ReactElement {
  const [form] = Form.useForm<CreateTestingEntityFormData>();
  const { closeContainingModal } = useModalContext();
  const notificationService = useNotificationService();
  const [saving, setSaving] = useState(false);

  const { elements, isLoading, options } = useChainElements(
    chainId,
    elementPredicate,
  );

  // The first element is preselected, which is what the entity is created
  // against unless the user picks another.
  useEffect(() => {
    if (elements.length > 0) {
      form.setFieldValue("elementId", elements[0].id);
    }
  }, [elements, form]);

  const submit = async (values: CreateTestingEntityFormData) => {
    setSaving(true);
    try {
      const created = await create(values, elements);
      closeContainingModal();
      onCreated(created);
    } catch (error) {
      notificationService.requestFailed(createFailedMessage, error);
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal
      title={title}
      open={true}
      onCancel={closeContainingModal}
      footer={[
        <Button key="cancel" disabled={saving} onClick={closeContainingModal}>
          Cancel
        </Button>,
        <Button
          key="submit"
          type="primary"
          form={formId}
          htmlType="submit"
          loading={saving}
        >
          Save
        </Button>,
      ]}
    >
      <Form<CreateTestingEntityFormData>
        id={formId}
        form={form}
        disabled={saving}
        layout="vertical"
        onFinish={(values) => void submit(values)}
      >
        <Form.Item
          label="Name"
          name="name"
          rules={[{ required: true, message: nameRequiredMessage }]}
        >
          <Input data-testid={nameTestId} autoFocus />
        </Form.Item>
        <Form.Item label={elementLabel} name="elementId">
          <Select
            allowClear
            loading={isLoading}
            options={options}
            placeholder={elementPlaceholder}
          />
        </Form.Item>
        <Form.Item label="Description" name="description">
          <Input.TextArea className="fixed-textarea" />
        </Form.Item>
      </Form>
    </Modal>
  );
}
