import React, {
  useState,
  useMemo,
  useEffect,
  useCallback,
  useRef,
} from "react";
import { FieldProps, RJSFSchema } from "@rjsf/utils";
import { Input, Button, Tag, Tooltip } from "antd";
import styles from "./EnhancedPatternPropertiesField.module.css";
import { OverridableIcon } from "../../../../icons/IconProvider.tsx";
import { DescriptionTooltipIcon } from "../DescriptionTooltipFieldTemplate";
import { FormContext } from "../ChainElementModificationContext";
import { api } from "../../../../api/api";
import { QueryParametersCheckbox } from "./QueryParametersCheckbox";
import {
  isAmqpProtocol,
  isKafkaProtocol,
  normalizeProtocol,
} from "../../../../misc/protocol-utils";

type ParamType = "path" | "query" | "additional" | "async" | "custom";

type EnhancedFieldProps = FieldProps<
  Record<string, string>,
  RJSFSchema,
  FormContext
>;

interface ParameterMetadata {
  name: string;
  required: boolean;
  source: "specification" | "environment" | "custom";
  description?: string;
  deprecated?: boolean;
  secured?: boolean;
  pathMapped?: boolean;
}

interface EnhancedParameter extends ParameterMetadata {
  value: string;
  isOverridden?: boolean;
  canDelete: boolean;
  envDefaultValue?: string;
}

interface EnvironmentData {
  id: string;
  sourceType?: string;
  properties?: Record<string, unknown>;
  defaultProperties?: Record<string, unknown>;
}

interface ServiceSummary {
  activeEnvironmentId?: string | null;
}

interface OpenAPIParameter {
  in?: string;
  name?: string;
  required?: boolean;
  description?: string;
  deprecated?: boolean;
}

interface OperationSpecFragment {
  topic?: string;
  channel?: string;
  exchange?: string;
  queues?: string;
  queue?: string;
  maasClassifierName?: string;
  groupId?: string;
  parameters?: OpenAPIParameter[];
}

type SpecificationNode = OperationSpecFragment | undefined | null;

const SECURED_PARAMS = ["password", "token", "secret", "apiKey", "api_key"];
const DEPRECATED_PARAMS = [
  "maas.createRelatedEntities",
  "maas.autoRemoveRelatedEntities",
];

function isSecuredParameter(name: string): boolean {
  const lowerName = name.toLowerCase();
  return SECURED_PARAMS.some((keyword) => lowerName.includes(keyword));
}

function isDeprecatedParameter(name: string): boolean {
  return DEPRECATED_PARAMS.includes(name);
}

type ParamRowProps = {
  param: EnhancedParameter;
  displayedValue: string;
  isDisabled: boolean;
  onKeyChange: (oldKey: string, newKey: string) => void;
  onValueChange: (key: string, value: string) => void;
  onPathMappedValueChange: (key: string, value: string) => void;
  onDelete: (key: string) => void;
  onRestoreDefault: (key: string) => void;
};

const ParamRowImpl: React.FC<ParamRowProps> = ({
  param,
  displayedValue,
  isDisabled,
  onKeyChange,
  onValueChange,
  onPathMappedValueChange,
  onDelete,
  onRestoreDefault,
}) => {
  const hasValue =
    displayedValue !== undefined && String(displayedValue).trim().length > 0;
  const rowClass = param.required && !hasValue ? styles.requiredRow : "";

  return (
    <tr className={rowClass}>
      <td className={styles.td}>
        <div className={styles.nameCell}>
          {param.canDelete && param.source === "custom" ? (
            <Input
              value={param.name}
              onChange={(e) => onKeyChange(param.name, e.target.value)}
              disabled={isDisabled}
              placeholder="Name"
            />
          ) : (
            <span className={styles.paramName}>{param.name}</span>
          )}
          {(param.isOverridden || param.deprecated) && (
            <div className={styles.labels}>
              {param.isOverridden && (
                <Tag className={styles.overriddenTag}>Overridden</Tag>
              )}
              {param.deprecated && (
                <Tag className={styles.deprecatedTag}>Deprecated</Tag>
              )}
            </div>
          )}
        </div>
      </td>
      <td className={styles.td}>
        <Input
          value={displayedValue}
          onChange={(e) =>
            param.pathMapped
              ? onPathMappedValueChange(param.name, e.target.value)
              : onValueChange(param.name, e.target.value)
          }
          disabled={isDisabled}
          placeholder="Value"
        />
      </td>
      <td className={styles.td}>
        <div className={styles.actions}>
          {param.isOverridden && !isDisabled && (
            <Tooltip title="Restore default value">
              <Button
                size="small"
                type="text"
                icon={<OverridableIcon name="rollback" />}
                onClick={() => onRestoreDefault(param.name)}
                className={styles.restoreBtn}
              />
            </Tooltip>
          )}
          {param.canDelete && !isDisabled && (
            <Button
              size="small"
              type="text"
              icon={<OverridableIcon name="delete" />}
              onClick={() => onDelete(param.name)}
              className={styles.deleteBtn}
            />
          )}
        </div>
      </td>
    </tr>
  );
};

const ParamRow = React.memo(
  ParamRowImpl,
  (prev, next) =>
    prev.param === next.param &&
    prev.displayedValue === next.displayedValue &&
    prev.isDisabled === next.isDisabled,
);
ParamRow.displayName = "ParamRow";

function determineParamType(idSchema: string): ParamType {
  if (idSchema.includes("PathParameters")) return "path";
  if (idSchema.includes("QueryParameters")) return "query";
  if (idSchema.includes("AdditionalParameters")) return "additional";
  if (idSchema.includes("AsyncProperties")) return "async";

  return "custom";
}

function parseParametersFromSpec(
  specification: SpecificationNode,
  paramType: ParamType,
  protocolType: string,
  elementType?: string,
): ParameterMetadata[] {
  if (paramType === "async") {
    return parseAsyncAPIParameters(protocolType, elementType);
  }

  if (!specification) {
    return [];
  }

  if (paramType === "path" || paramType === "query") {
    return parseOpenAPIParameters(specification, paramType);
  }

  return [];
}

function parseOpenAPIParameters(
  spec: OperationSpecFragment,
  paramType: ParamType,
): ParameterMetadata[] {
  const parameters = Array.isArray(spec.parameters) ? spec.parameters : [];
  const targetIn = paramType === "path" ? "path" : "query";

  return parameters
    .filter(
      (parameter): parameter is OpenAPIParameter & { name: string } =>
        Boolean(parameter) &&
        parameter.in === targetIn &&
        typeof parameter?.name === "string",
    )
    .map((parameter) => {
      const { name } = parameter;
      return {
        name,
        required: Boolean(parameter.required),
        source: "specification" as const,
        description: parameter.description,
        deprecated:
          Boolean(parameter.deprecated) || isDeprecatedParameter(name),
        secured: isSecuredParameter(name),
      };
    });
}

function parseAsyncAPIParameters(
  protocolType: string,
  elementType?: string,
): ParameterMetadata[] {
  const params: ParameterMetadata[] = [];
  const isAsyncApiTrigger = elementType === "async-api-trigger";

  if (isKafkaProtocol(protocolType) || isAmqpProtocol(protocolType)) {
    params.push({
      name: "maas.classifier.name",
      required: false,
      source: "specification",
    });
  }

  if (isKafkaProtocol(protocolType)) {
    params.push({
      name: "topic",
      required: false,
      source: "specification",
      pathMapped: true,
    });
    params.push({
      name: "groupId",
      required: isAsyncApiTrigger,
      source: "specification",
    });
  }

  if (isAmqpProtocol(protocolType)) {
    params.push({
      name: "exchange",
      required: false,
      source: "specification",
      pathMapped: true,
    });
    params.push({
      name: "queues",
      required: false,
      source: "specification",
    });
  }

  return params;
}

function toOperationSpecFragment(
  specification: unknown,
): OperationSpecFragment | null {
  if (!specification || typeof specification !== "object") {
    return null;
  }

  const record = specification as Record<string, unknown>;
  const parametersValue = record.parameters;
  const parameters = Array.isArray(parametersValue)
    ? parametersValue.filter(
        (item): item is OpenAPIParameter =>
          typeof item === "object" && item !== null,
      )
    : undefined;

  return {
    topic: typeof record.topic === "string" ? record.topic : undefined,
    channel: typeof record.channel === "string" ? record.channel : undefined,
    exchange: typeof record.exchange === "string" ? record.exchange : undefined,
    queues: typeof record.queues === "string" ? record.queues : undefined,
    queue: typeof record.queue === "string" ? record.queue : undefined,
    maasClassifierName:
      typeof record.maasClassifierName === "string"
        ? record.maasClassifierName
        : undefined,
    groupId: typeof record.groupId === "string" ? record.groupId : undefined,
    parameters,
  };
}

function extractSpecification(response: unknown): unknown {
  if (response && typeof response === "object" && "specification" in response) {
    return (response as { specification?: unknown }).specification;
  }
  return undefined;
}

function toStringMap(source?: Record<string, unknown>): Map<string, string> {
  if (!source) {
    return new Map<string, string>();
  }

  const entries = Object.entries(source).filter(
    (entry): entry is [string, string] => typeof entry[1] === "string",
  );

  return new Map(entries);
}

const EnhancedPatternPropertiesField: React.FC<EnhancedFieldProps> = ({
  formData = {},
  onChange,
  schema,
  uiSchema,
  disabled,
  readonly,
  registry,
  fieldPathId,
}) => {
  const [specParameters, setSpecParameters] = useState<ParameterMetadata[]>([]);
  const [envParameters, setEnvParameters] = useState<Map<string, string>>(
    new Map(),
  );
  const [isMaasEnvironment, setIsMaasEnvironment] = useState<boolean>(false);
  const [loadedSpec, setLoadedSpec] = useState<OperationSpecFragment | null>(
    null,
  );
  const [loadedSpecOperationId, setLoadedSpecOperationId] = useState<
    string | null
  >(null);
  const [autoFilledOperationId, setAutoFilledOperationId] = useState<
    string | null
  >(null);
  const [isEnvironmentLoaded, setIsEnvironmentLoaded] =
    useState<boolean>(false);
  const formContext = registry.formContext;

  const paramType = useMemo(
    () => determineParamType(fieldPathId.$id),
    [fieldPathId.$id],
  );
  const updateContext = registry.formContext.updateContext;

  const emitChange = useCallback(
    (value: Record<string, string>) => {
      onChange(value, fieldPathId.path);
      if (paramType === "query") {
        updateContext?.({
          integrationOperationQueryParameters: { ...value },
        });
      } else if (paramType === "path") {
        updateContext?.({
          integrationOperationPathParameters: { ...value },
        });
      }
    },
    [fieldPathId.path, onChange, updateContext, paramType],
  );

  const title = useMemo(() => {
    if (paramType === "async") {
      const protocolType = normalizeProtocol(
        formContext.integrationOperationProtocolType as string,
      );
      if (protocolType) {
        const protocolName =
          protocolType.charAt(0).toUpperCase() + protocolType.slice(1);
        return `${protocolName} Parameters`;
      }
    }
    return schema?.title || uiSchema?.["ui:title"] || "Items";
  }, [
    paramType,
    formContext.integrationOperationProtocolType,
    schema?.title,
    uiSchema,
  ]);

  const rowCount = Object.entries(formData).length;
  const [collapsed, setCollapsed] = useState(!(rowCount > 0));

  // operationSpecification is now published to FormContext centrally by
  // SystemOperationField (via api.getOperationInfo). We just read it here and
  // derive spec-driven parameter metadata. No network calls from this field.
  const resolveSpecification = useCallback(() => {
    const operationId = formContext.integrationOperationId;
    const protocolType = normalizeProtocol(
      formContext.integrationOperationProtocolType as string,
    );

    if (!operationId) {
      setSpecParameters([]);
      setLoadedSpec(null);
      setLoadedSpecOperationId(null);
      setAutoFilledOperationId(null);
      return;
    }

    const specSource = formContext.operationSpecification;
    // If schemas haven't arrived yet (SystemOperationField is still loading),
    // keep previous state — the next render with populated context will
    // re-run this effect and fill things in.
    if (specSource === undefined) {
      return;
    }

    const specFragment = toOperationSpecFragment(
      extractSpecification({ specification: specSource }),
    );
    const params = parseParametersFromSpec(
      specFragment,
      paramType,
      protocolType,
      formContext?.elementType,
    );
    setSpecParameters(params);
    setLoadedSpec(specFragment);
    setLoadedSpecOperationId(operationId);
    setAutoFilledOperationId(null);
  }, [
    formContext.integrationOperationId,
    formContext.integrationOperationProtocolType,
    formContext.elementType,
    formContext.operationSpecification,
    paramType,
  ]);

  const loadEnvironmentParameters = useCallback(async () => {
    const systemId = formContext.integrationSystemId;

    setIsEnvironmentLoaded(false);

    if (!systemId) {
      setEnvParameters(new Map());
      setIsMaasEnvironment(false);
      setIsEnvironmentLoaded(true);
      return;
    }

    try {
      const service: ServiceSummary = await api.getService(systemId);
      const environments: EnvironmentData[] =
        await api.getEnvironments(systemId);

      const activeEnv =
        environments.find((env) => env.id === service.activeEnvironmentId) ??
        environments[0];

      const isMaas = activeEnv?.sourceType === "MAAS_BY_CLASSIFIER";
      setIsMaasEnvironment(Boolean(isMaas));

      setEnvParameters(toStringMap(activeEnv?.properties));
      setIsEnvironmentLoaded(true);
    } catch (error: unknown) {
      const details = error instanceof Error ? error : new Error(String(error));
      console.error(
        "[EnhancedPatternPropertiesField] Failed to load environment parameters:",
        details,
      );
      setEnvParameters(new Map());
      setIsMaasEnvironment(false);
      setIsEnvironmentLoaded(true);
    }
  }, [formContext.integrationSystemId]);

  useEffect(() => {
    resolveSpecification();
  }, [resolveSpecification]);

  useEffect(() => {
    void loadEnvironmentParameters();
  }, [loadEnvironmentParameters]);

  useEffect(() => {
    if (paramType !== "async" || !isEnvironmentLoaded) {
      return;
    }

    const elementType = formContext.elementType;
    const isAsyncApiTrigger = elementType === "async-api-trigger";

    let updatedData: Record<string, string> | null = null;

    if (!isMaasEnvironment && formData["maas.classifier.name"] !== undefined) {
      updatedData = { ...(updatedData ?? formData) };
      delete updatedData["maas.classifier.name"];
    }

    if (!isAsyncApiTrigger && formData.groupId !== undefined) {
      updatedData = { ...(updatedData ?? formData) };
      delete updatedData.groupId;
    }

    if (updatedData) {
      emitChange(updatedData);
    }
  }, [
    formData,
    isMaasEnvironment,
    onChange,
    paramType,
    isEnvironmentLoaded,
    formContext.elementType,
    emitChange,
  ]);

  useEffect(() => {
    if (!loadedSpec) return;

    const operationId = formContext.integrationOperationId;
    if (
      !operationId ||
      operationId !== loadedSpecOperationId ||
      operationId === autoFilledOperationId
    ) {
      return;
    }

    const protocolType = normalizeProtocol(
      formContext.integrationOperationProtocolType ?? "",
    );
    const updates: Record<string, string> = {};
    let pathMappedUpdate: string | undefined;

    if (
      isKafkaProtocol(protocolType) &&
      !formContext.integrationOperationPath &&
      !isMaasEnvironment
    ) {
      const topicValue = loadedSpec.topic ?? loadedSpec.channel;
      if (topicValue) {
        pathMappedUpdate = topicValue;
      }
    }

    if (
      isAmqpProtocol(protocolType) &&
      loadedSpec.exchange &&
      !formContext.integrationOperationPath
    ) {
      pathMappedUpdate = loadedSpec.exchange;
    }

    if (isAmqpProtocol(protocolType) && !formData["queues"]) {
      const queuesValue = loadedSpec.queues || loadedSpec.queue;
      if (queuesValue) {
        updates["queues"] = queuesValue;
      }
    }

    if (isKafkaProtocol(protocolType) || isAmqpProtocol(protocolType)) {
      const classifierName = loadedSpec?.maasClassifierName;
      if (isMaasEnvironment && classifierName) {
        updates["maas.classifier.name"] = classifierName;
      } else if (!formData["maas.classifier.name"]) {
        if (classifierName) {
          updates["maas.classifier.name"] = classifierName;
        } else if (isAmqpProtocol(protocolType)) {
          updates["maas.classifier.name"] = "public";
        }
      }
    }

    const elementType = formContext?.elementType;
    const isAsyncApiTrigger = elementType === "async-api-trigger";
    if (
      isKafkaProtocol(protocolType) &&
      isAsyncApiTrigger &&
      !formData["groupId"] &&
      loadedSpec?.groupId
    ) {
      updates["groupId"] = loadedSpec.groupId;
    }

    const hasUpdates = Object.keys(updates).length > 0;
    if (hasUpdates || pathMappedUpdate !== undefined) {
      if (hasUpdates) {
        emitChange({ ...formData, ...updates });
      }
      if (pathMappedUpdate !== undefined) {
        updateContext?.({ integrationOperationPath: pathMappedUpdate });
      }
      setAutoFilledOperationId(operationId);
    }
  }, [
    loadedSpec,
    loadedSpecOperationId,
    formContext.integrationOperationProtocolType,
    formContext.integrationOperationId,
    formContext.integrationOperationPath,
    formContext?.elementType,
    paramType,
    autoFilledOperationId,
    formData,
    onChange,
    isMaasEnvironment,
    emitChange,
    updateContext,
  ]);

  const elementType = formContext.elementType;
  const isAsyncApiTrigger = elementType === "async-api-trigger";

  const mergedParameters = useMemo((): EnhancedParameter[] => {
    const result: EnhancedParameter[] = [];
    const processed = new Set<string>();

    const isHiddenParameter = (name: string): boolean => {
      if (paramType !== "async") return false;

      if (name === "groupId" && !isAsyncApiTrigger) {
        return true;
      }

      if (isMaasEnvironment) {
        return name === "topic";
      } else {
        return name.startsWith("maas.");
      }
    };

    specParameters
      .filter((p) => p.required && !isHiddenParameter(p.name))
      .forEach((p) => {
        const value = formData[p.name] || envParameters.get(p.name) || "";
        result.push({
          ...p,
          value,
          isOverridden: isOverridden(p.name, formData, envParameters),
          canDelete: false,
          envDefaultValue: envParameters.get(p.name),
        });
        processed.add(p.name);
      });

    specParameters
      .filter((p) => !p.required && !isHiddenParameter(p.name))
      .forEach((p) => {
        if (p.pathMapped) {
          const value = (formContext.integrationOperationPath as string) || "";
          result.push({
            ...p,
            value,
            isOverridden: false,
            canDelete: false,
          });
          processed.add(p.name);
          return;
        }

        const hasFormValue = formData[p.name] !== undefined;
        const isAsyncOrAdditional =
          paramType === "async" || paramType === "additional";
        const shouldShow = hasFormValue || isAsyncOrAdditional;

        if (shouldShow) {
          const value =
            formData[p.name] !== undefined
              ? formData[p.name]
              : envParameters.get(p.name) || "";
          result.push({
            ...p,
            value,
            isOverridden: isOverridden(p.name, formData, envParameters),
            canDelete: true,
            envDefaultValue: envParameters.get(p.name),
          });
          processed.add(p.name);
        }
      });

    if (paramType === "additional" || paramType === "async") {
      envParameters.forEach((envValue, name) => {
        if (!processed.has(name) && !isHiddenParameter(name)) {
          const currentValue =
            formData[name] !== undefined ? formData[name] : envValue;
          result.push({
            name,
            value: currentValue,
            required: false,
            source: "environment",
            isOverridden:
              formData[name] !== undefined && formData[name] !== envValue,
            canDelete: true,
            envDefaultValue: envValue,
            deprecated: isDeprecatedParameter(name),
            secured: isSecuredParameter(name),
          });
          processed.add(name);
        }
      });
    }

    Object.entries(formData).forEach(([name, value]) => {
      if (!processed.has(name) && !isHiddenParameter(name)) {
        result.push({
          name,
          value,
          required: false,
          source: "custom",
          canDelete: true,
          deprecated: isDeprecatedParameter(name),
          secured: isSecuredParameter(name),
        });
      }
    });

    return result;
  }, [
    specParameters,
    envParameters,
    formData,
    formContext.integrationOperationPath,
    paramType,
    isMaasEnvironment,
    isAsyncApiTrigger,
  ]);

  const reportMissingRequiredParams = formContext.reportMissingRequiredParams;

  useEffect(() => {
    const missing = mergedParameters
      .filter((p) => p.required && !p.value?.trim())
      .map((p) => p.name);
    reportMissingRequiredParams?.(paramType, missing);
  }, [mergedParameters, reportMissingRequiredParams, paramType]);

  function isOverridden(
    name: string,
    formData: Record<string, string>,
    envParams: Map<string, string>,
  ): boolean {
    return (
      envParams.has(name) &&
      formData[name] !== undefined &&
      formData[name] !== envParams.get(name)
    );
  }

  // Refs keep handlers stable across renders while always reading fresh state.
  // Without this, debounced setTimeout closures would use stale formData and
  // ParamRow's React.memo would be invalidated on every parent re-render.
  const formDataRef = useRef(formData);
  formDataRef.current = formData;
  const envParametersRef = useRef(envParameters);
  envParametersRef.current = envParameters;
  const emitChangeRef = useRef(emitChange);
  emitChangeRef.current = emitChange;
  const updateContextRef = useRef(updateContext);
  updateContextRef.current = updateContext;

  const handleAdd = useCallback(() => {
    const newKey = "";
    emitChangeRef.current({ ...formDataRef.current, [newKey]: "" });
  }, []);

  const handleKeyChange = useCallback((oldKey: string, newKey: string) => {
    if (oldKey === newKey) return;
    const updated = { ...formDataRef.current };
    const value = updated[oldKey];
    delete updated[oldKey];
    updated[newKey] = value;
    emitChangeRef.current(updated);
  }, []);

  const [localValues, setLocalValues] = useState<Record<string, string>>({});
  const debounceTimersRef = React.useRef<
    Record<string, ReturnType<typeof setTimeout>>
  >({});

  useEffect(() => {
    const timersSnapshot = debounceTimersRef.current;
    return () => {
      Object.values(timersSnapshot).forEach((timer) => clearTimeout(timer));
    };
  }, []);

  const handleValueChange = useCallback((key: string, value: string) => {
    setLocalValues((prev) => ({ ...prev, [key]: value }));

    if (debounceTimersRef.current[key]) {
      clearTimeout(debounceTimersRef.current[key]);
    }

    debounceTimersRef.current[key] = setTimeout(() => {
      emitChangeRef.current({ ...formDataRef.current, [key]: value });
      setLocalValues((prev) => {
        const updated = { ...prev };
        delete updated[key];
        return updated;
      });
    }, 300);
  }, []);

  const lastSentPathRef = React.useRef<string>();

  const handlePathMappedValueChange = useCallback(
    (key: string, value: string) => {
      setLocalValues((prev) => ({ ...prev, [key]: value }));

      if (debounceTimersRef.current[key]) {
        clearTimeout(debounceTimersRef.current[key]);
      }

      debounceTimersRef.current[key] = setTimeout(() => {
        lastSentPathRef.current = value;
        updateContextRef.current?.({ integrationOperationPath: value });
      }, 300);
    },
    [],
  );

  useEffect(() => {
    const currentPath = formContext.integrationOperationPath as string;
    if (currentPath === lastSentPathRef.current) return;

    setLocalValues((prev) => {
      const pathMappedNames = specParameters
        .filter((p) => p.pathMapped)
        .map((p) => p.name);
      let changed = false;
      const updated = { ...prev };
      for (const name of pathMappedNames) {
        if (name in updated) {
          delete updated[name];
          changed = true;
        }
      }
      return changed ? updated : prev;
    });
  }, [formContext.integrationOperationPath, specParameters]);

  const handleDelete = useCallback((key: string) => {
    const updated = { ...formDataRef.current };
    delete updated[key];
    emitChangeRef.current(updated);
  }, []);

  const restoreDefaultValue = useCallback((key: string) => {
    const defaultValue = envParametersRef.current.get(key) || "";
    emitChangeRef.current({ ...formDataRef.current, [key]: defaultValue });
  }, []);

  return (
    <div>
      <div className={styles.header}>
        <div
          className={styles.leftHeader}
          onClick={() => setCollapsed((s) => !s)}
        >
          <span className={styles.iconWrapper}>
            {collapsed ? (
              <OverridableIcon name="right" />
            ) : (
              <OverridableIcon name="down" />
            )}
          </span>
          <span>{title}</span>
          {schema?.description && (
            <DescriptionTooltipIcon description={schema.description} />
          )}
          <span className={styles.badge}>{mergedParameters.length}</span>
        </div>

        <div>
          <Button
            size="small"
            type="text"
            icon={<OverridableIcon name="plus" />}
            onClick={handleAdd}
            disabled={disabled || readonly}
            style={{ marginLeft: 8 }}
          />
        </div>
      </div>
      {!collapsed &&
        (mergedParameters.length === 0 ? (
          <div className={styles.noEntries}>
            No entries. Click <b>+</b> to add.
          </div>
        ) : (
          <>
            <table className={styles.table}>
              <thead>
                <tr>
                  <th className={styles.th}>Name</th>
                  <th className={styles.th}>Value</th>
                  <th className={styles.th}></th>
                </tr>
              </thead>
              <tbody>
                {mergedParameters.map((param, idx) => (
                  <ParamRow
                    key={idx}
                    param={param}
                    displayedValue={localValues[param.name] ?? param.value}
                    isDisabled={!!(disabled || readonly)}
                    onKeyChange={handleKeyChange}
                    onValueChange={handleValueChange}
                    onPathMappedValueChange={handlePathMappedValueChange}
                    onDelete={handleDelete}
                    onRestoreDefault={restoreDefaultValue}
                  />
                ))}
              </tbody>
            </table>
            {paramType === "query" &&
              formContext?.integrationOperationProtocolType?.toLowerCase() ===
                "http" && (
                <QueryParametersCheckbox
                  formContext={formContext}
                  disabled={disabled}
                  readonly={readonly}
                />
              )}
          </>
        ))}
    </div>
  );
};

export default EnhancedPatternPropertiesField;
