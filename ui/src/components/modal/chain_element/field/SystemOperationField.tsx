import React, { useEffect, useState } from "react";
import { FieldProps } from "@rjsf/utils";
import { Select, SelectProps } from "antd";
import { FormContext } from "../ChainElementModification";
import { api } from "../../../../api/api";
import { useNotificationService } from "../../../../hooks/useNotificationService";
import { SystemOperation } from "../../../../api/apiTypes";

const SystemOperationField: React.FC<FieldProps<any, any, FormContext>> = ({
  id,
  formData,
  schema,
  required,
  uiSchema,
  formContext,
}) => {
  const notificationService = useNotificationService();
  const [options, setOptions] = useState<SelectProps["options"]>([]);
  const [operationsMap, setOperationsMap] = useState<
    Map<string, SystemOperation>
  >(new Map());
  const specificationId = formContext!.integrationSpecificationId;

  useEffect(() => {
    const loadOperations = async () => {
      try {
        if (specificationId) {
          const operations = await api.getOperations(specificationId);

          const operationOptions: SelectProps["options"] =
            operations?.map((operation) => ({
              label: `${operation.name} ${operation.method} ${operation.path}`,
              value: operation.id,
            })) ?? [];
          setOperationsMap(
            new Map(operations.map((operation) => [operation.id, operation])),
          );
          setOptions(operationOptions);
        } else {
          setOperationsMap(new Map());
          setOptions([]);
        }
      } catch (error) {
        notificationService.requestFailed("Failed to load operations", error);
      }
    };

    void loadOperations();
  }, [specificationId, notificationService]);

  const title = uiSchema?.["ui:title"] ?? schema?.title ?? "";

  const labelStyle: React.CSSProperties = {
    display: "block",
    marginBottom: 6,
    fontWeight: 500,
  };

  const requiredStyle: React.CSSProperties = {
    color: "#ff4d4f",
    marginRight: 4,
  };

  const handleChange = (newValue: string) => {
    const operation: SystemOperation = operationsMap.get(newValue)!;

    formContext?.updateContext({
      integrationOperationId: newValue,
      integrationOperationPath: operation.path,
      integrationOperationMethod: operation.method,
      integrationOperationProtocolType: "http",
    });
  };

  return (
    <div>
      <label htmlFor={id} style={labelStyle}>
        {required ? <span style={requiredStyle}> *</span> : null}
        {title}
      </label>
      <Select value={formData} options={options} onChange={handleChange} />
    </div>
  );
};

export default SystemOperationField;
