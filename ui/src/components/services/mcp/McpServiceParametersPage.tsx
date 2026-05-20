import React, { useCallback, useEffect, useState } from "react";
import {
  Alert,
  Breadcrumb,
  Button,
  Descriptions,
  Flex,
  Form,
  Input,
  Select,
} from "antd";
import {
  ServiceParametersPageHeader,
  ServiceParametersPageLayout,
} from "../detail/ServiceParametersPage.tsx";
import {
  ServiceNameBreadcrumbItem,
  ServiceTypeBreadcrumbItem,
} from "../detail/ServiceBreadcrumb.tsx";
import {
  IntegrationSystemType,
  MCPSystem,
  MCPSystemCreateRequest,
  MCPSystemUpdateRequest,
} from "../../../api/apiTypes.ts";
import { useNotificationService } from "../../../hooks/useNotificationService.tsx";
import { api } from "../../../api/api.ts";
import { useParams } from "react-router";
import { isVsCode } from "../../../api/rest/vscodeExtensionApi.ts";
import { useForm } from "antd/lib/form/Form";
import { formatTimestamp } from "../../../misc/format-utils.ts";
import { getErrorMessage } from "../../../misc/error-utils.ts";

export const McpServiceParametersPage: React.FC = () => {
  const { systemId } = useParams<{
    systemId: string;
  }>();
  const [system, setSystem] = useState<MCPSystem | null>(null);
  const [isLoading, setIsLoading] = useState<boolean>(false);
  const [isSaving, setIsSaving] = useState<boolean>(false);
  const [hasChanges, setHasChanges] = useState<boolean>(false);
  const [errorText, setErrorText] = useState<string | null>(null);
  const [form] = useForm();
  const notificationService = useNotificationService();

  const loadSystem = useCallback(
    async (id: string) => {
      if (!id) {
        setSystem(null);
        return;
      }
      try {
        setIsLoading(true);
        const result = await api.getMcpSystem(id);
        setSystem(result);
      } catch (e) {
        notificationService.requestFailed("Failed to load MCP service", e);
        setSystem(null);
      } finally {
        setIsLoading(false);
      }
    },
    [notificationService],
  );

  const updateSystem = useCallback(
    async (id: string, changes: MCPSystemCreateRequest) => {
      try {
        setIsSaving(true);
        const result = await api.updateMcpSystem(id, changes);
        setSystem(result);
        setErrorText(null);
      } catch (e) {
        notificationService.requestFailed("Failed to update MCP service", e);
        setErrorText(getErrorMessage(e));
      } finally {
        setIsSaving(false);
      }
    },
    [notificationService],
  );

  useEffect(() => {
    if (systemId) {
      void loadSystem(systemId);
    }
  }, [loadSystem, systemId]);

  useEffect(() => {
    form.setFieldsValue(system);
  }, [form, system]);

  return (
    <ServiceParametersPageLayout>
      <Flex vertical style={{ flex: 1, minHeight: 0 }} gap={8}>
        <Breadcrumb
          items={[
            {
              title: (
                <ServiceTypeBreadcrumbItem type={IntegrationSystemType.MCP} />
              ),
            },
            {
              title: (
                <ServiceNameBreadcrumbItem
                  type={IntegrationSystemType.MCP}
                  id={system?.id}
                  name={system?.name}
                />
              ),
            },
          ]}
        />
        <ServiceParametersPageHeader />
        <Form<MCPSystemUpdateRequest>
          id={"updateSystemForm"}
          form={form}
          disabled={isLoading || isSaving}
          layout="vertical"
          onChange={() => setHasChanges(true)}
          onFinish={(values) => void updateSystem(system!.id, values)}
          initialValues={system ?? undefined}
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
          <Form.Item
            label="Labels"
            name="labels"
            getValueProps={(labels: MCPSystem["labels"]) => {
              return {
                value: labels?.map((l) => l.name) ?? [],
              };
            }}
            normalize={(values: string[]) => {
              return values.map((v) => ({ name: v, technical: false }));
            }}
          >
            <Select
              mode="tags"
              tokenSeparators={[" "]}
              classNames={{ popup: { root: "not-displayed" } }}
              suffixIcon={<></>}
            />
          </Form.Item>
          <Form.Item
            label="Identifier"
            name="identifier"
            rules={[{ required: true, message: "Enter service identifier" }]}
          >
            <Input maxLength={128} />
          </Form.Item>
          <Form.Item label="Instructions" name="instructions">
            <Input.TextArea />
          </Form.Item>
          {!isVsCode && (
            <Descriptions column={1} size="small" style={{ margin: "24px 0" }}>
              <Descriptions.Item label="Created">
                {formatTimestamp(system?.createdWhen)}
              </Descriptions.Item>
              <Descriptions.Item label="Modified">
                {formatTimestamp(system?.modifiedWhen)}
              </Descriptions.Item>
            </Descriptions>
          )}
          <Button
            type="primary"
            htmlType="submit"
            form={"updateSystemForm"}
            loading={isSaving}
            disabled={!system || isLoading || isSaving || !hasChanges}
          >
            Save
          </Button>
        </Form>
        {errorText && <Alert message={errorText} type="error" showIcon />}
      </Flex>
    </ServiceParametersPageLayout>
  );
};
