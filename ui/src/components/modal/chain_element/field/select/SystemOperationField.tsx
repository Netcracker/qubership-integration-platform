import React, { useCallback, useEffect, useRef, useState } from "react";
import { FieldProps } from "@rjsf/utils";
import { Flex, SelectProps, Switch, Typography } from "antd";
import { FormContext } from "../../ChainElementModificationContext.ts";
import { api } from "../../../../../api/api.ts";
import { useNotificationService } from "../../../../../hooks/useNotificationService.tsx";
import {
  PaginationOptions,
  SystemOperation,
} from "../../../../../api/apiTypes.ts";

import { MethodBadge } from "../../../../services/ui/MethodBadge.tsx";
import { SelectTag } from "./SelectTag.tsx";
import {
  normalizeProtocol,
  protocolForContext,
} from "../../../../../misc/protocol-utils.ts";
import { SelectAndNavigateField } from "./SelectAndNavigateField.tsx";
import { OperationPath } from "../../../../services/ui/OperationPath.tsx";
import styles from "./selectOptionValue.module.css";
import { isVsCode } from "../../../../../api/rest/vscodeExtensionApi";
import { JSONSchema7 } from "json-schema";
import {
  uniqueListById,
  uniqueMapById,
} from "../../../../../misc/operations-utils.ts";

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

  const contextProtocol =
    registry.formContext?.integrationOperationProtocolType;
  const protocolType = normalizeProtocol(contextProtocol as string);
  const isGrpcOperation = protocolType === "grpc";

  // The protocol belongs to the service, not to the operation: operationKind reads
  // "asyncapi" for both kafka and amqp, and the oneOf branches, the Validations tab
  // and isKafkaProtocol all match the service spelling.
  //
  // A specification import rewrites the service protocol (setSystemProtocol), so the
  // value the modal opens with — it comes from the element's stored properties — is
  // only a hint and gets refreshed from the service below. What ServiceField publishes
  // during this modal session is current: the user picked the service from a list
  // useServices had just loaded. `openedWithSystemIdRef` tells the two apart — any
  // service id other than the one the modal opened on was picked here.
  const openedWithSystemIdRef = useRef<string | undefined>(systemId);
  const checkedSystemIdRef = useRef<string | undefined>(undefined);
  const serviceProtocolRef = useRef<
    { systemId: string; protocol: string } | undefined
  >(undefined);
  // Snapshotted via refs (not deps) so an unrelated context update — the parent
  // publishes operation schemas into the same object — cannot cancel a refresh
  // that is still in flight.
  const contextProtocolRef = useRef<string | undefined>(contextProtocol);
  contextProtocolRef.current = contextProtocol;
  const updateContextRef = useRef(registry.formContext?.updateContext);
  updateContextRef.current = registry.formContext?.updateContext;

  /** The protocol to publish without asking the service again, if there is one. */
  const getTrustedProtocol = useCallback((): string | undefined => {
    const refreshed = serviceProtocolRef.current;
    if (refreshed && refreshed.systemId === systemId) {
      return refreshed.protocol;
    }
    // No service to ask, or the service was picked in this session.
    return !systemId || systemId !== openedWithSystemIdRef.current
      ? contextProtocol
      : undefined;
  }, [systemId, contextProtocol]);

  const synchronousGrpcCall = registry.formContext
    ?.synchronousGrpcCall as boolean;

  const usePagination = !isVsCode;

  const fetchOperations = useCallback(async () => {
    if (!specificationId) return;

    setIsLoading(true);
    try {
      const loaded = await api.getOperations(specificationId, {});
      const uniqueLoaded = uniqueListById(loaded);
      const uniqueLoadedMap = uniqueMapById(loaded);

      setOperations(uniqueLoaded);
      setOperationsMap(uniqueLoadedMap);

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
        const uniquePage = uniqueListById(page);

        setOperations((prev) => {
          if (!nextOffset) {
            return uniquePage;
          }
          const seen = new Set(prev.map((op) => op.id));
          const appended = uniquePage.filter((op) => !seen.has(op.id));
          return [...prev, ...appended];
        });

        setOperationsMap((prev) => {
          const m = new Map(nextOffset ? prev.entries() : []);
          uniquePage.forEach((op) => {
            if (!m.has(op.id)) {
              m.set(op.id, op);
            }
          });
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

  // Modal opened on an existing element: re-read the protocol from the service once,
  // and publish it when it no longer matches what the element stored.
  useEffect(() => {
    if (!systemId) return;
    if (systemId !== openedWithSystemIdRef.current) return;
    if (checkedSystemIdRef.current === systemId) return;

    checkedSystemIdRef.current = systemId;
    let cancelled = false;

    void api
      .getService(systemId)
      .then((service) => {
        if (cancelled) return;

        const protocol = protocolForContext(service.protocol);
        serviceProtocolRef.current = { systemId, protocol };

        if (protocol !== normalizeProtocol(contextProtocolRef.current ?? "")) {
          updateContextRef.current?.({
            integrationOperationProtocolType: protocol,
          });
        }
      })
      .catch(() => {
        // An unreachable service is no reason to rewrite the element: keep the
        // stored protocol and let handleChange retry the lookup.
      });

    return () => {
      cancelled = true;
    };
  }, [systemId]);

  useEffect(() => {
    const operationOptions: SelectProps["options"] =
      operations?.map((operation) => ({
        label: (
          <span className={styles.row}>
            <span className={styles.nameCol}>
              <SelectTag value={operation.name} />
            </span>
            <MethodBadge value={operation.method} minWidth={72} />
            <span className={styles.path}>
              <OperationPath path={operation.path} />
            </span>
          </span>
        ),
        value: operation.id,
        selectedLabel: formData === operation.id && (
          <span className={styles.row}>
            <SelectTag value={operation.name} />
            <MethodBadge value={operation.method} />
            <span className={styles.path}>
              <OperationPath
                path={operation.path}
                pathParams={
                  registry.formContext?.integrationOperationPathParameters
                }
                queryParams={
                  registry.formContext?.integrationOperationQueryParameters
                }
              />
            </span>
          </span>
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

      // Note: schemas (specification/requestSchema/responseSchemas) and
      // HTTP query-parameter autofill are handled centrally by
      // ChainElementModification's loader effect — it watches
      // `integrationOperationId` and republishes on change. We just switch
      // operation identifiers here and clear stale path/query overrides.
      const apply = (proto: string) => {
        const protocolType = protocolForContext(proto);

        registry.formContext.updateContext?.({
          integrationOperationId: newValue,
          integrationOperationPath: operation.path,
          integrationOperationMethod: operation.method,
          integrationOperationProtocolType: protocolType,
          integrationOperationPathParameters: undefined,
          integrationOperationQueryParameters: undefined,
          after: undefined,
          errorThrowing: true,
        });
      };

      // Only a protocol this modal session vouches for — see getTrustedProtocol —
      // may be reused. Otherwise ask the service: the stored value can predate a
      // specification import that changed the protocol, and the refresh above may
      // still be in flight or have failed.
      const trustedProtocol = getTrustedProtocol();

      if (trustedProtocol) {
        apply(trustedProtocol);
      } else if (systemId) {
        void api
          .getService(systemId)
          .then((service) => {
            serviceProtocolRef.current = {
              systemId,
              protocol: protocolForContext(service.protocol),
            };
            apply(service.protocol);
          })
          .catch(() => apply(""));
      } else {
        apply("");
      }
    },
    [registry.formContext, operationsMap, getTrustedProtocol],
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
      const target = event.currentTarget;
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
