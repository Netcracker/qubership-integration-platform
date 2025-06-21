import { useState } from "react";
import { Button, Flex, message, Popconfirm, Typography } from "antd";
import {
  UploadOutlined,
  DownloadOutlined,
  PlusOutlined,
  DeleteOutlined,
  TableOutlined,
} from "@ant-design/icons";
import styles from "./CommonVariables.module.css";
import ImportVariablesModal from "./ImportVariablesModal.tsx";
import { useModalsContext } from "../../../Modals.tsx";
import VariablesTable from "./VariablesTable.tsx";
import { useVariablesState } from "./useVariablesState";
import { variablesApi } from "../../../api/admin-tools/variables/variablesApi.ts";
import { downloadFile } from "../../../misc/download-utils.ts";
import { useNotificationService } from "../../../hooks/useNotificationService.tsx";
import { ResizeCallbackData } from "react-resizable";

const { Title } = Typography;

export const CommonVariables = () => {
  const { showModal } = useModalsContext();
  const [selectedRowKeys, setSelectedRowKeys] = useState<string[]>([]);
  const [columnsWidth, setColumnsWidth] = useState<{ [key: string]: number }>({
    key: 300,
    value: 600,
  });
  const notificationService = useNotificationService();

  const handleResize =
    (dataIndex: string) =>
    (_: unknown, { size }: ResizeCallbackData) => {
      requestAnimationFrame(() => {
        setColumnsWidth((prev) => ({
          ...prev,
          [dataIndex]: size.width,
        }));
      });
    };

  const {
    variables,
    isAddingNew,
    editingKey,
    editingValue,
    setIsAddingNew,
    onStartEditing,
    onChangeEditingValue,
    onCancelEditing,
    onConfirmEdit,
    onDelete,
    onAdd,
    onExport,
    fetchVariables,
  } = useVariablesState({
    getVariables: variablesApi.getCommonVariables,
    createVariable: variablesApi.createCommonVariable,
    updateVariable: variablesApi.updateCommonVariable,
    deleteVariables: variablesApi.deleteCommonVariables,
    exportVariables: async (keys) => {
      try {
        downloadFile(await variablesApi.exportVariables(keys, true));
        return true;
      } catch (error) {
        notificationService.requestFailed("Failed to export variables", error);
        return false;
      }
    },
  });

  const onDeleteSelected = async () => {
    try {
      const success = await variablesApi.deleteCommonVariables(selectedRowKeys);
      if (success) {
        message.success("Deleted selected variables");
        setSelectedRowKeys([]);
        fetchVariables();
      } else {
        message.error("Failed to delete selected variables");
      }
    } catch (error) {
      console.error("Error deleting selected variables:", error);
      message.error("Failed to delete selected variables");
    }
  };

  return (
    <Flex vertical style={{ height: "100%" }}>
      <div className={styles["common-variables-header"]}>
        <Title level={4} className={styles["common-variables-title"]}>
          <TableOutlined className={styles["common-variables-icon"]} />
          Common Variables
        </Title>

        <div className={styles["common-variables-actions"]}>
          <Button
            icon={<UploadOutlined />}
            onClick={() =>
              showModal({
                component: <ImportVariablesModal onSuccess={fetchVariables} />,
              })
            }
          >
            Import
          </Button>
          <Button
            icon={<DownloadOutlined />}
            onClick={() => onExport(selectedRowKeys)}
            disabled={!selectedRowKeys.length}
          >
            Export
          </Button>
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => setIsAddingNew(true)}
            disabled={isAddingNew}
          >
            Add Variable
          </Button>
          <Popconfirm
            title={`Delete ${selectedRowKeys.length} selected variable(s)?`}
            onConfirm={onDeleteSelected}
            okText="Yes"
            cancelText="No"
            disabled={!selectedRowKeys.length}
          >
            <Button
              danger
              icon={<DeleteOutlined />}
              disabled={!selectedRowKeys.length}
            >
              Delete Selected
            </Button>
          </Popconfirm>
        </div>
      </div>
      <VariablesTable
        flex
        variables={variables}
        isAddingNew={isAddingNew}
        selectedKeys={selectedRowKeys}
        onSelectedChange={setSelectedRowKeys}
        editingKey={editingKey}
        editingValue={editingValue}
        onStartEditing={onStartEditing}
        onChangeEditingValue={onChangeEditingValue}
        onCancelEditing={onCancelEditing}
        onConfirmEdit={onConfirmEdit}
        onDelete={onDelete}
        onAdd={onAdd}
        enableKeySort
        enableValueSort
        enableKeyFilter
        enableValueFilter
        isValueHidden={false}
        columnsWidth={columnsWidth}
        onResize={handleResize}
      />
    </Flex>
  );
};
