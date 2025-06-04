import { useEffect, useState } from "react";
import { message } from "antd";
import { useNotificationService } from "../../../hooks/useNotificationService";
import { ApiResponse, Variable } from "../../../api/admin-tools/variables/types.ts";

export const NEW_VARIABLE_KEY = "__new__";

type UseVariablesStateParams = {
  getVariables: () => Promise<ApiResponse<Variable[]>>;
  createVariable: (v: Variable) => Promise<ApiResponse<string[]>>;
  updateVariable: (v: Variable) => Promise<ApiResponse<Variable>>;
  deleteVariables: (keys: string[]) => Promise<boolean>;
  exportVariables?: (keys: string[]) => Promise<boolean>;
};

export const useVariablesState = ({
                                    getVariables,
                                    createVariable,
                                    updateVariable,
                                    deleteVariables,
                                    exportVariables,
                                  }: UseVariablesStateParams) => {
  const [variables, setVariables] = useState<Variable[]>([]);
  const [editingKey, setEditingKey] = useState<string | null>(null);
  const [editingValue, setEditingValue] = useState("");
  const [isAddingNew, setIsAddingNew] = useState(false);

  const notificationService = useNotificationService();

  useEffect(() => {
    fetchVariables();
  }, []);

  const fetchVariables = async () => {
    try {
      const response = await getVariables();
      if (response.success) {
        setVariables(response.data as Variable[]);
      } else {
        notificationService.requestFailed("Failed to load variables", response.error);
      }
    } catch (error: any) {
      notificationService.requestFailed("Failed to load variables", error);
    }
  };

  const startEditing = (key: string, value: string) => {
    setEditingKey(key);
    setEditingValue(value);
  };

  const cancelEditing = () => {
    if (editingKey === null) {
      setIsAddingNew(false);
    }
    setEditingKey(null);
    setEditingValue("");
  };

  const confirmEdit = async (key: string, newValue: string) => {
    try {
      const variableToUpdate = { key, value: newValue };
      const response = await updateVariable(variableToUpdate);
      if (response.success) {
        message.success("Variable updated");
        cancelEditing();
        fetchVariables();
      } else {
        const errorMessage = response.error?.errorMessage || "Failed to update variable";
        notificationService.requestFailed(errorMessage, response.error);
      }
    } catch (error: any) {
      notificationService.requestFailed("Failed to update variable", error);
    }
  };

  const deleteVariable = async (key: string) => {
    try {
      const success = await deleteVariables([key]);
      if (success) {
        message.success(`Deleted ${key}`);
        fetchVariables();
      } else {
        notificationService.requestFailed(`Failed to delete ${key}`, { message: "Operation failed" });
      }
    } catch (error: any) {
      notificationService.requestFailed(`Failed to delete ${key}`, error);
    }
  };

  const addVariable = async (key: string, value: string) => {
    if (key === NEW_VARIABLE_KEY) {
      notificationService.info(`Cannot save variable with key '${NEW_VARIABLE_KEY}'`, "This key is reserved.");
      return;
    }
    try {
      const response = await createVariable({ key, value: value });
      if (response.success) {
        message.success("Variable added");
        setIsAddingNew(false);
        fetchVariables();
      } else {
        const errorMessage = response.error?.errorMessage || "Failed to add variable";
        notificationService.requestFailed(errorMessage, response.error);
      }
    } catch (error: any) {
      notificationService.requestFailed("Failed to add variable", error);
    }
  };

  const handleExport = async (keys: string[]) => {
    if (!exportVariables) return;
    try {
      await exportVariables(keys);
      message.success("Exported");
    } catch (error: any) {
      notificationService.requestFailed("Failed to export", error);
    }
  };

  return {
    variables,
    editingKey,
    editingValue,
    isAddingNew,
    setIsAddingNew,
    onStartEditing: startEditing,
    onChangeEditingValue: setEditingValue,
    onCancelEditing: cancelEditing,
    onConfirmEdit: confirmEdit,
    onDelete: deleteVariable,
    onAdd: addVariable,
    onExport: handleExport,
    fetchVariables,
  };
};
