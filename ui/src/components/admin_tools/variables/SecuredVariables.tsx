import React, { useEffect, useState, useCallback } from "react";
import {
  Typography,
  Button,
  Input,
  Form,
  message,
  Modal,
  Table,
  Tag,
  Flex,
  FloatButton,
  Tooltip,
} from "antd";
import commonStyles from "../CommonStyle.module.css";
import styles from "./SecuredVariables.module.css";
import VariablesTable from "./VariablesTable";
import { SecretWithVariables, Variable } from "../../../api/apiTypes.ts";
import { downloadFile } from "../../../misc/download-utils.ts";
import { useNotificationService } from "../../../hooks/useNotificationService.tsx";
import { ResizeCallbackData } from "react-resizable";
import FloatButtonGroup from "antd/lib/float-button/FloatButtonGroup";
import { LongActionButton } from "../../LongActionButton.tsx";
import { OverridableIcon } from "../../../icons/IconProvider.tsx";
import { api } from "../../../api/api.ts";

const { Title } = Typography;

export const SecuredVariables: React.FC = () => {
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
      const response = await api.getSecuredVariablesForSecret(secret);
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
    [notificationService],
  );

  const loadSecrets = useCallback(async () => {
    const response = await api.getSecuredVariables();

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
  }, [notificationService]);

  useEffect(() => {
    void loadSecrets();
  }, [loadSecrets]);

  const handleCreateSecret = useCallback(
    async (values: { secretName: string }) => {
      const name = values.secretName.trim();
      if (!name) return;
      const response = await api.createSecret(name);
      if (response.success) {
        message.success(`Secret "${name}" created`);
        setCreateModalVisible(false);
        createForm.resetFields();
        await loadSecrets();
      } else {
        console.error("Failed to create secret:", response.error);
        notificationService.requestFailed(
          response.error?.responseBody.errorMessage ||
            "Failed to create secret.",
          null,
        );
      }
    },
    [createForm, loadSecrets, notificationService],
  );

  const handleAddVariable = useCallback(
    async (secret: string, key: string, value: string) => {
      if (!key || !value) return;
      const response = await api.createSecuredVariables(secret, [
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
    [refreshSecretVariables, notificationService],
  );

  const handleUpdateVariable = useCallback(
    async (secret: string, key: string, value: string) => {
      const response = await api.updateSecuredVariables(secret, [
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
    [refreshSecretVariables, notificationService],
  );

  const handleDeleteVariable = useCallback(
    async (secret: string, key: string) => {
      const response = await api.deleteSecuredVariables(secret, [key]);
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
    [refreshSecretVariables, notificationService],
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
        const response = await api.deleteSecuredVariables(
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
        await loadSecrets();
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
  }, [selectedKeys, loadSecrets, notificationService]);

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
        onConfirmEdit={(key, value) =>
          void handleUpdateVariable(secret, key, value)
        }
        onDelete={(key) => void handleDeleteVariable(secret, key)}
        onAdd={(key, value) => void handleAddVariable(secret, key, value)}
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

  const exportHelmChart = useCallback(
    async (secret: string) => {
      try {
        downloadFile(await api.downloadHelmChart(secret));
      } catch (error) {
        notificationService.requestFailed("Failed to get helm chart", error);
      }
    },
    [notificationService],
  );

  return (
    <Flex vertical className={commonStyles["container"]}>
      <Flex vertical={false}>
        <Title level={4} className={commonStyles["title"]}>
          <OverridableIcon name="lock" className={commonStyles["icon"]} />
          Secured Variables
        </Title>
      </Flex>

      <Modal
        title="Create New Secret"
        open={createModalVisible}
        onCancel={() => setCreateModalVisible(false)}
        footer={null}
      >
        <Form<{ secretName: string }>
          layout="vertical"
          form={createForm}
          onFinish={(values) => void handleCreateSecret(values)}
        >
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

      <Table<{ key: string; secret: string }>
        className="flex-table"
        dataSource={secrets.map((s) => ({ key: s, secret: s }))}
        columns={[
          {
            title: "Secret",
            dataIndex: "secret",
            key: "secret",
            render: (secret: string) => (
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
                <div>
                  <Tooltip
                    placement="topRight"
                    title="Export secret as Helm Chart"
                  >
                    <LongActionButton
                      size="small"
                      type="text"
                      icon={<OverridableIcon name="cloudDownload" />}
                      onSubmit={async () => exportHelmChart(secret)}
                    />
                  </Tooltip>
                  <Tooltip placement="topRight" title="Add variable">
                    <Button
                      icon={<OverridableIcon name="plus" />}
                      size="small"
                      type="text"
                      onClick={() =>
                        setNewVariableKeys((prev) => ({
                          ...prev,
                          [secret]: true,
                        }))
                      }
                    />
                  </Tooltip>
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
      <FloatButtonGroup trigger="hover" icon={<OverridableIcon name="more" />}>
        <FloatButton
          tooltip={{
            title: "Delete selected variables",
            placement: "left",
          }}
          icon={<OverridableIcon name="delete" />}
          onClick={() => {
            if (!hasSelected) return;
            Modal.confirm({
              title: `Delete selected variable(s)?`,
              content: `Are you sure you want to delete variables(s)?`,
              onOk: handleDeleteSelected,
            });
          }}
        />
        <FloatButton
          tooltip={{ title: "Add secret", placement: "left" }}
          icon={<OverridableIcon name="plus" />}
          onClick={() => setCreateModalVisible(true)}
        />
      </FloatButtonGroup>
    </Flex>
  );
};
