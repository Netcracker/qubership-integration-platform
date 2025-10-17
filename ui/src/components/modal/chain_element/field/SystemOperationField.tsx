import React, { useCallback, useEffect, useState } from "react";
import { FieldProps } from "@rjsf/utils";
import { Button, Flex, Select, SelectProps, Tooltip } from "antd";
import { FormContext } from "../ChainElementModification";
import { api } from "../../../../api/api";
import { useNotificationService } from "../../../../hooks/useNotificationService";
import { SystemOperation } from "../../../../api/apiTypes";
import { JSONSchema7 } from "json-schema";
import { VSCodeExtensionApi } from "../../../../api/rest/vscodeExtensionApi";
import { Icon } from "../../../../IconProvider";

const SystemOperationField: React.FC<
  FieldProps<string, JSONSchema7, FormContext>
> = ({ id, formData, schema, required, uiSchema, formContext }) => {
  const notificationService = useNotificationService();
  const [options, setOptions] = useState<SelectProps["options"]>([]);
  const [operationsMap, setOperationsMap] = useState<
    Map<string, SystemOperation>
  >(new Map());
  const [isLoading, setIsLoading] = useState<boolean>(false);

  const systemId = formContext?.integrationSystemId;
  const specGroupId = formContext?.integrationSpecificationGroupId;
  const specificationId = formContext?.integrationSpecificationId;
  const [operationId, setOperationId] = useState<string | undefined>(formData);

  useEffect(() => {
    const loadOperations = async () => {
      setIsLoading(true);
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
        setOperationsMap(new Map());
        setOptions([]);
        notificationService.requestFailed("Failed to load operations", error);
      } finally {
        setIsLoading(false);
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

  const handleChange = useCallback(
    (newValue: string) => {
      setOperationId(newValue);
      const operation: SystemOperation = operationsMap.get(newValue)!;
      const systemId = formContext?.integrationSystemId;

      const apply = (proto?: string) => {
        formContext?.updateContext({
          integrationOperationId: newValue,
          integrationOperationPath: operation.path,
          integrationOperationMethod: operation.method,
          integrationOperationProtocolType: (typeof proto === 'string' && proto.trim()) ? proto.toLowerCase() : 'http',
        });
      };

      if (systemId) {
        void api.getService(systemId)
          .then(s => apply(typeof s?.protocol === 'string' ? s.protocol : undefined))
          .catch(() => apply(undefined));
      } else {
        apply(undefined);
      }
    },
    [formContext, operationsMap],
  );

  const onNavigationButtonClick = useCallback(() => {
    const path = `/services/systems/${systemId}/specificationGroups/${specGroupId}/specifications/${specificationId}/operations/${operationId}`;
    if (api instanceof VSCodeExtensionApi) {
      void api.navigateInNewTab(path);
    } else {
      window.open(path, "_blank");
    }
  }, [
    systemId,
    specGroupId,
    specificationId,
    operationId,
  ]);

  return (
    <div>
      <label htmlFor={id} style={labelStyle}>
        {required ? <span style={requiredStyle}> *</span> : null}
        {title}
      </label>
      <Flex gap={4}>
        <Select
          value={formData}
          options={options}
          onChange={handleChange}
          disabled={isLoading}
        />
        <Tooltip title="Go to operation">
          <Button
            icon={<Icon name="send" />}
            disabled={
              !(systemId && specGroupId && specificationId && operationId)
            }
            onClick={onNavigationButtonClick}
          />
        </Tooltip>
      </Flex>
    </div>
  );
};

export default SystemOperationField;
