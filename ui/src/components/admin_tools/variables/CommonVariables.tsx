import { useCallback, useState } from "react";
import { Flex, FloatButton, message, Modal, Typography } from "antd";
import {
  PlusOutlined,
  DeleteOutlined,
  TableOutlined,
  MoreOutlined,
  CloudUploadOutlined,
  CloudDownloadOutlined,
} from "@ant-design/icons";
import styles from "../CommonStyle.module.css";
import ImportVariablesModal from "./ImportVariablesModal.tsx";
import { useModalsContext } from "../../../Modals.tsx";
import VariablesTable from "./VariablesTable.tsx";
import { useVariablesState } from "./useVariablesState";
import { variablesApi } from "../../../api/admin-tools/variables/variablesApi.ts";
import { downloadFile } from "../../../misc/download-utils.ts";
import { useNotificationService } from "../../../hooks/useNotificationService.tsx";
import { ResizeCallbackData } from "react-resizable";
import FloatButtonGroup from "antd/lib/float-button/FloatButtonGroup";
import { ApiResponse, Variable } from "../../../api/admin-tools/variables/types.ts";

const { Title } = Typography;

async function getVariables(): Promise<ApiResponse<Variable[]>> {
  return variablesApi.getCommonVariables();
}

export const CommonVariables = () => {
  const { showModal } = useModalsContext();
  const [selectedRowKeys, setSelectedRowKeys] = useState<string[]>([]);
  const [columnsWidth, setColumnsWidth] = useState<{ [key: string]: number }>({
    key: 300,
  });
  const notificationService = useNotificationService();

  const handleResize = useCallback(
    (dataIndex: string) =>
      (_: unknown, { size }: ResizeCallbackData) => {
        requestAnimationFrame(() => {
          setColumnsWidth((prev) => ({
            ...prev,
            [dataIndex]: size.width,
          }));
        });
      },
    [],
  );

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
    getVariables,
    createVariable: (variable) => variablesApi.createCommonVariable(variable),
    updateVariable: (variable) => variablesApi.updateCommonVariable(variable),
    deleteVariables: (keys) => variablesApi.deleteCommonVariables(keys),
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
        await fetchVariables();
      } else {
        message.error("Failed to delete selected variables");
      }
    } catch (error) {
      console.error("Error deleting selected variables:", error);
      message.error("Failed to delete selected variables");
    }
  };

  return (
    <Flex vertical className={styles["container"]}>
      <Flex vertical={false}>
        <Title level={4} className={styles["title"]}>
          <TableOutlined className={styles["icon"]} />
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
        onConfirmEdit={(key, value) => void onConfirmEdit(key, value)}
        onDelete={(key) => void onDelete(key)}
        onAdd={(key, value) => void onAdd(key, value)}
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
              component: (
                <ImportVariablesModal onSuccess={() => void fetchVariables()} />
              ),
            })
          }
        />
        <FloatButton
          tooltip={{ title: "Export selected variables", placement: "left" }}
          icon={<CloudDownloadOutlined />}
          onClick={() => {
            if (!selectedRowKeys.length) return;
            void onExport(selectedRowKeys);
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
