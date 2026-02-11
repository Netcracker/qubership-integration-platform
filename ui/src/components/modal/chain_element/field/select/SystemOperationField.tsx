import React, { useCallback, useEffect, useRef, useState } from "react";
import { FieldProps } from "@rjsf/utils";
import { Flex, SelectProps, Switch, Typography } from "antd";
import { FormContext } from "../../ChainElementModification.tsx";
import { api } from "../../../../../api/api.ts";
import { useNotificationService } from "../../../../../hooks/useNotificationService.tsx";
import {
  PaginationOptions,
  SystemOperation,
} from "../../../../../api/apiTypes.ts";

import { HttpMethod } from "../../../../services/HttpMethod.tsx";
import { SelectTag } from "./SelectTag.tsx";
import {
  isHttpProtocol,
  normalizeProtocol,
} from "../../../../../misc/protocol-utils.ts";
import { SelectAndNavigateField } from "./SelectAndNavigateField.tsx";
import { OperationPath } from "../../../../services/OperationPath.tsx";
import { isVsCode } from "../../../../../api/rest/vscodeExtensionApi";
import { JSONSchema7 } from "json-schema";

const SystemOperationField: React.FC<
  FieldProps<string, JSONSchema7, FormContext>
> = ({ id, formData, schema, required, uiSchema, registry }) => {
  const notificationService = useNotificationService();

  const [operations, setOperations] = useState<SystemOperation[]>([]);
  const [options, setOptions] = useState<SelectProps["options"]>([]);
  const [operationsMap, setOperationsMap] = useState<
    Map<string, SystemOperation>
  >(new Map());
  const [isLoading, setIsLoading] = useState<boolean>(false);

  const inFlightRef = useRef(false);
  const offsetRef = useRef(0);
  const allLoadedRef = useRef(false);

  const systemId = registry.formContext?.integrationSystemId as string;
  const specGroupId = registry.formContext
    ?.integrationSpecificationGroupId as string;
  const specificationId: string = registry.formContext
    ?.integrationSpecificationId as string;

  const [operationId, setOperationId] = useState<string | undefined>(formData);
  const [navigationPath, setNavigationPath] = useState<string>("");

  const protocolType = normalizeProtocol(
    registry.formContext?.integrationOperationProtocolType as string,
  );
  const isGrpcOperation = protocolType === "grpc";
  const synchronousGrpcCall = registry.formContext
    ?.synchronousGrpcCall as boolean;

  const usePagination = !isVsCode;

  const fetchOperations = useCallback(async () => {
    if (!specificationId) return;

    setIsLoading(true);
    try {
      const loaded = await api.getOperations(specificationId, {});
      setOperations(loaded);
      setOperationsMap(new Map(loaded.map((op) => [op.id, op])));
      offsetRef.current = loaded.length;
      allLoadedRef.current = true;
    } catch (error) {
      setOperations([]);
      setOperationsMap(new Map());
      offsetRef.current = 0;
      allLoadedRef.current = false;
      notificationService.requestFailed("Failed to load operations", error);
    } finally {
      setIsLoading(false);
    }
  }, [specificationId, notificationService]);

  const fetchOperationsPaginated = useCallback(
    async (nextOffset: number) => {
      if (!specificationId) return;
      if (inFlightRef.current) return;
      if (allLoadedRef.current && nextOffset !== 0) return;

      inFlightRef.current = true;
      setIsLoading(true);

      try {
        const pagination: PaginationOptions = { offset: nextOffset };
        const page = await api.getOperations(specificationId, pagination);

        setOperations((prev) => [...(nextOffset ? prev : []), ...page]);

        setOperationsMap((prev) => {
          const m = new Map(nextOffset ? prev.entries() : []);
          page.forEach((op) => m.set(op.id, op));
          return m;
        });

        offsetRef.current = nextOffset + page.length;
        allLoadedRef.current = page.length === 0;
      } catch (error) {
        setOperations([]);
        setOperationsMap(new Map());
        offsetRef.current = 0;
        allLoadedRef.current = false;
        notificationService.requestFailed("Failed to load operations", error);
      } finally {
        inFlightRef.current = false;
        setIsLoading(false);
      }
    },
    [specificationId, notificationService],
  );

  useEffect(() => {
    setOperations([]);
    setOperationsMap(new Map());
    offsetRef.current = 0;
    allLoadedRef.current = false;
    inFlightRef.current = false;

    if (!specificationId) return;

    if (usePagination) {
      void fetchOperationsPaginated(0);
    } else {
      void fetchOperations();
    }
  }, [
    specificationId,
    usePagination,
    fetchOperations,
    fetchOperationsPaginated,
  ]);

  useEffect(() => {
    if (!usePagination) return;
    if (!formData) return;
    if (!specificationId) return;
    if (operationsMap.has(formData)) return;
    if (allLoadedRef.current) return;
    if (inFlightRef.current) return;

    void fetchOperationsPaginated(offsetRef.current);
  }, [
    usePagination,
    formData,
    specificationId,
    operationsMap,
    fetchOperationsPaginated,
  ]);

  useEffect(() => {
    const operationOptions: SelectProps["options"] =
      operations?.map((operation) => ({
        label: (
          <>
            <SelectTag value={operation.name} />
            <HttpMethod value={operation.method} width={110} />
            <OperationPath path={operation.path} />
          </>
        ),
        value: operation.id,
        selectedLabel: formData === operation.id && (
          <>
            <SelectTag value={operation.name} />
            <HttpMethod value={operation.method} width={110} />
            <OperationPath
              path={operation.path}
              pathParams={
                registry.formContext?.integrationOperationPathParameters
              }
              queryParams={
                registry.formContext?.integrationOperationQueryParameters
              }
            />
          </>
        ),
      })) ?? [];
    setOptions(operationOptions);
  }, [
    operations,
    formData,
    registry.formContext?.integrationOperationQueryParameters,
    registry.formContext?.integrationOperationPathParameters,
  ]);

  const title = uiSchema?.["ui:title"] ?? schema?.title ?? "";

  const handleChange = useCallback(
    (newValue: string) => {
      setOperationId(newValue);

      const operation = operationsMap.get(newValue);
      if (!operation) return;

      const systemId = registry.formContext?.integrationSystemId as string;

      const apply = async (proto: string) => {
        const protocolType = normalizeProtocol(proto) ?? "http";

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
          integrationOperationPathParameters: {},
          integrationOperationQueryParameters:
            Object.keys(queryParams).length > 0 ? queryParams : {},
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

  useEffect(() => {
    setNavigationPath(
      `/services/systems/${systemId}/specificationGroups/${specGroupId}/specifications/${specificationId}/operations/${operationId}`,
    );
  }, [systemId, specGroupId, specificationId, operationId]);

  const handleGrpcSynchronousChange = useCallback(
    (checked: boolean) => {
      registry.formContext?.updateContext?.({
        synchronousGrpcCall: checked,
      });
    },
    [registry.formContext],
  );

  const onPopupScroll: SelectProps<string>["onPopupScroll"] = useCallback(
    (event: React.UIEvent<HTMLDivElement>) => {
      const target = event.target as HTMLDivElement;
      const isScrolledToTheEnd =
        target.scrollTop + target.clientHeight + 1 >= target.scrollHeight;

      if (!isScrolledToTheEnd) return;
      if (allLoadedRef.current) return;
      if (inFlightRef.current) return;

      void fetchOperationsPaginated(offsetRef.current);
    },
    [fetchOperationsPaginated],
  );

  const isInitialLoading = isLoading && operations.length === 0;

  return (
    <div>
      <SelectAndNavigateField
        id={id}
        title={title}
        required={required}
        selectValue={formData}
        selectOptions={options}
        selectOnChange={handleChange}
        selectDisabled={isInitialLoading}
        selectLoading={isLoading}
        selectOnPopupScroll={usePagination ? onPopupScroll : undefined}
        selectOptionLabelProp="selectedLabel"
        buttonTitle="Go to operation"
        buttonDisabled={
          !(systemId && specGroupId && specificationId && operationId)
        }
        buttonOnClick={navigationPath}
      />
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
