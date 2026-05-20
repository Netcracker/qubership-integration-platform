import React, { useState, useEffect, useCallback, useMemo } from "react";
import { Form, Input, Flex, Tooltip, message } from "antd";
import { InfoCircleOutlined } from "@ant-design/icons";
import { useForm } from "antd/lib/form/Form";
import { api } from "../../../api/api.ts";
import { useNotificationService } from "../../../hooks/useNotificationService.tsx";
import { downloadFile } from "../../../misc/download-utils.ts";
import { MaasFormActions } from "./MaasFormActions.tsx";
import { MaasPageHeader } from "./MaasPageHeader.tsx";
import { NamespaceField } from "./NamespaceField.tsx";
import {
  RabbitMQMaasFormData,
  NON_WHITESPACE_PATTERN,
  DEFAULT_VHOST,
  RABBITMQ_FIELD_NAMES,
  getMaasDefaultNamespace,
  isRabbitMQFormValid,
} from "./types.ts";
import sharedStyles from "../DevTools.module.css";
import styles from "./Maas.module.css";

export const RabbitMQMaasPage: React.FC = () => {
  const [form] = useForm<RabbitMQMaasFormData>();
  const [exportInProgress, setExportInProgress] = useState(false);
  const [createInProgress, setCreateInProgress] = useState(false);
  const notificationService = useNotificationService();

  const formValues = Form.useWatch((values) => values, form) as
    | Partial<RabbitMQMaasFormData>
    | undefined;

  const isFormValid = useMemo(
    () => isRabbitMQFormValid(formValues),
    [formValues],
  );

  const getInitialFormValues = useCallback(
    (): Partial<RabbitMQMaasFormData> => ({
      [RABBITMQ_FIELD_NAMES.NAMESPACE]: getMaasDefaultNamespace(),
      [RABBITMQ_FIELD_NAMES.VHOST]: DEFAULT_VHOST,
      [RABBITMQ_FIELD_NAMES.EXCHANGE]: "",
      [RABBITMQ_FIELD_NAMES.QUEUE]: "",
      [RABBITMQ_FIELD_NAMES.ROUTING_KEY]: "",
    }),
    [],
  );

  useEffect(() => {
    form.setFieldsValue(getInitialFormValues());
  }, [form, getInitialFormValues]);

  const handleReset = useCallback(() => {
    form.setFieldsValue(getInitialFormValues());
  }, [form, getInitialFormValues]);

  const handleExport = useCallback(async () => {
    try {
      const values = await form.validateFields([
        RABBITMQ_FIELD_NAMES.VHOST,
        RABBITMQ_FIELD_NAMES.EXCHANGE,
        RABBITMQ_FIELD_NAMES.QUEUE,
        RABBITMQ_FIELD_NAMES.ROUTING_KEY,
      ]);
      setExportInProgress(true);
      const file = await api.getMaasRabbitMQDeclarativeFile({
        vhost: values.vhost,
        exchange: values.exchange ?? "",
        queue: values.queue ?? "",
        routingKey: values.routingKey,
      });
      downloadFile(file);
      message.success("Declarative file downloaded.");
    } catch (error) {
      notificationService.requestFailed(
        "Unable to export RabbitMQ declarative file.",
        error,
      );
    } finally {
      setExportInProgress(false);
    }
  }, [form, notificationService]);

  const handleCreate = useCallback(async () => {
    try {
      const values = await form.validateFields();
      setCreateInProgress(true);
      await api.createMaasRabbitMQEntity({
        namespace: values.namespace,
        vhost: values.vhost,
        exchange: values.exchange ?? "",
        queue: values.queue ?? "",
        routingKey: values.routingKey,
      });
      message.success(
        `MaaS RabbitMQ entity created successfully: Namespace=[${values.namespace}] Vhost=[${values.vhost}] Exchange=[${values.exchange ?? ""}] Queue=[${values.queue ?? ""}] Routing key=[${values.routingKey ?? ""}]`,
      );
    } catch (error) {
      notificationService.requestFailed(
        "Unable to create MaaS RabbitMQ entity with given values.",
        error,
      );
    } finally {
      setCreateInProgress(false);
    }
  }, [form, notificationService]);

  const validateExchangeOrQueue = useCallback(
    (_: unknown) => {
      const exchange = String(
        form.getFieldValue(RABBITMQ_FIELD_NAMES.EXCHANGE) ?? "",
      ).trim();
      const queue = String(
        form.getFieldValue(RABBITMQ_FIELD_NAMES.QUEUE) ?? "",
      ).trim();
      if (!exchange && !queue) {
        return Promise.reject(
          new Error(
            'Fields "Exchange Name" and "Queue Name" can\'t be empty at the same time.',
          ),
        );
      }
      return Promise.resolve();
    },
    [form],
  );

  const validateRoutingKey = useCallback(
    (_: unknown, value: string) => {
      const routingKeyTrimmed = value?.trim() || "";
      if (routingKeyTrimmed) {
        const exchange = String(
          form.getFieldValue(RABBITMQ_FIELD_NAMES.EXCHANGE) ?? "",
        ).trim();
        const queue = String(
          form.getFieldValue(RABBITMQ_FIELD_NAMES.QUEUE) ?? "",
        ).trim();
        if (!exchange || !queue) {
          return Promise.reject(
            new Error(
              'Fields "Exchange Name" and "Queue Name" must be specified.',
            ),
          );
        }
      }
      return Promise.resolve();
    },
    [form],
  );

  return (
    <Flex vertical className={sharedStyles["container"]}>
      <MaasPageHeader
        title="RabbitMQ - MaaS"
        exportInProgress={exportInProgress}
        isFormValid={isFormValid}
        onExport={() => void handleExport()}
      />

      <div className={styles["parametersSection"]}>
        <div className={styles["parametersHeading"]}>Parameters</div>
        <Form
          form={form}
          layout="vertical"
          onFinish={() => void handleCreate()}
        >
          <NamespaceField />

          <Form.Item
            label="Vhost Classifier Name"
            name={RABBITMQ_FIELD_NAMES.VHOST}
            required
            rules={[
              { required: true, message: "Vhost Classifier Name is required" },
              {
                pattern: NON_WHITESPACE_PATTERN,
                message: "Vhost Classifier Name cannot be empty",
              },
            ]}
          >
            <Input placeholder="Enter vhost classifier name" />
          </Form.Item>

          <Form.Item
            label="Exchange Name"
            name={RABBITMQ_FIELD_NAMES.EXCHANGE}
            dependencies={[RABBITMQ_FIELD_NAMES.QUEUE]}
            rules={[{ validator: validateExchangeOrQueue }]}
          >
            <Input placeholder="Enter exchange name" />
          </Form.Item>

          <Form.Item
            label="Queue Name"
            name={RABBITMQ_FIELD_NAMES.QUEUE}
            dependencies={[RABBITMQ_FIELD_NAMES.EXCHANGE]}
            rules={[{ validator: validateExchangeOrQueue }]}
          >
            <Input placeholder="Enter queue name" />
          </Form.Item>

          <Form.Item
            label={
              <span className={styles["labelWithIcon"]}>
                Routing Key
                <Tooltip
                  title='When both "Exchange Name" and "Queue Name" are specified, there will be binding created with routing key value from this field.'
                  placement="top"
                >
                  <InfoCircleOutlined
                    className={styles["infoIcon"]}
                    aria-label="Routing key help"
                  />
                </Tooltip>
              </span>
            }
            name={RABBITMQ_FIELD_NAMES.ROUTING_KEY}
            dependencies={[
              RABBITMQ_FIELD_NAMES.EXCHANGE,
              RABBITMQ_FIELD_NAMES.QUEUE,
            ]}
            rules={[{ validator: validateRoutingKey }]}
          >
            <Input placeholder="Enter routing key" />
          </Form.Item>
        </Form>
      </div>

      <div className={styles["footer"]}>
        <MaasFormActions
          createInProgress={createInProgress}
          isFormValid={isFormValid}
          onCreate={() => void handleCreate()}
          onReset={handleReset}
        />
      </div>
    </Flex>
  );
};
