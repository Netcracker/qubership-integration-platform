import React, { useEffect, useState, useCallback, useMemo } from "react";
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
  Tooltip,
  type TableProps,
} from "antd";
import commonStyles from "../CommonStyle.module.css";
import styles from "./SecuredVariables.module.css";
import VariablesTable from "./VariablesTable";
import { SecretWithVariables, Variable } from "../../../api/apiTypes.ts";
import { downloadFile } from "../../../misc/download-utils.ts";
import { useNotificationService } from "../../../hooks/useNotificationService.tsx";
import { LongActionButton } from "../../LongActionButton.tsx";
import { OverridableIcon } from "../../../icons/IconProvider.tsx";
import { treeExpandIcon } from "../../table/TreeExpandIcon.tsx";
import { api } from "../../../api/api.ts";
import { ProtectedButton } from "../../../permissions/ProtectedButton.tsx";
import { Require } from "../../../permissions/Require.tsx";
import { usePermissions } from "../../../permissions/usePermissions.tsx";
import { hasPermissions } from "../../../permissions/funcs.ts";
import {
  attachResizeToColumns,
  useTableColumnResize,
} from "../../table/useTableColumnResize.tsx";
import { CompactSearch } from "../../table/CompactSearch.tsx";

const { Title } = Typography;
type SecretRow = { key: string; secret: string };

function secretNameMatchesSearch(secretName: string, term: string): boolean {
  const t = term.trim().toLowerCase();
  if (!t) return true;
  return secretName.toLowerCase().includes(t);
}

export const SecuredVariables: React.FC = () => {
  const [searchTerm, setSearchTerm] = useState("");
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
  const notificationService = useNotificationService();
  const permissions = usePermissions();
  const [expandedRowKeys, setExpandedRowKeys] = useState<React.Key[]>([]);

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
      const sorted = [...secretsWithVariables];
      sorted.sort((a: SecretWithVariables, b: SecretWithVariables) => {
        if (a.isDefaultSecret) return -1;
        if (b.isDefaultSecret) return 1;
        return 0;
      });

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
      if (!key) return;
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
        onStartEditing={(key, value) => {
          setEditing({ secret, key });
          setEditingValue(value);
        }}
        onChangeEditingValue={setEditingValue}
        onCancelEditing={() => {
          if (editing) {
            setEditing(null);
            setEditingValue("");
          } else {
            setNewVariableKeys((prev) => {
              const state = { ...prev };
              delete state[secret];
              return state;
            });
          }
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
        calculateScrollHeight={calculateSecuredScrollHeight}
        enableEdit={hasPermissions(permissions, {
          securedVariable: ["update"],
        })}
        enableDelete={hasPermissions(permissions, {
          securedVariable: ["delete"],
        })}
      />
    ),
    [
      variables,
      selectedKeys,
      newVariableKeys,
      editing,
      editingValue,
      calculateSecuredScrollHeight,
      permissions,
      handleUpdateVariable,
      handleDeleteVariable,
      handleAddVariable,
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

  const openNewVariableEditor = useCallback((secret: string) => {
    setNewVariableKeys((prev) => ({
      ...prev,
      [secret]: true,
    }));
  }, []);

  const secretListColumnResize = useTableColumnResize({
    secret: 520,
  });

  const secretListColumns = useMemo<TableProps<SecretRow>["columns"]>(
    () => [
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
              <Require permissions={{ secret: ["export"] }}>
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
              </Require>
              <ProtectedButton
                require={{ securedVariable: ["create"] }}
                tooltipProps={{
                  placement: "topRight",
                  title: "Add variable",
                }}
                buttonProps={{
                  iconName: "plus",
                  size: "small",
                  type: "text",
                  onClick: () => openNewVariableEditor(secret),
                }}
              />
            </div>
          </div>
        ),
      },
    ],
    [defaultSecret, exportHelmChart, openNewVariableEditor],
  );

  const secretListColumnsResized = useMemo(
    () =>
      attachResizeToColumns(
        secretListColumns,
        secretListColumnResize.columnWidths,
        secretListColumnResize.createResizeHandlers,
        { minWidth: 80 },
      ),
    [
      secretListColumns,
      secretListColumnResize.columnWidths,
      secretListColumnResize.createResizeHandlers,
    ],
  );

  const filteredSecrets = useMemo(
    () => secrets.filter((s) => secretNameMatchesSearch(s, searchTerm)),
    [secrets, searchTerm],
  );

  return (
    <Flex vertical className={commonStyles["container"]}>
      <Flex
        vertical={false}
        justify="space-between"
        align="center"
        gap={8}
        wrap="wrap"
        style={{ marginBottom: 16 }}
      >
        <Title level={4} className={commonStyles["title"]}>
          <OverridableIcon name="lock" className={commonStyles["icon"]} />
          Secured Variables
        </Title>
        <Flex vertical={false} align="center" gap={8} wrap="wrap">
          <CompactSearch
            value={searchTerm}
            onChange={setSearchTerm}
            placeholder="Search secrets..."
            allowClear
            className={commonStyles["searchField"] as string}
          />
          <Flex vertical={false} gap={4}>
            <ProtectedButton
              require={{ securedVariable: ["delete"] }}
              tooltipProps={{
                title: "Delete selected variables",
                placement: "bottom",
              }}
              buttonProps={{
                iconName: "delete",
                onClick: () => {
                  if (!hasSelected) return;
                  Modal.confirm({
                    title: `Delete selected variable(s)?`,
                    content: `Are you sure you want to delete variables(s)?`,
                    onOk: handleDeleteSelected,
                  });
                },
                disabled: !hasSelected,
              }}
            />
            <ProtectedButton
              require={{ secret: ["create"] }}
              tooltipProps={{ title: "Add secret", placement: "bottom" }}
              buttonProps={{
                iconName: "plus",
                onClick: () => setCreateModalVisible(true),
              }}
            />
          </Flex>
        </Flex>
      </Flex>

      <Modal
        title="Create Secret"
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
            label="Name"
            rules={[
              {
                required: true,
                pattern: /^[a-z]+[-a-z0-9]*$/,
                message:
                  'Please use lower case without special characters. If necessary, use "-" as a separator.',
              },
            ]}
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

      <Table<SecretRow>
        className="flex-table"
        dataSource={filteredSecrets.map((s) => ({ key: s, secret: s }))}
        columns={secretListColumnsResized}
        expandable={{
          expandIcon: treeExpandIcon(),
          expandedRowRender: (record) => expandedRowRender(record.secret),
          rowExpandable: () => true,
          expandedRowKeys,
          onExpandedRowsChange: (rowKeys) => {
            setExpandedRowKeys([...rowKeys]);
          },
        }}
        pagination={false}
        size="small"
        bordered={false}
        sticky
        scroll={{
          x: secretListColumnResize.totalColumnsWidth,
          y: "",
        }}
        components={secretListColumnResize.resizableHeaderComponents}
      />
    </Flex>
  );
};
