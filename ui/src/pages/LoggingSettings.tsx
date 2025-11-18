import React, { useCallback, useEffect, useState } from "react";
import { Button, Form, Select, SelectProps, Spin } from "antd";
import { useParams } from "react-router";
import { useForm } from "antd/lib/form/Form";
import Checkbox from "antd/lib/checkbox";
import {
  ChainLoggingProperties,
  ChainLoggingSettings,
  LogLoggingLevel,
  LogPayload,
  SessionsLoggingLevel,
} from "../api/apiTypes.ts";
import { capitalize } from "../misc/format-utils.ts";

import { api } from "../api/api.ts";
import { useNotificationService } from "../hooks/useNotificationService.tsx";
import styles from "./Chain.module.css";

type LogSettingsFormState = ChainLoggingProperties & { custom: boolean };

export const LoggingSettings: React.FC = () => {
  const { chainId } = useParams<{ chainId: string }>();
  const [isLoggingSettingsLoading, setIsLoggingSettingsLoading] =
    useState(false);
  const [loggingSettings, setLoggingSettings] =
    useState<ChainLoggingSettings | null>(null);
  const [isCustom, setIsCustom] = useState(false);
  const [form] = useForm();
  const notificationService = useNotificationService();

  const getLoggingSettings =
    useCallback(async (): Promise<ChainLoggingSettings | null> => {
      if (!chainId) {
        return null;
      }
      setIsLoggingSettingsLoading(true);
      try {
        return api.getLoggingSettings(chainId);
      } catch (error) {
        notificationService.requestFailed(
          "Failed to get logging settings",
          error,
        );
        return null;
      } finally {
        setIsLoggingSettingsLoading(false);
      }
    }, [chainId, notificationService]);

  useEffect(() => {
    void getLoggingSettings().then(setLoggingSettings);
  }, [chainId, getLoggingSettings]);

  useEffect(() => {
    if (loggingSettings) {
      const properties: ChainLoggingProperties =
        loggingSettings?.custom ??
        loggingSettings?.consulDefault ??
        loggingSettings?.fallbackDefault;
      const custom = !!loggingSettings?.custom;
      setIsCustom(custom);
      form.setFieldsValue({ custom, ...properties });
    }
  }, [form, loggingSettings]);

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
      notificationService.requestFailed(
        "Failed to delete logging settings",
        error,
      );
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
      notificationService.requestFailed(
        "Failed to update logging settings",
        error,
      );
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
    labelCol: { flex: "150px" },
    wrapperCol: { flex: "auto" },
  };

  const formItemStyle = {
    style: { marginLeft: 150 },
  };

  const sessionLevelOptions: SelectProps<SessionsLoggingLevel>["options"] =
    Object.values(SessionsLoggingLevel).map((value) => {
      return {
        value: value,
        label: capitalize(value),
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

  return (
    <div className={styles.pageContainer as string}>
      <div className={styles.formContent as string} style={{ position: "relative" }}>
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
          onValuesChange={(changedValues: Partial<LogSettingsFormState>) => {
            if (changedValues.custom !== undefined) {
              setIsCustom(changedValues.custom);
            }
          }}
          onFinish={(formState) => void setLoggingProperties(formState)}
        >
          <Form.Item label={null} name="custom" valuePropName="checked" {...formItemStyle}>
            <Checkbox>Override default properties</Checkbox>
          </Form.Item>
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
          <Form.Item label={null} name="dptEventsEnabled" valuePropName="checked" {...formItemStyle}>
            <Checkbox disabled={!isCustom}>Produce DPT Events</Checkbox>
          </Form.Item>
          <Form.Item label={null} name="maskingEnabled" valuePropName="checked" {...formItemStyle}>
            <Checkbox disabled={!isCustom}>Enable logging masking</Checkbox>
          </Form.Item>
          <Form.Item label={null} {...formItemStyle}>
            <Button 
              type="primary" 
              htmlType="submit" 
              loading={isLoggingSettingsLoading}
            >
              Apply
            </Button>
          </Form.Item>
        </Form>
      </div>
    </div>
  );
};
