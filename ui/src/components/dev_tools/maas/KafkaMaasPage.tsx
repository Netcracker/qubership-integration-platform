import React, { useState, useEffect, useCallback, useMemo } from "react";
import { Form, Input, Flex, message } from "antd";
import { useForm } from "antd/lib/form/Form";
import { api } from "../../../api/api.ts";
import { useNotificationService } from "../../../hooks/useNotificationService.tsx";
import { downloadFile } from "../../../misc/download-utils.ts";
import { MaasFormActions } from "./MaasFormActions.tsx";
import { MaasPageHeader } from "./MaasPageHeader.tsx";
import { NamespaceField } from "./NamespaceField.tsx";
import { KafkaMaasFormData, NON_WHITESPACE_PATTERN, getMaasDefaultNamespace } from "./types.ts";
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

  const isFormValid = useMemo(() => {
    const namespace = formValues?.namespace;
    const topicClassifierName = formValues?.topicClassifierName;
    return (
      !!namespace &&
      !!topicClassifierName &&
      NON_WHITESPACE_PATTERN.test(namespace) &&
      NON_WHITESPACE_PATTERN.test(topicClassifierName)
    );
  }, [formValues]);

  useEffect(() => {
    form.setFieldsValue({
      namespace: getMaasDefaultNamespace(),
      topicClassifierName: "",
    });
  }, [form]);

  const handleReset = useCallback(() => {
    form.setFieldsValue({
      namespace: getMaasDefaultNamespace(),
      topicClassifierName: "",
    });
  }, [form]);

  const handleExport = useCallback(async () => {
    try {
      const values = await form.validateFields(["topicClassifierName"]);
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
        onExport={handleExport}
      />

      <div className={styles["parametersSection"]}>
        <div className={styles["parametersHeading"]}>
          Parameters
        </div>
        <Form
          form={form}
          layout="vertical"
          onFinish={handleCreate}
        >
          <NamespaceField />

          <Form.Item
            label="Topic Classifier Name"
            name="topicClassifierName"
            required
            rules={[
              { required: true, message: "Topic Classifier Name is required" },
              { pattern: NON_WHITESPACE_PATTERN, message: "Topic Classifier Name cannot be empty" },
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
          onCreate={handleCreate}
          onReset={handleReset}
        />
      </div>
    </Flex>
  );
};
