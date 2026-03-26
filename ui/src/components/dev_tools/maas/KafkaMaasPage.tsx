import React, { useState, useEffect, useCallback, useMemo } from "react";
import { Form, Input, Flex, message } from "antd";
import { useForm } from "antd/lib/form/Form";
import { api } from "../../../api/api.ts";
import { useNotificationService } from "../../../hooks/useNotificationService.tsx";
import { downloadFile } from "../../../misc/download-utils.ts";
import { MaasFormActions } from "./MaasFormActions.tsx";
import { MaasPageHeader } from "./MaasPageHeader.tsx";
import { NamespaceField } from "./NamespaceField.tsx";
import {
  KafkaMaasFormData,
  NON_WHITESPACE_PATTERN,
  KAFKA_FIELD_NAMES,
  getMaasDefaultNamespace,
  isKafkaFormValid,
} from "./types.ts";
import sharedStyles from "../DevTools.module.css";
import styles from "./Maas.module.css";

export const KafkaMaasPage: React.FC = () => {
  const [form] = useForm<KafkaMaasFormData>();
  const [exportInProgress, setExportInProgress] = useState(false);
  const [createInProgress, setCreateInProgress] = useState(false);
  const notificationService = useNotificationService();

  const formValues = Form.useWatch((values) => values, form) as
    | Partial<KafkaMaasFormData>
    | undefined;

  const isFormValid = useMemo(() => isKafkaFormValid(formValues), [formValues]);

  const getInitialFormValues = useCallback(
    (): Partial<KafkaMaasFormData> => ({
      [KAFKA_FIELD_NAMES.NAMESPACE]: getMaasDefaultNamespace(),
      [KAFKA_FIELD_NAMES.TOPIC_CLASSIFIER_NAME]: "",
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
        KAFKA_FIELD_NAMES.TOPIC_CLASSIFIER_NAME,
      ]);
      setExportInProgress(true);
      const file = await api.getMaasKafkaDeclarativeFile({
        topicClassifierName: values.topicClassifierName,
      });
      downloadFile(file);
      message.success("Declarative file downloaded.");
    } catch (error) {
      notificationService.requestFailed(
        "Unable to export Kafka declarative file.",
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
      await api.createMaasKafkaEntity({
        namespace: values.namespace,
        topicClassifierName: values.topicClassifierName,
      });
      message.success(
        `MaaS Kafka entity created successfully: Namespace=[${values.namespace}] Topic Classifier=[${values.topicClassifierName}]`,
      );
    } catch (error) {
      notificationService.requestFailed(
        "Unable to create MaaS entity with given values.",
        error,
      );
    } finally {
      setCreateInProgress(false);
    }
  }, [form, notificationService]);

  return (
    <Flex vertical className={sharedStyles["container"]}>
      <MaasPageHeader
        title="Kafka - MaaS"
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
            label="Topic Classifier Name"
            name={KAFKA_FIELD_NAMES.TOPIC_CLASSIFIER_NAME}
            required
            rules={[
              { required: true, message: "Topic Classifier Name is required" },
              {
                pattern: NON_WHITESPACE_PATTERN,
                message: "Topic Classifier Name cannot be empty",
              },
            ]}
          >
            <Input placeholder="Enter topic classifier name" />
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
