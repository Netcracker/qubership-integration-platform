import React, { useState, useMemo, useEffect, useCallback } from "react";
import { FieldProps, RJSFSchema } from "@rjsf/utils";
import { Input, Button, Tag, Tooltip } from "antd";
import styles from "./EnhancedPatternPropertiesField.module.css";
import { Icon } from "../../../../IconProvider.tsx";
import { FormContext } from "../ChainElementModification";
import { api } from "../../../../api/api";

type ParamType = 'path' | 'query' | 'additional' | 'async' | 'custom';

type EnhancedFieldProps = FieldProps<Record<string, string>, RJSFSchema, FormContext>;
type EnhancedIdSchema = EnhancedFieldProps['idSchema'];

interface ParameterMetadata {
  name: string;
  required: boolean;
  source: 'specification' | 'environment' | 'custom';
  description?: string;
  deprecated?: boolean;
  secured?: boolean;
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

const SECURED_PARAMS = ['password', 'token', 'secret', 'apiKey', 'api_key'];
const DEPRECATED_PARAMS = ['maas.createRelatedEntities', 'maas.autoRemoveRelatedEntities'];

function isSecuredParameter(name: string): boolean {
  const lowerName = name.toLowerCase();
  return SECURED_PARAMS.some(keyword => lowerName.includes(keyword));
}

function isDeprecatedParameter(name: string): boolean {
  return DEPRECATED_PARAMS.includes(name);
}

function determineParamType(idSchema?: EnhancedIdSchema | null): ParamType {
  const fieldId = idSchema?.$id ?? '';

  if (fieldId.includes('PathParameters')) return 'path';
  if (fieldId.includes('QueryParameters')) return 'query';
  if (fieldId.includes('AdditionalParameters')) return 'additional';
  if (fieldId.includes('AsyncProperties')) return 'async';

  return 'custom';
}

function parseParametersFromSpec(specification: SpecificationNode, paramType: ParamType, protocolType?: string): ParameterMetadata[] {
  if (paramType === 'async') {
    return parseAsyncAPIParameters(protocolType);
  }

  if (!specification) {
    return [];
  }

  if (paramType === 'path' || paramType === 'query') {
    return parseOpenAPIParameters(specification, paramType);
  }

  return [];
}

function parseOpenAPIParameters(spec: OperationSpecFragment, paramType: ParamType): ParameterMetadata[] {
  const parameters = Array.isArray(spec.parameters) ? spec.parameters : [];
  const targetIn = paramType === 'path' ? 'path' : 'query';

  return parameters
    .filter((parameter): parameter is OpenAPIParameter & { name: string } =>
      Boolean(parameter) && parameter.in === targetIn && typeof parameter?.name === 'string')
    .map((parameter) => {
      const { name } = parameter;
      return {
        name,
        required: Boolean(parameter.required),
        source: 'specification' as const,
        description: parameter.description,
        deprecated: Boolean(parameter.deprecated) || isDeprecatedParameter(name),
        secured: isSecuredParameter(name),
      };
    });
}

function parseAsyncAPIParameters(protocolType?: string): ParameterMetadata[] {
  const normalizedProtocol = protocolType?.toLowerCase();
  const params: ParameterMetadata[] = [];

  if (normalizedProtocol === 'kafka' || normalizedProtocol === 'amqp') {
    params.push({
      name: 'maas.classifier.name',
      required: false,
      source: 'specification',
    });
  }

  if (normalizedProtocol === 'kafka') {
    params.push({
      name: 'topic',
      required: false,
      source: 'specification',
    });
    params.push({
      name: 'groupId',
      required: true,
      source: 'specification',
    });
  }

  if (normalizedProtocol === 'amqp') {
    params.push({
      name: 'exchange',
      required: false,
      source: 'specification',
    });
    params.push({
      name: 'queues',
      required: false,
      source: 'specification',
    });
  }

  return params;
}

function toOperationSpecFragment(specification: unknown): OperationSpecFragment | null {
  if (!specification || typeof specification !== 'object') {
    return null;
  }

  const record = specification as Record<string, unknown>;
  const parametersValue = record.parameters;
  const parameters = Array.isArray(parametersValue)
    ? parametersValue.filter((item): item is OpenAPIParameter =>
        typeof item === 'object' && item !== null)
    : undefined;

  return {
    topic: typeof record.topic === 'string' ? record.topic : undefined,
    channel: typeof record.channel === 'string' ? record.channel : undefined,
    exchange: typeof record.exchange === 'string' ? record.exchange : undefined,
    queues: typeof record.queues === 'string' ? record.queues : undefined,
    queue: typeof record.queue === 'string' ? record.queue : undefined,
    maasClassifierName: typeof record.maasClassifierName === 'string' ? record.maasClassifierName : undefined,
    groupId: typeof record.groupId === 'string' ? record.groupId : undefined,
    parameters,
  };
}

function extractSpecification(response: unknown): unknown {
  if (response && typeof response === 'object' && 'specification' in response) {
    return (response as { specification?: unknown }).specification;
  }
  return undefined;
}

function toStringMap(source?: Record<string, unknown>): Map<string, string> {
  if (!source) {
    return new Map<string, string>();
  }

  const entries = Object.entries(source).filter(
    (entry): entry is [string, string] => typeof entry[1] === 'string'
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
  idSchema,
  formContext,
}) => {
  const [specParameters, setSpecParameters] = useState<ParameterMetadata[]>([]);
  const [envParameters, setEnvParameters] = useState<Map<string, string>>(new Map());
  const [isMaasEnvironment, setIsMaasEnvironment] = useState<boolean>(false);
  const [loadedSpec, setLoadedSpec] = useState<OperationSpecFragment | null>(null);
  const [autoFilledOperationId, setAutoFilledOperationId] = useState<string | null>(null);

  const paramType = useMemo(() => determineParamType(idSchema), [idSchema]);

  const title = useMemo(() => {
    if (paramType === 'async') {
      const protocolType = formContext?.integrationOperationProtocolType?.toLowerCase();
      if (protocolType) {
        const protocolName = protocolType.charAt(0).toUpperCase() + protocolType.slice(1);
        return `${protocolName} Parameters`;
      }
    }
    return schema?.title || uiSchema?.["ui:title"] || "Items";
  }, [paramType, formContext?.integrationOperationProtocolType, schema?.title, uiSchema]);

  const rowCount = Object.entries(formData).length;
  const [collapsed, setCollapsed] = useState(!(rowCount > 0));

  const loadOperationSpecification = useCallback(async () => {
    const operationId = formContext?.integrationOperationId;
    const protocolType = formContext?.integrationOperationProtocolType?.toLowerCase();

    if (!operationId) {
      setSpecParameters([]);
      setLoadedSpec(null);
      setAutoFilledOperationId(null);
      return;
    }

    try {
      const response = await api.getOperationInfo(operationId);
      const specFragment = toOperationSpecFragment(extractSpecification(response));
      const params = parseParametersFromSpec(specFragment, paramType, protocolType);
      setSpecParameters(params);
      setLoadedSpec(specFragment);
    } catch (error: unknown) {
      const details = error instanceof Error ? error : new Error(String(error));
      console.error('Failed to load operation specification:', details);
      setSpecParameters([]);
      setLoadedSpec(null);
      setAutoFilledOperationId(null);
    }
  }, [formContext?.integrationOperationId, formContext?.integrationOperationProtocolType, paramType]);

  const loadEnvironmentParameters = useCallback(async () => {
    const systemId = formContext?.integrationSystemId;

    if (!systemId) {
      setEnvParameters(new Map());
      setIsMaasEnvironment(false);
      return;
    }

    try {
      const service: ServiceSummary = await api.getService(systemId);
      const environments: EnvironmentData[] = await api.getEnvironments(systemId);

      const activeEnv = environments.find(env => env.id === service.activeEnvironmentId)
        ?? environments[0];

      const isMaas = activeEnv?.sourceType === 'MAAS_BY_CLASSIFIER';
      setIsMaasEnvironment(Boolean(isMaas));

      setEnvParameters(toStringMap(activeEnv?.properties));
    } catch (error: unknown) {
      const details = error instanceof Error ? error : new Error(String(error));
      console.error('[EnhancedPatternPropertiesField] Failed to load environment parameters:', details);
      setEnvParameters(new Map());
      setIsMaasEnvironment(false);
    }
  }, [formContext?.integrationSystemId]);

  useEffect(() => {
    void loadOperationSpecification();
  }, [loadOperationSpecification]);

  useEffect(() => {
    void loadEnvironmentParameters();
  }, [loadEnvironmentParameters]);

  useEffect(() => {
    if (paramType !== 'async') {
      return;
    }

    let updatedData: Record<string, string> | null = null;

    if (isMaasEnvironment && formData.topic !== undefined) {
      updatedData = { ...formData };
      delete updatedData.topic;
    }

    if (!isMaasEnvironment && formData['maas.classifier.name'] !== undefined) {
      updatedData = { ...(updatedData ?? formData) };
      delete updatedData['maas.classifier.name'];
    }

    if (updatedData) {
      onChange(updatedData);
    }
  }, [formData, isMaasEnvironment, onChange, paramType]);

  // Auto-fill parameters from specification (separate effect to avoid infinite loop)
  useEffect(() => {
    if (!loadedSpec) return;

    const operationId = formContext?.integrationOperationId;

    if (operationId === autoFilledOperationId) {
      return;
    }

    const protocolType = formContext?.integrationOperationProtocolType?.toLowerCase();
    const updates: Record<string, string> = {};

    if (protocolType === 'kafka' && !formData['topic'] && !isMaasEnvironment) {
      const topicValue = loadedSpec.topic ?? loadedSpec.channel;
      if (topicValue) {
        updates['topic'] = topicValue;
      }
    }

    if (protocolType === 'amqp' && loadedSpec.exchange && !formData['exchange']) {
      updates['exchange'] = loadedSpec.exchange;
    }

    if (protocolType === 'amqp' && !formData['queues']) {
      const queuesValue = loadedSpec.queues || loadedSpec.queue;
      if (queuesValue) {
        updates['queues'] = queuesValue;
      }
    }

    if ((protocolType === 'kafka' || protocolType === 'amqp') && loadedSpec.maasClassifierName && !formData['maas.classifier.name'] && isMaasEnvironment) {
      updates['maas.classifier.name'] = loadedSpec.maasClassifierName;
    }

    if (protocolType === 'kafka' && !formData['groupId'] && loadedSpec?.groupId) {
      updates['groupId'] = loadedSpec.groupId;
    }

    if (Object.keys(updates).length > 0) {
      const updatedProps = { ...formData, ...updates };
      onChange(updatedProps);
      setAutoFilledOperationId(operationId || null);
    }
  }, [loadedSpec, formContext?.integrationOperationProtocolType, formContext?.integrationOperationId, paramType, autoFilledOperationId, formData, onChange, isMaasEnvironment]);

  const mergedParameters = useMemo((): EnhancedParameter[] => {
    const result: EnhancedParameter[] = [];
    const processed = new Set<string>();

    // Helper function to check if parameter should be hidden based on environment type
    const isHiddenParameter = (name: string): boolean => {
      if (paramType !== 'async') return false;

      if (isMaasEnvironment) {
        // In MaaS mode: hide 'topic' parameter
        return name === 'topic';
      } else {
        // In Manual mode: hide parameters with 'maas.' prefix
        return name.startsWith('maas.');
      }
    };

    // 1. Add required parameters from specification
    specParameters.filter(p => p.required && !isHiddenParameter(p.name)).forEach(p => {
      const value = formData[p.name] || envParameters.get(p.name) || '';
      result.push({
        ...p,
        value,
        isOverridden: isOverridden(p.name, formData, envParameters),
        canDelete: false,
        envDefaultValue: envParameters.get(p.name),
      });
      processed.add(p.name);
    });

    // 2. Add optional parameters from specification
    // For async/additional: ALWAYS show spec params (even if empty), unless hidden
    // For path/query: only show if present in formData
    specParameters.filter(p => !p.required && !isHiddenParameter(p.name)).forEach(p => {
      const hasFormValue = formData[p.name] !== undefined;
      const isAsyncOrAdditional = paramType === 'async' || paramType === 'additional';
      const shouldShow = hasFormValue || isAsyncOrAdditional;

      if (shouldShow) {
        const value = formData[p.name] !== undefined ? formData[p.name] : envParameters.get(p.name) || '';
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

    // 3. For async/additional: Add ALL environment parameters (except hidden)
    if (paramType === 'additional' || paramType === 'async') {
      envParameters.forEach((envValue, name) => {
        if (!processed.has(name) && !isHiddenParameter(name)) {
          const currentValue = formData[name] !== undefined ? formData[name] : envValue;
          result.push({
            name,
            value: currentValue,
            required: false,
            source: 'environment',
            isOverridden: formData[name] !== undefined && formData[name] !== envValue,
            canDelete: true,
            envDefaultValue: envValue,
            deprecated: isDeprecatedParameter(name),
            secured: isSecuredParameter(name),
          });
          processed.add(name);
        }
      });
    }

    // 4. Add custom parameters (not in spec, not in env, and not hidden)
    Object.entries(formData).forEach(([name, value]) => {
      if (!processed.has(name) && !isHiddenParameter(name)) {
        result.push({
          name,
          value,
          required: false,
          source: 'custom',
          canDelete: true,
          deprecated: isDeprecatedParameter(name),
          secured: isSecuredParameter(name),
        });
      }
    });

    return result;
  }, [specParameters, envParameters, formData, paramType, isMaasEnvironment]);

  function isOverridden(name: string, formData: Record<string, string>, envParams: Map<string, string>): boolean {
    return envParams.has(name) &&
           formData[name] !== undefined &&
           formData[name] !== envParams.get(name);
  }

  const handleAdd = () => {
    const newKey = "";
    const newData = { ...formData, [newKey]: "" };
    onChange(newData);
  };

  const handleKeyChange = (oldKey: string, newKey: string) => {
    if (oldKey === newKey) return;
    const updated = { ...formData };
    const value = updated[oldKey];
    delete updated[oldKey];
    updated[newKey] = value;
    onChange(updated);
  };

  const [localValues, setLocalValues] = useState<Record<string, string>>({});
  const debounceTimersRef = React.useRef<Record<string, ReturnType<typeof setTimeout>>>({});

  // Clean up timers on unmount
  useEffect(() => {
    const timersSnapshot = debounceTimersRef.current;
    return () => {
      Object.values(timersSnapshot).forEach(timer => clearTimeout(timer));
    };
  }, []);

  const handleValueChange = (key: string, value: string) => {
    setLocalValues(prev => ({ ...prev, [key]: value }));

    if (debounceTimersRef.current[key]) {
      clearTimeout(debounceTimersRef.current[key]);
    }

    debounceTimersRef.current[key] = setTimeout(() => {
      onChange({ ...formData, [key]: value });
      setLocalValues(prev => {
        const updated = { ...prev };
        delete updated[key];
        return updated;
      });
    }, 300);
  };

  const handleDelete = (key: string) => {
    const updated = { ...formData };
    delete updated[key];
    onChange(updated);
  };

  const restoreDefaultValue = (key: string) => {
    const defaultValue = envParameters.get(key) || '';
    onChange({ ...formData, [key]: defaultValue });
  };

  return (
    <div>
      <div className={styles.header}>
        <div
          className={styles.leftHeader}
          onClick={() => setCollapsed((s) => !s)}
        >
          <span className={styles.iconWrapper}>
            {collapsed ? <Icon name="right" /> : <Icon name="down" />}
          </span>
          <span>{title}</span>
          <span className={styles.badge}>{mergedParameters.length}</span>
        </div>

        <div>
          <Button
            size="small"
            type="text"
            icon={<Icon name="plus" />}
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
                <tr key={idx} className={param.required ? styles.requiredRow : ''}>
                  <td className={styles.td}>
                    <div className={styles.nameCell}>
                      {param.canDelete && param.source === 'custom' ? (
                        <Input
                          value={param.name}
                          onChange={(e) => handleKeyChange(param.name, e.target.value)}
                          disabled={disabled || readonly}
                          placeholder="Name"
                        />
                      ) : (
                        <span className={styles.paramName}>{param.name}</span>
                      )}
                      {(param.isOverridden || param.deprecated) && (
                        <div className={styles.labels}>
                          {param.isOverridden && <Tag className={styles.overriddenTag}>Overridden</Tag>}
                          {param.deprecated && <Tag className={styles.deprecatedTag}>Deprecated</Tag>}
                        </div>
                      )}
                    </div>
                  </td>
                  <td className={styles.td}>
                    <Input
                      value={localValues[param.name] ?? param.value}
                      onChange={(e) => handleValueChange(param.name, e.target.value)}
                      disabled={disabled || readonly}
                      placeholder="Value"
                    />
                  </td>
                  <td className={styles.td}>
                    <div className={styles.actions}>
                      {param.isOverridden && !disabled && !readonly && (
                        <Tooltip title="Restore default value">
                          <Button
                            size="small"
                            type="text"
                            icon={<Icon name="rollback" />}
                            onClick={() => restoreDefaultValue(param.name)}
                            className={styles.restoreBtn}
                          />
                        </Tooltip>
                      )}
                      {param.canDelete && !disabled && !readonly && (
                        <Button
                          size="small"
                          type="text"
                          icon={<Icon name="delete" />}
                          onClick={() => handleDelete(param.name)}
                          className={styles.deleteBtn}
                        />
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        ))}
    </div>
  );
};

export default EnhancedPatternPropertiesField;

