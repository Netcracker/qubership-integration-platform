import { useCallback, useEffect, useState } from "react";
import { message } from "antd";
import { useNotificationService } from "../../../hooks/useNotificationService";
import { ApiResponse, Variable } from "../../../api/apiTypes.ts";

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

  const fetchVariables = useCallback(async () => {
    try {
      const response = await getVariables();
      if (response.success) {
        setVariables(response.data as Variable[]);
      } else {
        notificationService.requestFailed(
          "Failed to load variables",
          response.error,
        );
      }
    } catch (error) {
      notificationService.requestFailed("Failed to load variables", error);
    }
  }, [getVariables, notificationService]);

  useEffect(() => {
    void fetchVariables();
  }, [fetchVariables]);

  const startEditing = useCallback((key: string, value: string) => {
    setEditingKey(key);
    setEditingValue(value);
  }, []);

  const cancelEditing = useCallback(() => {
    if (editingKey === null) {
      setIsAddingNew(false);
    }
    setEditingKey(null);
    setEditingValue("");
  }, [editingKey]);

  const confirmEdit = useCallback(
    async (key: string, newValue: string) => {
      try {
        const variableToUpdate = { key, value: newValue };
        const response = await updateVariable(variableToUpdate);
        if (response.success) {
          message.success("Variable updated");
          cancelEditing();
          await fetchVariables();
        } else {
          const errorMessage =
            response.error?.responseBody.errorMessage ||
            "Failed to update variable";
          notificationService.requestFailed(errorMessage, response.error);
        }
      } catch (error) {
        notificationService.requestFailed("Failed to update variable", error);
      }
    },
    [cancelEditing, fetchVariables, notificationService, updateVariable],
  );

  const deleteVariable = useCallback(
    async (key: string) => {
      try {
        const success = await deleteVariables([key]);
        if (success) {
          message.success(`Deleted ${key}`);
          await fetchVariables();
        } else {
          notificationService.requestFailed(`Failed to delete ${key}`, {
            message: "Operation failed",
          });
        }
      } catch (error) {
        notificationService.requestFailed(`Failed to delete ${key}`, error);
      }
    },
    [deleteVariables, fetchVariables, notificationService],
  );

  const addVariable = useCallback(
    async (key: string, value: string) => {
      if (key === NEW_VARIABLE_KEY) {
        notificationService.info(
          `Cannot save variable with key '${NEW_VARIABLE_KEY}'`,
          "This key is reserved.",
        );
        return;
      }
      try {
        const response = await createVariable({ key, value: value });
        if (response.success) {
          message.success("Variable added");
          setIsAddingNew(false);
          await fetchVariables();
        } else {
          const errorMessage =
            response.error?.responseBody.errorMessage ||
            "Failed to add variable";
          notificationService.requestFailed(errorMessage, response.error);
        }
      } catch (error) {
        notificationService.requestFailed("Failed to add variable", error);
      }
    },
    [createVariable, fetchVariables, notificationService],
  );

  const handleExport = useCallback(
    async (keys: string[]) => {
      if (!exportVariables) return;
      try {
        await exportVariables(keys);
        message.success("Exported");
      } catch (error) {
        notificationService.requestFailed("Failed to export", error);
      }
    },
    [exportVariables, notificationService],
  );

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
