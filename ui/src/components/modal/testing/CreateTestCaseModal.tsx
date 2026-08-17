import React, { useEffect, useMemo, useState } from "react";
import { Button, Form, Input, Modal, Select } from "antd";
import { api } from "../../../api/api.ts";
import { Element, TestCase, TestCaseRequest } from "../../../api/apiTypes.ts";
import { useNotificationService } from "../../../hooks/useNotificationService.tsx";
import { useModalContext } from "../../../ModalContextProvider.tsx";
import {
  flattenElements,
  getHttpMethods,
  isHttpTrigger,
} from "../../testing/testingElements.ts";

const FORM_ID = "createTestCaseForm";

/** Creation defaults; a mock uses different ones, so neither set is shared. */
const DEFAULT_TIMEOUT = 120000;
const DEFAULT_ENABLED = false;

export type CreateTestCaseModalProps = {
  chainId: string;
  onCreated: (testCase: TestCase) => void;
};

type FormData = {
  name: string;
  elementId?: string;
  description?: string;
};

export const CreateTestCaseModal: React.FC<CreateTestCaseModalProps> = ({
  chainId,
  onCreated,
}) => {
  const [form] = Form.useForm<FormData>();
  const { closeContainingModal } = useModalContext();
  const notificationService = useNotificationService();
  const [triggers, setTriggers] = useState<Element[]>([]);
  const [triggersLoading, setTriggersLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setTriggersLoading(true);
    void (async () => {
      try {
        const elements = await api.getElements(chainId);
        if (cancelled) {
          return;
        }
        const httpTriggers = flattenElements(elements).filter(isHttpTrigger);
        setTriggers(httpTriggers);
        form.setFieldValue("elementId", httpTriggers[0]?.id);
      } catch (error) {
        if (!cancelled) {
          notificationService.requestFailed(
            "Failed to load chain elements",
            error,
          );
        }
      } finally {
        if (!cancelled) {
          setTriggersLoading(false);
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [chainId, form, notificationService]);

  const triggerOptions = useMemo(
    () =>
      triggers.map((trigger) => ({ value: trigger.id, label: trigger.name })),
    [triggers],
  );

  const submit = async (values: FormData) => {
    setSaving(true);
    const trigger = triggers.find((element) => element.id === values.elementId);
    const request: TestCaseRequest = {
      name: values.name.trim(),
      description: values.description ?? "",
      enabled: DEFAULT_ENABLED,
      // The chain reference carries the case even when no trigger is picked yet:
      // it is what scopes the case to the chain in every list.
      triggerReference: { chainId, elementId: values.elementId ?? "" },
      requestSettings: {
        queryParameters: [],
        pathParameters: [],
        message: { body: null, headers: [] },
        method: getHttpMethods(trigger)[0],
        timeout: DEFAULT_TIMEOUT,
      },
      responseValidationRules: [],
    };
    try {
      const created = await api.createTestCase(request);
      closeContainingModal();
      onCreated(created);
    } catch (error) {
      notificationService.requestFailed("Failed to create a test case", error);
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal
      title="Create Test Case"
      open={true}
      onCancel={closeContainingModal}
      footer={[
        <Button key="cancel" disabled={saving} onClick={closeContainingModal}>
          Cancel
        </Button>,
        <Button
          key="submit"
          type="primary"
          form={FORM_ID}
          htmlType="submit"
          loading={saving}
        >
          Save
        </Button>,
      ]}
    >
      <Form<FormData>
        id={FORM_ID}
        form={form}
        disabled={saving}
        layout="vertical"
        onFinish={(values) => void submit(values)}
      >
        <Form.Item
          label="Name"
          name="name"
          rules={[
            { required: true, message: "Enter a name for the test case." },
          ]}
        >
          <Input data-testid="test-case-name" autoFocus />
        </Form.Item>
        <Form.Item label="Trigger" name="elementId">
          <Select
            allowClear
            loading={triggersLoading}
            options={triggerOptions}
            placeholder="Select an HTTP trigger"
          />
        </Form.Item>
        <Form.Item label="Description" name="description">
          <Input.TextArea className="fixed-textarea" />
        </Form.Item>
      </Form>
    </Modal>
  );
};
