import React, { useEffect, useState } from "react";
import { Form, Input, Button, Select, Descriptions, Spin } from "antd";
import {
  IntegrationSystem,
  IntegrationSystemType,
} from "../../../api/apiTypes";
import { api } from "../../../api/api";
import { useAsyncRequest } from "../useAsyncRequest";
import { SourceFlagTag } from "../ui/SourceFlagTag";
import { prepareFile, serviceCache } from "../utils.tsx";
import { isVsCode } from "../../../api/rest/vscodeExtensionApi.ts";
import { downloadFile } from "../../../misc/download-utils.ts";
import { useNotificationService } from "../../../hooks/useNotificationService.tsx";
import { useServiceParametersToolbar } from "./ServiceParametersPage";
import { usePermissions } from "../../../permissions/usePermissions.tsx";
import { hasPermissions } from "../../../permissions/funcs.ts";
import { Require } from "../../../permissions/Require.tsx";
import { ProtectedButton } from "../../../permissions/ProtectedButton.tsx";
import { ContextServiceTag } from "../ServiceLabel.tsx";
import { useLabelsForm } from "../useLabelsForm.ts";
import { useUnsavedChangesWithModal } from "../useUnsavedChangesWithModal.tsx";

export interface ServiceParametersTabProps {
  systemId: string;
  activeTab: string;
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
  const { setToolbar } = useServiceParametersToolbar() ?? {};
  const [form] = Form.useForm();
  const { technicalLabels, userLabels, setUserLabels, onSetLabelsAndForm } =
      useLabelsForm(form);
  const [system, setSystem] = useState<IntegrationSystem | null>(null);
  const { hasChanges, setHasChanges } = useUnsavedChangesWithModal({
    system,
    blockerCondition: activeTab === "parameters",
    onYes: async () => {
      await handleSave();
    },
  });
  const notificationService = useNotificationService();
  const permissions = usePermissions();
  const [disabled, setDisabled] = useState<boolean>(false);

  useEffect(() => {
    setDisabled(!hasPermissions(permissions, { service: ["update"] }));
  }, [permissions]);

  const {
    loading: loadingSystem,
    error: loadError,
    execute: loadSystem,
  } = useAsyncRequest(
    async (id: string) => {
      if (serviceCache[id]) {
        return serviceCache[id];
      }
      const data = await api.getService(id);
      serviceCache[id] = data;
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
    const values = (await form.validateFields()) as ServiceFormValues;
    const payload = {
      ...system,
      name: values.name,
      description: values.description,
      type: isVsCode ? values.type : system.type,
      labels: [
        ...technicalLabels.map((name) => ({ name, technical: true })),
        ...userLabels.map((name) => ({ name, technical: false })),
      ],
    };
    const updated: IntegrationSystem = await api.updateService(
      systemId,
      payload,
    );
    setSystem(updated);
    serviceCache[systemId] = updated;
    return updated;
  });

  const handleSave = async (): Promise<void> => {
    await saveSystem();
    setHasChanges(false);
  };

  useEffect(() => {
    if (!setToolbar || activeTab !== "parameters") return;
    const toolbar =
      !isVsCode && system && systemId ? (
        <ProtectedButton
          require={{ service: ["export"] }}
          tooltipProps={{ title: "Export service", placement: "bottom" }}
          buttonProps={{
            iconName: "cloudDownload",
            onClick: () => {
              void (async () => {
                if (!systemId) return;
                try {
                  const file = await api.exportServices([systemId], []);
                  downloadFile(prepareFile(file));
                } catch (e) {
                  notificationService.requestFailed("Export error", e);
                }
              })();
            },
          }}
        />
      ) : null;
    setToolbar(toolbar);
    return () => setToolbar(null);
  }, [setToolbar, activeTab, system, systemId, notificationService]);

  if (loadingSystem) return <Spin style={{ margin: 32 }} />;
  if (loadError)
    return <div style={{ color: "red", margin: 32 }}>{loadError}</div>;
  if (!system) return null;

  return (
    <div style={{ paddingLeft: sidePadding, maxWidth: 900 }}>
      <Form
        form={form}
        layout="vertical"
        onChange={() => setHasChanges(true)}
        disabled={disabled}
      >
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
            {system.protocol ? (
              <SourceFlagTag source={system.protocol} toUpperCase={true} />
            ) : (
              "-"
            )}
          </Descriptions.Item>
        </Descriptions>
        {isVsCode && (
          <Form.Item
            label="Type"
            name="type"
            rules={[{ required: true, message: "Select service type" }]}
          >
            <Select onChange={() => setHasChanges(true)}>
              <Select.Option value={IntegrationSystemType.INTERNAL}>
                Internal
              </Select.Option>
              <Select.Option value={IntegrationSystemType.EXTERNAL}>
                External
              </Select.Option>
              <Select.Option value={IntegrationSystemType.IMPLEMENTED}>
                Implemented
              </Select.Option>
            </Select>
          </Form.Item>
        )}
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
        <Require permissions={{ service: ["update"] }}>
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
        </Require>
        {saveError && (
          <div style={{ color: "red", marginTop: 8 }}>{saveError}</div>
        )}
      </Form>
    </div>
  );
};
