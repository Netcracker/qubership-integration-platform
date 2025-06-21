import React, { useEffect, useState, useCallback } from "react";
import {
  Typography,
  Button,
  Input,
  Form,
  message,
  Modal,
  Popconfirm,
  Table,
  Tag,
  Flex,
} from "antd";
import {
  PlusOutlined,
  DeleteOutlined,
  DownloadOutlined,
  LockOutlined,
} from "@ant-design/icons";

import styles from "./SecuredVariables.module.css";
import VariablesTable from "./VariablesTable";
import { variablesApi } from "../../../api/admin-tools/variables/variablesApi.ts";
import {
  SecretWithVariables,
  Variable,
} from "../../../api/admin-tools/variables/types.ts";
import { downloadFile } from "../../../misc/download-utils.ts";
import { useNotificationService } from "../../../hooks/useNotificationService.tsx";
import { ResizeCallbackData } from "react-resizable";

const { Title } = Typography;

const SecuredVariables: React.FC = () => {
  const [secrets, setSecrets] = useState<string[]>([]);
  const [defaultSecret, setDefaultSecret] = useState<string>("");
  const [variables, setVariables] = useState<Record<string, Variable[]>>({});
  const [editing, setEditing] = useState<{
    secret: string;
    key: string;
  } | null>(null);
  const [editingValue, setEditingValue] = useState<string>("");
  const [newVariableKeys, setNewVariableKeys] = useState<
    Record<string, boolean>
  >({});
  const [selectedKeys, setSelectedKeys] = useState<Record<string, string[]>>(
    {},
  );
  const [createModalVisible, setCreateModalVisible] = useState(false);
  const [createForm] = Form.useForm();
  const [columnsWidth, setColumnsWidth] = useState<{ [key: string]: number }>({
    key: 300,
    value: 600,
  });
  const notificationService = useNotificationService();

  const handleResize = useCallback(
    (dataIndex: string) =>
      (_: unknown, { size }: ResizeCallbackData) => {
        setColumnsWidth((prev) => ({
          ...prev,
          [dataIndex]: size.width,
        }));
      },
    [setColumnsWidth],
  );

  const refreshSecretVariables = useCallback(
    async (secret: string): Promise<boolean> => {
      const response = await variablesApi.getSecuredVariablesForSecret(secret);
      if (response.success && response.data) {
        setVariables((prev) => ({
          ...prev,
          [secret]: response.data as Variable[],
        }));
        return true;
      } else {
        console.error(
          `Failed to refresh variables for secret ${secret}:`,
          response.error,
        );
        notificationService.requestFailed(
          response.error?.responseBody.errorMessage ||
            "Failed to refresh list.",
          null,
        );
        return false;
      }
    },
    [variablesApi.getSecuredVariablesForSecret, setVariables, message],
  );

  const loadSecrets = useCallback(async () => {
    const response = await variablesApi.getSecuredVariables();

    if (response.success && response.data) {
      const secretsWithVariables: SecretWithVariables[] = response.data;
      const sorted = secretsWithVariables.sort(
        (a: SecretWithVariables, b: SecretWithVariables) => {
          if (a.isDefaultSecret) return -1;
          if (b.isDefaultSecret) return 1;
          return 0;
        },
      );

      const bySecret: Record<string, Variable[]> = {};
      const secretNames: string[] = [];
      let defaultName = "";

      sorted.forEach(({ secretName, variables, isDefaultSecret }) => {
        bySecret[secretName] = variables;
        secretNames.push(secretName);
        if (isDefaultSecret) defaultName = secretName;
      });

      setSecrets(secretNames);
      setVariables(bySecret);
      setDefaultSecret(defaultName);
    } else {
      console.error("Failed to load secrets:", response.error);
      notificationService.requestFailed(
        response.error?.responseBody.errorMessage || "Failed to load secrets.",
        null,
      );
    }
  }, [
    variablesApi.getSecuredVariables,
    setSecrets,
    setVariables,
    setDefaultSecret,
    message,
  ]);

  useEffect(() => {
    loadSecrets();
  }, [loadSecrets]);

  const handleCreateSecret = useCallback(
    async (values: { secretName: string }) => {
      const name = values.secretName.trim();
      if (!name) return;
      const response = await variablesApi.createSecret(name);
      if (response.success) {
        message.success(`Secret "${name}" created`);
        setCreateModalVisible(false);
        createForm.resetFields();
        loadSecrets();
      } else {
        console.error("Failed to create secret:", response.error);
        notificationService.requestFailed(
          response.error?.responseBody.errorMessage ||
            "Failed to create secret.",
          null,
        );
      }
    },
    [
      variablesApi.createSecret,
      message,
      setCreateModalVisible,
      createForm,
      loadSecrets,
    ],
  );

  const handleAddVariable = useCallback(
    async (secret: string, key: string, value: string) => {
      if (!key || !value) return;
      const response = await variablesApi.createSecuredVariables(secret, [
        { key, value },
      ]);
      if (response.success) {
        const refreshed = await refreshSecretVariables(secret);
        if (refreshed) {
          setNewVariableKeys((prev) => ({ ...prev, [secret]: false }));
          message.success("Variable added");
        }
      } else {
        notificationService.requestFailed(
          response.error?.responseBody.errorMessage ||
            "Failed to add variable.",
          null,
        );
      }
    },
    [
      variablesApi.createSecuredVariables,
      message,
      refreshSecretVariables,
      setNewVariableKeys,
    ],
  );

  const handleUpdateVariable = useCallback(
    async (secret: string, key: string, value: string) => {
      const response = await variablesApi.updateSecuredVariables(secret, [
        { key, value },
      ]);
      if (response.success) {
        const refreshed = await refreshSecretVariables(secret);
        if (refreshed) {
          setEditing(null);
          setEditingValue("");
          message.success("Value updated");
        }
      } else {
        notificationService.requestFailed(
          response.error?.responseBody.errorMessage ||
            "Failed to update variable.",
          null,
        );
      }
    },
    [
      variablesApi.updateSecuredVariables,
      message,
      refreshSecretVariables,
      setEditing,
      setEditingValue,
    ],
  );

  const handleDeleteVariable = useCallback(
    async (secret: string, key: string) => {
      const response = await variablesApi.deleteSecuredVariables(secret, [key]);
      if (response.success) {
        const refreshed = await refreshSecretVariables(secret);
        if (refreshed) {
          message.success("Variable deleted");
        }
      } else {
        console.error("Failed to delete variable:", response.error);
        notificationService.requestFailed(
          response.error?.responseBody.errorMessage ||
            "Failed to delete variable.",
          null,
        );
      }
    },
    [variablesApi.deleteSecuredVariables, message, refreshSecretVariables],
  );

  const handleDeleteSelected = useCallback(async () => {
    const toDelete: Record<string, string[]> = {};

    Object.entries(selectedKeys).forEach(([secret, keys]) => {
      if (keys.length > 0) {
        toDelete[secret] = keys;
      }
    });

    if (Object.keys(toDelete).length === 0) return;

    try {
      let allSuccessful = true;
      for (const secret of Object.keys(toDelete)) {
        const response = await variablesApi.deleteSecuredVariables(
          secret,
          toDelete[secret],
        );
        if (!response.success) {
          allSuccessful = false;
          console.error(
            `Failed to delete variables for secret ${secret}:`,
            response.error,
          );
        }
      }

      if (allSuccessful) {
        loadSecrets();
      } else {
        notificationService.requestFailed(
          "Some selected variables failed to delete. Check console for details.",
          null,
        );
      }
    } catch (error) {
      console.error(
        "An unexpected error occurred during batch deletion:",
        error,
      );
      notificationService.requestFailed(
        "An unexpected error occurred during batch deletion:",
        error,
      );
    }
  }, [
    selectedKeys,
    variablesApi.deleteSecuredVariables,
    variablesApi.getSecuredVariables,
    setVariables,
    setSelectedKeys,
    message,
    loadSecrets,
  ]);

  const hasSelected = Object.values(selectedKeys).some(
    (keys) => keys.length > 0,
  );

  const calculateSecuredScrollHeight = useCallback(() => {
    const headerHeight = 40;
    return 300 - headerHeight;
  }, []);

  const expandedRowRender = useCallback(
    (secret: string) => (
      <VariablesTable
        variables={variables[secret] || []}
        selectedKeys={selectedKeys[secret] || []}
        isAddingNew={newVariableKeys[secret]}
        editingKey={editing?.secret === secret ? editing.key : null}
        editingValue={editing?.secret === secret ? editingValue : ""}
        onStartEditing={(key) => {
          setEditing({ secret, key });
          setEditingValue("");
        }}
        onChangeEditingValue={setEditingValue}
        onCancelEditing={() => {
          setEditing(null);
          setEditingValue("");
        }}
        onConfirmEdit={(key, value) => handleUpdateVariable(secret, key, value)}
        onDelete={(key) => handleDeleteVariable(secret, key)}
        onAdd={(key, value) => handleAddVariable(secret, key, value)}
        onSelectedChange={(keys) =>
          setSelectedKeys((prev) => ({ ...prev, [secret]: keys }))
        }
        isValueHidden
        enableKeySort
        enableValueSort={false}
        enableKeyFilter
        enableValueFilter={false}
        columnsWidth={columnsWidth}
        onResize={handleResize}
        calculateScrollHeight={calculateSecuredScrollHeight}
      />
    ),
    [
      variables,
      selectedKeys,
      newVariableKeys,
      editing,
      editingValue,
      columnsWidth,
      handleResize,
      calculateSecuredScrollHeight,
      handleUpdateVariable,
      handleDeleteVariable,
      handleAddVariable,
      setSelectedKeys,
    ],
  );

  return (
    <Flex vertical style={{ height: "100%" }}>
      <div className={styles["secured-variables-header"]}>
        <Title level={4} className={styles["secured-variables-title"]}>
          <LockOutlined />
          Secured Variables
        </Title>
        <div className={styles["secret-actions"]}>
          <Popconfirm
            title="Delete all selected variables?"
            onConfirm={handleDeleteSelected}
            okText="Yes"
            cancelText="No"
            disabled={!hasSelected}
          >
            <Button icon={<DeleteOutlined />} danger disabled={!hasSelected}>
              Delete Selected
            </Button>
          </Popconfirm>
          <Button type="primary" onClick={() => setCreateModalVisible(true)}>
            Create Secret
          </Button>
        </div>
      </div>

      <Modal
        title="Create New Secret"
        open={createModalVisible}
        onCancel={() => setCreateModalVisible(false)}
        footer={null}
      >
        <Form layout="vertical" form={createForm} onFinish={handleCreateSecret}>
          <Form.Item
            name="secretName"
            label="Secret Name"
            rules={[{ required: true, message: "Enter secret name" }]}
          >
            <Input placeholder="e.g., my-secret" />
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" block>
              Create
            </Button>
          </Form.Item>
        </Form>
      </Modal>

      <Table
        className="flex-table"
        dataSource={secrets.map((s) => ({ key: s, secret: s }))}
        columns={[
          {
            title: "Secret",
            dataIndex: "secret",
            key: "secret",
            render: (secret) => (
              <div className={styles["secret-content"]}>
                <div className="secret-label">
                  <span>{secret}</span>
                  {secret === defaultSecret && (
                    <Tag
                      color="green"
                      style={{
                        borderRadius: 12,
                        padding: "0 8px",
                        marginLeft: "8px",
                      }}
                    >
                      default
                    </Tag>
                  )}
                </div>
                <div className={styles["secret-actions"]}>
                  <Button
                    size="small"
                    icon={<DownloadOutlined />}
                    onClick={async () => {
                      try {
                        downloadFile(
                          await variablesApi.downloadHelmChart(secret),
                        );
                      } catch (error) {
                        notificationService.requestFailed(
                          "Failed to get helm chart",
                          error,
                        );
                      }
                    }}
                  >
                    HELM Chart
                  </Button>
                  <Button
                    icon={<PlusOutlined />}
                    size="small"
                    onClick={() =>
                      setNewVariableKeys((prev) => ({
                        ...prev,
                        [secret]: true,
                      }))
                    }
                  />
                </div>
              </div>
            ),
          },
        ]}
        expandable={{
          expandedRowRender: (record) => expandedRowRender(record.secret),
          rowExpandable: () => true,
        }}
        pagination={false}
        size="small"
        bordered={false}
        sticky
        scroll={{ y: "" }}
      />
    </Flex>
  );
};

export default SecuredVariables;
