import React, { useEffect, useState } from "react";
import { Form, Input, Button, Descriptions, Spin, Select } from "antd";
import { ContextSystem } from "../../../api/apiTypes";
import { api } from "../../../api/api";
import { useAsyncRequest } from "../useAsyncRequest";
import { isVsCode } from "../../../api/rest/vscodeExtensionApi.ts";
import { contextServiceCache } from "../utils.tsx";
import { ServiceParametersTabProps } from "../detail/ServiceParametersTab.tsx";
import { ContextServiceTag } from "../ServiceLabel.tsx";
import { useLabelsForm } from "../useLabelsForm.ts";
import { useUnsavedChangesWithModal } from "../useUnsavedChangesWithModal.tsx";

interface FormValues {
  name: string;
  description?: string;
}

export const ContextServiceParametersTab: React.FC<
  ServiceParametersTabProps
> = ({ systemId, activeTab, formatTimestamp, sidePadding, styles }) => {
  const [form] = Form.useForm();
  const { technicalLabels, userLabels, setUserLabels, onSetLabelsAndForm } =
    useLabelsForm(form);
  const [system, setSystem] = useState<ContextSystem>();
  const { hasChanges, setHasChanges } = useUnsavedChangesWithModal({
    system,
    blockerCondition: activeTab === "parameters",
    onYes: async () => {
      await handleSave();
    },
  });

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
        onSetLabelsAndForm(data);
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
      labels: [
        ...technicalLabels.map((name) => ({ name, technical: true })),
        ...userLabels.map((name) => ({ name, technical: false })),
      ],
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
        <Form.Item label="Labels" name="labels">
          <Select
            mode="tags"
            style={{ width: "100%" }}
            value={[...technicalLabels, ...userLabels]}
            onChange={(allSelected: string[]) => {
              const updatedUserLabels = allSelected.filter(
                (label) => !technicalLabels.includes(label),
              );
              setUserLabels(updatedUserLabels);
              form.setFieldsValue({
                labels: [...technicalLabels, ...updatedUserLabels],
              });
              setHasChanges(true);
            }}
            tagRender={({ label, onClose }) => {
              const isTech = technicalLabels.includes(
                typeof label === "string" ? label : "",
              );

              return <ContextServiceTag {...{ label, onClose, isTech }} />;
            }}
            placeholder="Add labels"
          />
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
