import { useState } from "react";
import { Flex, FloatButton, message, Modal, Typography } from "antd";
import {
  PlusOutlined,
  DeleteOutlined,
  TableOutlined,
  MoreOutlined,
  CloudUploadOutlined,
  CloudDownloadOutlined,
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
import FloatButtonGroup from "antd/lib/float-button/FloatButtonGroup";

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
      <Flex vertical={false}>
        <Title level={4} className={styles["common-variables-title"]}>
          <TableOutlined className={styles["common-variables-icon"]} />
          Common Variables
        </Title>
      </Flex>
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
      <FloatButtonGroup trigger="hover" icon={<MoreOutlined />}>
        <FloatButton
          tooltip={{ title: "Import variables", placement: "left" }}
          icon={<CloudUploadOutlined />}
          onClick={() =>
            showModal({
              component: <ImportVariablesModal onSuccess={fetchVariables} />,
            })
          }
        />
        <FloatButton
          tooltip={{ title: "Export selected variables", placement: "left" }}
          icon={<CloudDownloadOutlined />}
          onClick={async () => {
            if (!selectedRowKeys.length) return;
            await onExport(selectedRowKeys);
          }}
        />
        <FloatButton
          tooltip={{
            title: "Delete selected variables",
            placement: "left",
          }}
          icon={<DeleteOutlined />}
          onClick={() => {
            if (!selectedRowKeys.length) return;
            Modal.confirm({
              title: `Delete ${selectedRowKeys.length} selected variable(s)?`,
              content: `Are you sure you want to delete ${selectedRowKeys.length} variables(s)?`,
              onOk: onDeleteSelected,
            });
          }}
        />
        <FloatButton
          tooltip={{ title: "Add variable", placement: "left" }}
          icon={<PlusOutlined />}
          onClick={() => setIsAddingNew(true)}
        />
      </FloatButtonGroup>
    </Flex>
  );
};
