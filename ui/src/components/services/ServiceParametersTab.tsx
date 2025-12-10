import React, { useEffect, useState, useCallback } from "react";
import { Form, Input, Button, Select, Tag, Descriptions, Spin, FloatButton } from "antd";
import { IntegrationSystem, IntegrationSystemType } from "../../api/apiTypes";
import { api } from "../../api/api";
import { useAsyncRequest } from './useAsyncRequest';
import { SourceFlagTag } from "./SourceFlagTag";
import { prepareFile, serviceCache } from "./utils.tsx";
import { isVsCode } from "../../api/rest/vscodeExtensionApi.ts";
import { useBlocker } from "react-router-dom";
import { useModalsContext } from "../../Modals.tsx";
import { UnsavedChangesModal } from "../modal/UnsavedChangesModal.tsx";
import FloatButtonGroup from "antd/lib/float-button/FloatButtonGroup";
import { OverridableIcon } from "../../icons/IconProvider.tsx";
import { downloadFile } from "../../misc/download-utils.ts";
import { useNotificationService } from "../../hooks/useNotificationService.tsx";

export interface ServiceParametersTabProps {
  systemId: string;
  activeTab: string
  formatTimestamp: (val: string) => string;
  sidePadding: number;
  styles: Record<string, string>;
}

interface ServiceFormValues {
  name: string;
  description?: string;
  labels: string[];
  type: IntegrationSystemType;
}

export const ServiceParametersTab: React.FC<ServiceParametersTabProps> = ({
  systemId,
  activeTab,
  formatTimestamp,
  sidePadding,
  styles,
}) => {
  const [form] = Form.useForm();
  const [system, setSystem] = useState<IntegrationSystem | null>(null);
  const [technicalLabels, setTechnicalLabels] = useState<string[]>([]);
  const [userLabels, setUserLabels] = useState<string[]>([]);
  const [hasChanges, setHasChanges] = useState<boolean>(false);
  const blocker = useBlocker(hasChanges);
  const { showModal } = useModalsContext();
  const notificationService = useNotificationService();

  const setLabelsAndForm = useCallback((data: IntegrationSystem) => {
    setTechnicalLabels(data.labels?.filter(l => l.technical).map(l => l.name) || []);
    setUserLabels(data.labels?.filter(l => !l.technical).map(l => l.name) || []);
    form.setFieldsValue({
      name: data.name,
      description: data.description,
      type: data.type,
      labels: [
        ...(data.labels?.filter(l => l.technical).map(l => l.name) || []),
        ...(data.labels?.filter(l => !l.technical).map(l => l.name) || []),
      ],
    });
  }, [form]);

  const {
    loading: loadingSystem,
    error: loadError,
    execute: loadSystem,
  } = useAsyncRequest(async (id: string) => {
    if (serviceCache[id]) {
      return serviceCache[id];
    }
    const data = await api.getService(id);
    serviceCache[id] = data;
    return data;
  }, { initialValue: null });

  useEffect(() => {
    if (!systemId) return;
    void loadSystem(systemId).then((data) => {
      if (data) {
        setSystem(data);
        setLabelsAndForm(data);
      }
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [systemId, activeTab]);

  useEffect(() => {
    if (blocker.state === "blocked") {
      showModal({
        component: (
          <UnsavedChangesModal
            onYes={() => {
              blocker.proceed();
              setHasChanges(false);
            }}
            onNo={() => {
              blocker.reset();
            }}
          />
        ),
      });
    }
  }, [blocker, showModal]);

  const {
    loading: savingSystem,
    error: saveError,
    execute: saveSystem,
  } = useAsyncRequest(async () => {
    if (!system || !systemId) throw new Error('No system');
    const values = (await form.validateFields()) as ServiceFormValues;
    const payload = {
      ...system,
      name: values.name,
      description: values.description,
      type: values.type,
      labels: [
        ...technicalLabels.map(name => ({ name, technical: true })),
        ...userLabels.map(name => ({ name, technical: false })),
      ],
    };
    const updated: IntegrationSystem = await api.updateService(systemId, payload);
    setSystem(updated);
    serviceCache[systemId] = updated;
    return updated;
  });

  const handleSave = async (): Promise<void> => {
    await saveSystem();
    setHasChanges(false);
  };

  if (loadingSystem) return <Spin style={{ margin: 32 }} />;
  if (loadError) return <div style={{ color: 'red', margin: 32 }}>{loadError}</div>;
  if (!system) return null;

  return (
    <div style={{ paddingLeft: sidePadding, maxWidth: 900 }}>
      <>
        {!isVsCode && (
          <FloatButtonGroup trigger="hover" icon={<OverridableIcon name="more" />}>
            <FloatButton
              tooltip={{ title: "Export service", placement: "left" }}
              icon={<OverridableIcon name="cloudDownload" />}
              onClick={() => {
                void (async () => {
                  if (!systemId) {
                    return;
                  }
                  try {
                    const file = await api.exportServices([systemId], []);
                    downloadFile(prepareFile(file));
                  } catch (e) {
                    notificationService.requestFailed("Export error", e);
                  }
                })();
              }}
            />
          </FloatButtonGroup>
        )}
      </>
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
        <Descriptions column={1} size="small" style={{ marginBottom: 16 }}>
          <Descriptions.Item label="Protocol">
            {system.protocol ? <SourceFlagTag source={system.protocol} toUpperCase={true} /> : '-'}
          </Descriptions.Item>
        </Descriptions>
        <Form.Item
          label="Type"
          name="type"
          rules={[{ required: true, message: "Select service type" }]}
        >
          <Select onChange={() => setHasChanges(true)}>
            <Select.Option value={IntegrationSystemType.INTERNAL}>Internal</Select.Option>
            <Select.Option value={IntegrationSystemType.EXTERNAL}>External</Select.Option>
            <Select.Option value={IntegrationSystemType.IMPLEMENTED}>Implemented</Select.Option>
          </Select>
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
              setHasChanges(true)
            }}
            tagRender={({ label, onClose }) => {
              const isTech = technicalLabels.includes(typeof label === "string" ? label : "");
              return (
                <Tag
                  color={isTech ? "blue" : "default"}
                  closable={!isTech}
                  onClose={onClose}
                  style={{ marginRight: 4 }}
                >
                  {label}
                </Tag>
              );
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
          onClick={() => { void handleSave(); }}
          loading={savingSystem}
          disabled={savingSystem || !hasChanges}
        >
          Save
        </Button>
        {(saveError) && <div style={{ color: 'red', marginTop: 8 }}>{saveError}</div>}
      </Form>
    </div>
  );
};
