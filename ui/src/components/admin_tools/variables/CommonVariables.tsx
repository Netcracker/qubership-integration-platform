import { useMemo, useState } from "react";
import { Flex, message, Modal } from "antd";
import styles from "../CommonStyle.module.css";
import ImportVariablesModal from "./ImportVariablesModal.tsx";
import { useModalsContext } from "../../../Modals.tsx";
import VariablesTable from "./VariablesTable.tsx";
import { useVariablesState } from "./useVariablesState";
import { downloadFile } from "../../../misc/download-utils.ts";
import { useNotificationService } from "../../../hooks/useNotificationService.tsx";
import { ApiResponse, Variable } from "../../../api/apiTypes.ts";
import { api } from "../../../api/api.ts";
import { ProtectedButton } from "../../../permissions/ProtectedButton.tsx";
import { hasPermissions } from "../../../permissions/funcs.ts";
import { usePermissions } from "../../../permissions/usePermissions.tsx";
import { AdminToolsHeader } from "../AdminToolsHeader.tsx";
import { TableToolbar } from "../../table/TableToolbar.tsx";

async function getVariables(): Promise<ApiResponse<Variable[]>> {
  return api.getCommonVariables();
}

function variableMatchesSearch(v: Variable, term: string): boolean {
  const t = term.trim().toLowerCase();
  if (!t) return true;
  return v.key.toLowerCase().includes(t) || v.value.toLowerCase().includes(t);
}

export const CommonVariables = () => {
  const { showModal } = useModalsContext();
  const [searchTerm, setSearchTerm] = useState("");
  const [selectedRowKeys, setSelectedRowKeys] = useState<string[]>([]);
  const notificationService = useNotificationService();
  const permissions = usePermissions();

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
    createVariable: (variable) => api.createCommonVariable(variable),
    updateVariable: (variable) => api.updateCommonVariable(variable),
    deleteVariables: (keys) => api.deleteCommonVariables(keys),
    exportVariables: async (keys) => {
      try {
        downloadFile(await api.exportVariables(keys, true));
        return true;
      } catch (error) {
        notificationService.requestFailed("Failed to export variables", error);
        return false;
      }
    },
  });

  const filteredVariables = useMemo(
    () => variables.filter((v) => variableMatchesSearch(v, searchTerm)),
    [variables, searchTerm],
  );

  const onDeleteSelected = async () => {
    try {
      const success = await api.deleteCommonVariables(selectedRowKeys);
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
      <AdminToolsHeader
        title="Common Variables"
        iconName="table"
        toolbar={
          <TableToolbar
            variant="admin"
            search={{
              value: searchTerm,
              onChange: setSearchTerm,
              placeholder: "Search variables...",
              allowClear: true,
            }}
            actions={
              <>
                <ProtectedButton
                  require={{ commonVariable: ["delete"] }}
                  tooltipProps={{
                    title: "Delete selected variables",
                    placement: "bottom",
                  }}
                  buttonProps={{
                    iconName: "delete",
                    onClick: () => {
                      if (!selectedRowKeys.length) return;
                      Modal.confirm({
                        title: `Delete ${selectedRowKeys.length} selected variable(s)?`,
                        content: `Are you sure you want to delete ${selectedRowKeys.length} variables(s)?`,
                        onOk: onDeleteSelected,
                      });
                    },
                    disabled: !selectedRowKeys.length,
                  }}
                />
                <ProtectedButton
                  require={{ commonVariable: ["export"] }}
                  tooltipProps={{
                    title: "Export selected variables",
                    placement: "bottom",
                  }}
                  buttonProps={{
                    iconName: "cloudDownload",
                    onClick: () => {
                      if (!selectedRowKeys.length) return;
                      void onExport(selectedRowKeys);
                    },
                    disabled: !selectedRowKeys.length,
                  }}
                />
                <ProtectedButton
                  require={{ commonVariable: ["import"] }}
                  tooltipProps={{
                    title: "Import variables",
                    placement: "bottom",
                  }}
                  buttonProps={{
                    iconName: "cloudUpload",
                    onClick: () =>
                      showModal({
                        component: (
                          <ImportVariablesModal
                            onSuccess={() => void fetchVariables()}
                          />
                        ),
                      }),
                  }}
                />
                <ProtectedButton
                  require={{ commonVariable: ["create"] }}
                  tooltipProps={{ title: "Add variable", placement: "bottom" }}
                  buttonProps={{
                    type: "primary",
                    iconName: "plus",
                    onClick: () => setIsAddingNew(true),
                  }}
                />
              </>
            }
          />
        }
      />
      <VariablesTable
        flex
        variables={filteredVariables}
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
        enableEdit={hasPermissions(permissions, { commonVariable: ["update"] })}
        enableDelete={hasPermissions(permissions, {
          commonVariable: ["delete"],
        })}
      />
    </Flex>
  );
};
