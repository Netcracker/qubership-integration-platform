import React, { useEffect, useState } from "react";
import {
  Button,
  Flex,
  FloatButton,
  Form,
  notification,
  Select,
  SelectProps,
  Spin,
  Table
} from "antd";
import { useParams } from "react-router";
import { useForm } from "antd/lib/form/Form";
import Checkbox from "antd/lib/checkbox";
import {
  ChainLoggingProperties,
  ChainLoggingSettings,
  LogLoggingLevel,
  LogPayload,
  MaskedField,
  SessionsLoggingLevel,
} from "../api/apiTypes.ts";
import { TableProps } from "antd/lib/table";
import {
  getTextColumnFilterFn,
  TextColumnFilterDropdown,
} from "../components/table/TextColumnFilterDropdown.tsx";
import { formatTimestamp } from "../misc/format-utils.ts";
import {
  getTimestampColumnFilterFn,
  TimestampColumnFilterDropdown,
} from "../components/table/TimestampColumnFilterDropdown.tsx";
import { TableRowSelection } from "antd/lib/table/interface";
import { InlineEdit } from "../components/InlineEdit.tsx";
import { TextValueEdit } from "../components/table/TextValueEdit.tsx";
import { api } from "../api/api.ts";
import { DeleteOutlined, MoreOutlined, PlusOutlined } from "@ant-design/icons";
import FloatButtonGroup from "antd/lib/float-button/FloatButtonGroup";
import capitalize from "antd/es/_util/capitalize";

type LogSettingsFormState = ChainLoggingProperties & { custom: boolean };

export const LoggingSettings: React.FC = () => {
  const { chainId } = useParams<{ chainId: string }>();
  const [selectedRowKeys, setSelectedRowKeys] = useState<React.Key[]>([]);
  const [maskedFields, setMaskedFields] = useState<MaskedField[]>([]);
  const [isLoggingSettingsLoading, setIsLoggingSettingsLoading] =
    useState(false);
  const [loggingSettings, setLoggingSettings] =
    useState<ChainLoggingSettings | null>(null);
  const [isMaskedFieldsLoading, setIsMaskedFieldsLoading] = useState(false);
  const [isCustom, setIsCustom] = useState(false);
  const [form] = useForm();

  useEffect(() => {
    getLoggingSettings().then(setLoggingSettings);
    getMaskedFields().then(setMaskedFields);
  }, [chainId]);

  useEffect(() => {
    if (!!loggingSettings) {
      const properties: ChainLoggingProperties =
        loggingSettings?.custom ??
        loggingSettings?.consulDefault ??
        loggingSettings?.fallbackDefault;
      const custom = !!loggingSettings?.custom;
      setIsCustom(custom);
      form.setFieldsValue({ custom, ...properties });
    }
  }, [loggingSettings]);

  const getLoggingSettings = async (): Promise<ChainLoggingSettings | null> => {
    if (!chainId) {
      return null;
    }
    setIsLoggingSettingsLoading(true);
    try {
      return api.getLoggingSettings(chainId);
    } catch (error) {
      notification.error({
        message: "Request failed",
        description: "Failed to get logging settings",
      });
      return null;
    } finally {
      setIsLoggingSettingsLoading(false);
    }
  };

  const getMaskedFields = async (): Promise<MaskedField[]> => {
    setIsMaskedFieldsLoading(true);
    if (!chainId) {
      return [];
    }
    try {
      return api.getMaskedFields(chainId);
    } catch (error) {
      notification.error({
        message: "Request failed",
        description: "Failed to get masked fields",
      });
      return [];
    } finally {
      setIsMaskedFieldsLoading(false);
    }
  };

  const updateMaskedField = async (
    fieldId: string,
    changes: Partial<Omit<MaskedField, "id">>,
  ): Promise<void> => {
    if (!chainId) {
      return;
    }
    setIsMaskedFieldsLoading(true);
    try {
      const field = await api.updateMaskedField(chainId, fieldId, changes);
      setMaskedFields(maskedFields.map((f) => (f.id === fieldId ? field : f)));
    } catch (error) {
      notification.error({
        message: "Request failed",
        description: "Failed to update masked field",
      });
    } finally {
      setIsMaskedFieldsLoading(false);
    }
  };

  const createMaskedField = async () => {
    if (!chainId) {
      return;
    }
    setIsMaskedFieldsLoading(true);
    try {
      const field = await api.createMaskedField(chainId, { name: "" });
      setMaskedFields([field, ...maskedFields]);
    } catch (error) {
      notification.error({
        message: "Request failed",
        description: "Failed to create masked field",
      });
    } finally {
      setIsMaskedFieldsLoading(false);
    }
  };

  const deleteSelectedMaskedFields = async () => {
    if (!chainId) {
      return;
    }
    setIsMaskedFieldsLoading(true);
    try {
      const ids = selectedRowKeys.map((key) => key.toString());
      await api.deleteMaskedFields(chainId, ids);
      setMaskedFields(
        maskedFields?.filter((field) => !ids.some((id) => field.id === id)) ??
          [],
      );
    } catch (error) {
      notification.error({
        message: "Request failed",
        description: "Failed to delete masked fields",
      });
    } finally {
      setIsMaskedFieldsLoading(false);
    }
  };

  const deleteLoggingSettings = async () => {
    if (!chainId) {
      return null;
    }
    setIsLoggingSettingsLoading(true);
    try {
      await api.deleteLoggingSettings(chainId);
      delete loggingSettings?.custom;
      setLoggingSettings(loggingSettings);
    } catch (error) {
      notification.error({
        message: "Request failed",
        description: "Failed to delete logging settings",
      });
      return null;
    } finally {
      setIsLoggingSettingsLoading(false);
    }
  };

  const updateLoggingProperties = async (
    properties: ChainLoggingProperties,
  ): Promise<void> => {
    if (!chainId) {
      return;
    }
    setIsLoggingSettingsLoading(true);
    try {
      await api.setLoggingProperties(chainId, properties);
      setLoggingSettings({
        ...loggingSettings,
        fallbackDefault: loggingSettings?.fallbackDefault ?? properties,
        custom: properties,
      });
    } catch (error) {
      notification.error({
        message: "Request failed",
        description: "Failed to update logging settings",
      });
    } finally {
      setIsLoggingSettingsLoading(false);
    }
  };

  const setLoggingProperties = async (
    formState: LogSettingsFormState,
  ): Promise<void> => {
    if (!chainId) {
      return;
    }
    if (formState.custom) {
      await updateLoggingProperties(formState);
    } else {
      await deleteLoggingSettings();
    }
  };

  const formItemLayout = {
    labelCol: { span: 8 },
    wrapperCol: { span: 16 },
  };

  const sessionLevelOptions: SelectProps<SessionsLoggingLevel>["options"] =
    Object.values(SessionsLoggingLevel).map((value) => {
      return {
        value: value,
        label: capitalize(value[0]),
      };
    });

  const logLoggingLevelOptions: SelectProps<LogLoggingLevel>["options"] =
    Object.values(LogLoggingLevel).map((value) => ({
      value: value,
      label: capitalize(value),
    }));

  const logPayloadOptions: SelectProps<LogPayload>["options"] = Object.values(
    LogPayload,
  ).map((value) => ({
    value: value,
    label: capitalize(value),
  }));

  const columns: TableProps<MaskedField>["columns"] = [
    {
      title: "Field",
      dataIndex: "name",
      key: "name",
      sorter: (a, b) => a.name.localeCompare(b.name),
      filterDropdown: (props) => <TextColumnFilterDropdown {...props} />,
      onFilter: getTextColumnFilterFn((snapshot) => snapshot.name),
      render: (_, field) => (
        <InlineEdit<{ name: string }>
          values={{ name: field.name }}
          editor={<TextValueEdit name={"name"} />}
          viewer={field.name}
          onSubmit={async ({ name }) => {
            await updateMaskedField(field.id, { name });
          }}
        />
      ),
    },
    {
      title: "Created By",
      dataIndex: "createdBy",
      key: "createdBy",
      render: (_, field) => <>{field.createdBy.username}</>,
      sorter: (a, b) =>
        a.createdBy.username.localeCompare(b.createdBy.username),
      filterDropdown: (props) => <TextColumnFilterDropdown {...props} />,
      onFilter: getTextColumnFilterFn(
        (snapshot) => snapshot.createdBy.username,
      ),
    },
    {
      title: "Created At",
      dataIndex: "createdWhen",
      key: "createdWhen",
      render: (_, field) => <>{formatTimestamp(field.createdWhen)}</>,
      sorter: (a, b) => a.createdWhen - b.createdWhen,
      filterDropdown: (props) => <TimestampColumnFilterDropdown {...props} />,
      onFilter: getTimestampColumnFilterFn((snapshot) => snapshot.createdWhen),
    },
    {
      title: "Modified By",
      dataIndex: "modifiedBy",
      key: "modifiedBy",
      render: (_, field) => <>{field.modifiedBy.username}</>,
      sorter: (a, b) =>
        a.modifiedBy.username.localeCompare(b.modifiedBy.username),
      filterDropdown: (props) => <TextColumnFilterDropdown {...props} />,
      onFilter: getTextColumnFilterFn(
        (snapshot) => snapshot.modifiedBy.username,
      ),
    },
    {
      title: "Modified At",
      dataIndex: "modifiedWhen",
      key: "modifiedWhen",
      render: (_, field) => <>{formatTimestamp(field.modifiedWhen)}</>,
      sorter: (a, b) => a.modifiedWhen - b.modifiedWhen,
      filterDropdown: (props) => <TimestampColumnFilterDropdown {...props} />,
      onFilter: getTimestampColumnFilterFn((snapshot) => snapshot.modifiedWhen),
    },
  ];

  const onSelectChange = (newSelectedRowKeys: React.Key[]) => {
    setSelectedRowKeys(newSelectedRowKeys);
  };

  const onCreateBtnClick = async () => {
    await createMaskedField();
  };

  const onDeleteBtnClick = async () => {
    if (selectedRowKeys.length === 0) return;
    await deleteSelectedMaskedFields();
  };

  const rowSelection: TableRowSelection<MaskedField> = {
    type: "checkbox",
    selectedRowKeys,
    onChange: onSelectChange,
  };

  return (
    <>
      <Flex vertical={false} gap={16} style={{ height: "100%" }}>
        <Flex vertical gap={8} style={{ minWidth: 350, maxWidth: 350 }}>
          <h3>Logging Properties</h3>
          {isLoggingSettingsLoading ? (
            <Spin
              size="large"
              style={{
                position: "absolute",
                left: "calc(50% - 16px)",
                top: "calc(50% - 16px)",
              }}
            />
          ) : null}
          <Form<LogSettingsFormState>
            disabled={isLoggingSettingsLoading}
            labelWrap
            form={form}
            {...formItemLayout}
            onValuesChange={(formState) => {
              if (formState.custom !== undefined) {
                setIsCustom(formState.custom);
              }
            }}
            onFinish={async (formState) => setLoggingProperties(formState)}
          >
            <Form.Item label={null} name="custom" valuePropName="checked">
              <Checkbox>Override default properties</Checkbox>
            </Form.Item>
            {/* Logging properties source label */}
            <Form.Item label="Session level" name="sessionsLoggingLevel">
              <Select<SessionsLoggingLevel>
                disabled={!isCustom}
                options={sessionLevelOptions}
              ></Select>
            </Form.Item>
            <Form.Item label="Log level" name="logLoggingLevel">
              <Select<LogLoggingLevel>
                disabled={!isCustom}
                options={logLoggingLevelOptions}
              ></Select>
            </Form.Item>
            <Form.Item label="Log payload" name="logPayload">
              <Select<LogPayload[]>
                disabled={!isCustom}
                mode="multiple"
                allowClear
                options={logPayloadOptions}
              ></Select>
            </Form.Item>
            <Form.Item
              label={null}
              name="dptEventsEnabled"
              valuePropName="checked"
            >
              <Checkbox disabled={!isCustom}>Produce DPT Events</Checkbox>
            </Form.Item>
            <Form.Item
              label={null}
              name="maskingEnabled"
              valuePropName="checked"
            >
              <Checkbox disabled={!isCustom}>Enable logging masking</Checkbox>
            </Form.Item>
            <Form.Item label={null}>
              <Button type="primary" htmlType="submit">
                Apply
              </Button>
            </Form.Item>
          </Form>
        </Flex>
        <Flex vertical gap={8} style={{ flexShrink: 1, flexGrow: 1, overflow: 'auto' }}>
          <h3>Masked fields</h3>
          <Table
            columns={columns}
            rowSelection={rowSelection}
            dataSource={maskedFields}
            pagination={false}
            loading={isMaskedFieldsLoading}
            rowKey="id"
            className="flex-table"
            scroll={{ y: "" }}
          />
        </Flex>
      </Flex>
      <FloatButtonGroup trigger="hover" icon={<MoreOutlined />}>
        <FloatButton
          tooltip="Delete selected masked fields"
          icon={<DeleteOutlined />}
          onClick={onDeleteBtnClick}
        />
        <FloatButton
          tooltip="Add new masked field"
          icon={<PlusOutlined />}
          onClick={onCreateBtnClick}
        />
      </FloatButtonGroup>
    </>
  );
};
