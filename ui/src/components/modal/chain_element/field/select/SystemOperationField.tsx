import React, { useCallback, useEffect, useState } from "react";
import { FieldProps } from "@rjsf/utils";
import {
  Button,
  Flex,
  Select,
  SelectProps,
  Switch,
  Tooltip,
  Typography,
} from "antd";
import { FormContext } from "../../ChainElementModification.tsx";
import { api } from "../../../../../api/api.ts";
import { useNotificationService } from "../../../../../hooks/useNotificationService.tsx";
import { SystemOperation } from "../../../../../api/apiTypes.ts";
import { JSONSchema7 } from "json-schema";
import { VSCodeExtensionApi } from "../../../../../api/rest/vscodeExtensionApi.ts";
import { OverridableIcon } from "../../../../../icons/IconProvider.tsx";
import { HttpMethod } from "../../../../services/HttpMethod.tsx";
import { SelectTag } from "./SelectTag.tsx";
import {
  isHttpProtocol,
  normalizeProtocol,
} from "../../../../../misc/protocol-utils.ts";

const SystemOperationField: React.FC<
  FieldProps<string, JSONSchema7, FormContext>
> = ({ id, formData, schema, required, uiSchema, registry }) => {
  const notificationService = useNotificationService();
  const [options, setOptions] = useState<SelectProps["options"]>([]);
  const [operationsMap, setOperationsMap] = useState<
    Map<string, SystemOperation>
  >(new Map());
  const [isLoading, setIsLoading] = useState<boolean>(false);

  const systemId = registry.formContext?.integrationSystemId as string;
  const specGroupId = registry.formContext
    ?.integrationSpecificationGroupId as string;
  const specificationId = registry.formContext
    ?.integrationSpecificationId as string;
  const [operationId, setOperationId] = useState<string | undefined>(formData);
  const protocolType = normalizeProtocol(
    registry.formContext?.integrationOperationProtocolType as string,
  );
  const isGrpcOperation = protocolType === "grpc";
  const synchronousGrpcCall = registry.formContext
    ?.synchronousGrpcCall as boolean;

  useEffect(() => {
    const loadOperations = async () => {
      setIsLoading(true);
      try {
        if (specificationId) {
          const operations = await api.getOperations(specificationId);

          const operationOptions: SelectProps["options"] =
            operations?.map((operation) => ({
              label: (
                <>
                  <SelectTag value={operation.name} width={200} />
                  <HttpMethod value={operation.method} width={110} />
                  {operation.path}
                </>
              ),
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
      const systemId = registry.formContext?.integrationSystemId as string;

      const apply = async (proto: string) => {
        const protocolType = normalizeProtocol(proto) ?? "http";

        // Initialize query parameters from specification (for HTTP/SOAP)
        const queryParams: Record<string, string> = {};
        if (isHttpProtocol(protocolType)) {
          try {
            const opInfo = await api.getOperationInfo(newValue);
            if (
              opInfo.specification?.parameters &&
              Array.isArray(opInfo.specification.parameters)
            ) {
              interface Parameter {
                in?: string;
                name?: string;
              }

              const parameters = opInfo.specification.parameters as Parameter[];
              const queryParamNames = parameters
                .filter(
                  (
                    p,
                  ): p is Parameter & {
                    in: "query";
                    name: string;
                  } => p.in === "query" && typeof p.name === "string",
                )
                .map((p) => p.name);

              queryParamNames.forEach((name: string) => {
                queryParams[name] = "";
              });
            }
          } catch (error) {
            console.error(
              "Failed to load operation specification for query params:",
              error,
            );
          }
        }

        registry.formContext.updateContext?.({
          integrationOperationId: newValue,
          integrationOperationPath: operation.path,
          integrationOperationMethod: operation.method,
          integrationOperationProtocolType: protocolType,
          integrationOperationQueryParameters:
            Object.keys(queryParams).length > 0 ? queryParams : undefined,
        });
      };

      if (systemId) {
        void api
          .getService(systemId)
          .then((s) => apply(s.protocol))
          .catch(() => apply(""));
      } else {
        void apply("");
      }
    },
    [registry.formContext, operationsMap],
  );

  const onNavigationButtonClick = useCallback(() => {
    const path = `/services/systems/${systemId}/specificationGroups/${specGroupId}/specifications/${specificationId}/operations/${operationId}`;
    if (api instanceof VSCodeExtensionApi) {
      void api.navigateInNewTab(path);
    } else {
      window.open(path, "_blank");
    }
  }, [systemId, specGroupId, specificationId, operationId]);

  const handleGrpcSynchronousChange = useCallback(
    (checked: boolean) => {
      registry.formContext?.updateContext?.({
        synchronousGrpcCall: checked,
      });
    },
    [registry.formContext],
  );

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
            icon={<OverridableIcon name="send" />}
            disabled={
              !(systemId && specGroupId && specificationId && operationId)
            }
            onClick={onNavigationButtonClick}
          />
        </Tooltip>
      </Flex>
      {isGrpcOperation && (
        <Flex align="center" gap={8} style={{ marginTop: 12, marginBottom: 8 }}>
          <Typography.Text strong>Synchronous call</Typography.Text>
          <Switch
            checked={synchronousGrpcCall}
            onChange={handleGrpcSynchronousChange}
          />
        </Flex>
      )}
    </div>
  );
};

export default SystemOperationField;
