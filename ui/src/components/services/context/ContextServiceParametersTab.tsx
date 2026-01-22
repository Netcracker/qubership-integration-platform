import React, { useEffect, useState, useCallback } from "react";
import { Form, Input, Button, Descriptions, Spin } from "antd";
import { ContextSystem } from "../../../api/apiTypes";
import { api } from "../../../api/api";
import { useAsyncRequest } from "../useAsyncRequest";
import { isVsCode } from "../../../api/rest/vscodeExtensionApi.ts";
import { contextServiceCache } from "../utils.tsx";
import { ServiceParametersTabProps } from "../ServiceParametersTab.tsx";

interface FormValues {
  name: string;
  description?: string;
}

export const ContextServiceParametersTab: React.FC<
  ServiceParametersTabProps
> = ({ systemId, activeTab, formatTimestamp, sidePadding, styles }) => {
  const [form] = Form.useForm();
  const [system, setSystem] = useState<ContextSystem>();
  const [hasChanges, setHasChanges] = useState<boolean>(false);

  const setForm = useCallback(
    (data: ContextSystem) => {
      form.setFieldsValue({
        name: data.name,
        description: data.description,
      });
    },
    [form],
  );

  const {
    loading: loadingSystem,
    error: loadError,
    execute: loadSystem,
  } = useAsyncRequest(
    async (id: string) => {
      if (contextServiceCache[id]) {
        return contextServiceCache[id];
      }
      const data = await api.getContextService(id);
      contextServiceCache[id] = data;
      return data;
    },
    { initialValue: null },
  );

  useEffect(() => {
    if (!systemId) return;
    void loadSystem(systemId).then((data) => {
      if (data) {
        setSystem(data);
        setForm(data);
      }
    });
  }, [systemId, activeTab]);

  const {
    loading: savingSystem,
    error: saveError,
    execute: saveSystem,
  } = useAsyncRequest(async () => {
    if (!system || !systemId) throw new Error("No system");
    const values = (await form.validateFields()) as FormValues;
    const payload = {
      ...system,
      name: values.name,
      description: values.description,
    };
    const updated: ContextSystem = await api.updateContextService(
      systemId,
      payload,
    );
    setSystem(updated);
    contextServiceCache[systemId] = updated;
    return updated;
  });

  const handleSave = async (): Promise<void> => {
    await saveSystem();
    setHasChanges(false);
  };

  if (loadingSystem) return <Spin style={{ margin: 32 }} />;
  if (loadError)
    return <div style={{ color: "red", margin: 32 }}>{loadError}</div>;
  if (!system) return null;

  return (
    <div style={{ paddingLeft: sidePadding, maxWidth: 900 }}>
      <Form form={form} layout="vertical" onChange={() => setHasChanges(true)}>
        <Form.Item
          label="Name"
          name="name"
          rules={[{ required: true, message: "Enter service name" }]}
        >
          <Input maxLength={128} />
        </Form.Item>
        <Form.Item label="Description" name="description">
          <Input.TextArea maxLength={512} />
        </Form.Item>
        {!isVsCode && (
          <Descriptions column={1} size="small" style={{ margin: "24px 0" }}>
            <Descriptions.Item label="Created">
              {formatTimestamp(system.createdWhen as string)}
            </Descriptions.Item>
            <Descriptions.Item label="Modified">
              {formatTimestamp(system.modifiedWhen as string)}
            </Descriptions.Item>
          </Descriptions>
        )}
        <Button
          type="primary"
          className={styles["variables-actions"]}
          onClick={() => {
            void handleSave();
          }}
          loading={savingSystem}
          disabled={savingSystem || !hasChanges}
        >
          Save
        </Button>
        {saveError && (
          <div style={{ color: "red", marginTop: 8 }}>{saveError}</div>
        )}
      </Form>
    </div>
  );
};
