/// <reference types="vite/client" />
import React, {
  useEffect,
  useState,
  useMemo,
  useCallback,
  useRef,
} from "react";
import { Button, Modal, Tabs, Flex } from "antd";
import { useModalContext } from "../../../ModalContextProvider.tsx";
import styles from "./ChainElementModification.module.css";
import {
  Element,
  OperationInfo,
  PatchElementRequest,
} from "../../../api/apiTypes.ts";
import { useLibraryElement } from "../../../hooks/useLibraryElement.tsx";
import Form from "@rjsf/antd";
import yaml from "js-yaml";
import { api } from "../../../api/api.ts";
import { useElement } from "../../../hooks/useElement.tsx";
import {
  isHttpProtocol,
  normalizeProtocol,
} from "../../../misc/protocol-utils.ts";
import { useNotificationService } from "../../../hooks/useNotificationService.tsx";
import {
  ObjectFieldTemplateProps,
  RegistryWidgetsType,
  type RJSFValidationError,
  UiSchema,
} from "@rjsf/utils";
import type { JSONSchema7 } from "json-schema";
import validator from "@rjsf/validator-ajv8";
import StringAsMultipleSelectWidget from "./widget/StringAsMultipleSelectWidget.tsx";
import MultipleSelectWidget from "./widget/MultipleSelectWidget.tsx";
import SingleColumnTableWidget from "./widget/SingleColumnTableWidget.tsx";
import { DebouncedTextareaWidget } from "./widget/DebouncedTextareaWidget.tsx";
import OneOfAsSingleInputField from "./field/OneOfAsSingleInputField.tsx";
import PatternPropertiesField from "./field/PatternPropertiesField.tsx";
import {
  INITIAL_UI_SCHEMA,
  conditionalTabs,
  desiredTabOrder,
  getTabForPath,
  getStaleProtocolProperties,
} from "./ChainElementModificationConstants.ts";
import { ChainGraphNode } from "../../graph/nodes/ChainGraphNodeTypes.ts";
import MappingField from "./field/MappingField.tsx";
import CustomArrayField from "./field/CustomArrayField.tsx";
import ScriptField from "./field/ScriptField.tsx";
import JsonField from "./field/JsonField.tsx";
import ServiceField from "./field/select/ServiceField.tsx";
import SpecificationField from "./field/select/SpecificationField.tsx";
import SystemOperationField from "./field/select/SystemOperationField.tsx";
import EnhancedPatternPropertiesField from "./field/EnhancedPatternPropertiesField.tsx";
import BodyMimeTypeField from "./field/BodyMimeTypeField.tsx";
import { useModalsContext } from "../../../Modals.tsx";
import { UnsavedChangesModal } from "../UnsavedChangesModal.tsx";
import CustomOneOfField from "./field/CustomOneOfField.tsx";
import SingleSelectField from "./field/select/SingleSelectField.tsx";
import ContextServiceField from "./field/select/ContextServiceField.tsx";
import { FullscreenButton } from "../FullscreenButton.tsx";
import { useDocumentation } from "../../../hooks/useDocumentation.ts";
import { OverridableIcon } from "../../../icons/IconProvider.tsx";
import ChainTriggerElementIdField from "./field/ChainTriggerElementIdField.tsx";
import {
  type FormContext,
  buildFormContextFromProperties,
  enrichProperties as enrichPropertiesUtil,
} from "./ChainElementModificationContext.ts";
import {
  validateKafkaGroupId,
  transformValidationErrors,
  hasCriticalErrors,
} from "./formValidationHelpers.ts";
import BasePathField from "./field/BasePathField.tsx";
import ExternalRouteCheckbox from "./field/ExternalRouteCheckbox.tsx";
import ContextPathWithPrefixField from "./field/ContextPathWithPrefixField.tsx";
import DescriptionTooltipFieldTemplate from "./DescriptionTooltipFieldTemplate.tsx";
import { getSchemaModules } from "./chainElementSchemaModules.ts";
import {
  ElementNameInlineEdit,
  type ElementNameInlineEditRef,
} from "./ElementNameInlineEdit.tsx";
import { usePermissions } from "../../../permissions/usePermissions.tsx";
import { hasPermissions } from "../../../permissions/funcs.ts";
import { isVsCode } from "../../../api/rest/vscodeExtensionApi.ts";

type ElementModificationProps = {
  node: ChainGraphNode;
  chainId: string;
  elementId: string;
  onSubmit: (changedElement: Element, node: ChainGraphNode) => void;
  onClose?: () => void;
};

type TabField = {
  tab: string;
  path: string[];
  schema: JSONSchema7;
};

type CollectTabFieldsFn = (
  schema: JSONSchema7,
  path: string[],
  acc: TabField[],
  elementType?: string,
) => void;

function collectFromProperties(
  schema: JSONSchema7,
  path: string[],
  acc: TabField[],
  elementType: string | undefined,
  collect: CollectTabFieldsFn,
): void {
  if (!schema.properties || typeof schema.properties !== "object") return;
  for (const [key, subSchema] of Object.entries(schema.properties)) {
    collect(subSchema as JSONSchema7, [...path, key], acc, elementType);
  }
}

function collectFromCombinators(
  schema: JSONSchema7,
  path: string[],
  acc: TabField[],
  elementType: string | undefined,
  collect: CollectTabFieldsFn,
): void {
  const combinators = ["allOf", "anyOf", "oneOf"] as const;
  for (const keyword of combinators) {
    const arr = schema[keyword];
    if (!Array.isArray(arr)) continue;
    for (const subSchema of arr) {
      if (typeof subSchema === "object" && subSchema !== null) {
        collect(subSchema, path, acc, elementType);
      }
    }
  }
}

function collectFromArrayItems(
  schema: JSONSchema7,
  path: string[],
  acc: TabField[],
  elementType: string | undefined,
  collect: CollectTabFieldsFn,
): void {
  if (schema.type !== "array" || !schema.items) return;
  if (Array.isArray(schema.items)) {
    schema.items.forEach((item, i) =>
      collect(item as JSONSchema7, [...path, `${i}`], acc, elementType),
    );
  } else {
    collect(schema.items as JSONSchema7, [...path, "items"], acc, elementType);
  }
}

function collectFromConditional(
  schema: JSONSchema7,
  path: string[],
  acc: TabField[],
  elementType: string | undefined,
  collect: CollectTabFieldsFn,
): void {
  if (!schema.if) return;
  if (schema.then) collect(schema.then as JSONSchema7, path, acc, elementType);
  if (schema.else) collect(schema.else as JSONSchema7, path, acc, elementType);
}

// WA to hide array properties
function CustomObjectFieldTemplate({ properties }: ObjectFieldTemplateProps) {
  return <div>{properties.filter((p) => !p.hidden).map((p) => p.content)}</div>;
}

function extractAutoFillQueryParams(
  info: OperationInfo,
  protocolType: string | undefined,
  currentQueryParams: Record<string, string> | undefined,
): Record<string, string> | undefined {
  const normalized = normalizeProtocol(protocolType ?? "");
  const shouldFill =
    isHttpProtocol(normalized) &&
    (!currentQueryParams || Object.keys(currentQueryParams).length === 0);
  if (!shouldFill) return undefined;

  const parameters = (info.specification as { parameters?: unknown })
    ?.parameters;
  if (!Array.isArray(parameters)) return undefined;

  const extracted: Record<string, string> = {};
  for (const p of parameters as Array<{ in?: string; name?: string }>) {
    if (p.in === "query" && typeof p.name === "string") {
      extracted[p.name] = "";
    }
  }
  return Object.keys(extracted).length > 0 ? extracted : undefined;
}

// Static RJSF registries — defined outside the component so references are stable
// across renders and RJSF doesn't rebuild its internal registry on every re-render.
const WIDGETS: RegistryWidgetsType = {
  stringAsMultipleSelectWidget: StringAsMultipleSelectWidget,
  multipleSelectWidget: MultipleSelectWidget,
  singleColumnTableWidget: SingleColumnTableWidget,
  debouncedTextareaWidget: DebouncedTextareaWidget,
  textarea: DebouncedTextareaWidget,
};

const FIELDS = {
  OneOfField: CustomOneOfField,
  oneOfAsSingleInputField: OneOfAsSingleInputField,
  patternPropertiesField: PatternPropertiesField,
  enhancedPatternPropertiesField: EnhancedPatternPropertiesField,
  mappingField: MappingField,
  customArrayField: CustomArrayField,
  scriptField: ScriptField,
  jsonField: JsonField,
  serviceField: ServiceField,
  specificationField: SpecificationField,
  systemOperationField: SystemOperationField,
  bodyMimeTypeField: BodyMimeTypeField,
  singleSelectField: SingleSelectField,
  contextServiceField: ContextServiceField,
  chainTriggerElementIdField: ChainTriggerElementIdField,
  basePathField: BasePathField,
  externalRouteCheckbox: ExternalRouteCheckbox,
  contextPathWithPrefixField: ContextPathWithPrefixField,
};

const TEMPLATES = {
  ObjectFieldTemplate: CustomObjectFieldTemplate,
  FieldTemplate: DescriptionTooltipFieldTemplate,
};

const FORM_DEFAULT_STATE_BEHAVIOR = {
  allOf: "populateDefaults" as const,
  arrayMinItems: {
    populate: "never" as const,
  },
};

export const ChainElementModification: React.FC<ElementModificationProps> = ({
  node,
  chainId,
  elementId,
  onSubmit,
  onClose,
}) => {
  const { isLoading: libraryElementIsLoading, libraryElement } =
    useLibraryElement(node.data.elementType);
  const [isLoading, setIsLoading] = useState(false);
  const { closeContainingModal } = useModalContext();
  const { updateElement } = useElement();
  const notificationService = useNotificationService();
  const { openElementDoc } = useDocumentation();
  const [schema, setSchema] = useState<JSONSchema7>({});
  const [formData, setFormData] = useState<Record<string, unknown>>({});
  const [formContext, setFormContext] = useState<FormContext>({});

  const [activeKey, setActiveKey] = useState<string>();
  const { showModal } = useModalsContext();
  const [hasChanges, setHasChanges] = useState<boolean>(false);
  const [isFullscreen, setIsFullscreen] = useState<boolean>(false);
  const [elementDescription, setElementDescription] = useState<string>("");
  const [missingRequiredParamsMap, setMissingRequiredParamsMap] = useState<
    Record<string, string[]>
  >({});
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const formRef = useRef<any>(null);
  const elementNameRef = useRef<ElementNameInlineEditRef>(null);
  const isInitializingRef = useRef(true);
  const hasUserEditedFormRef = useRef(false);
  const hasUserInteractedRef = useRef(false);

  const permissions = usePermissions();
  const canEditChain = hasPermissions(permissions, { chain: ["update"] });

  const reportMissingRequiredParams = useCallback(
    (key: string, params: string[]) => {
      setMissingRequiredParamsMap((prev) => {
        const prevParams = prev[key];
        if (
          prevParams?.length === params.length &&
          prevParams?.every((p, i) => p === params[i])
        ) {
          return prev;
        }
        return { ...prev, [key]: params };
      });
    },
    [],
  );

  const handleFullscreen = useCallback(() => {
    setIsFullscreen((prevValue) => !prevValue);
  }, []);

  const schemaModules = useMemo(() => getSchemaModules(), []);

  useEffect(() => {
    isInitializingRef.current = true;
    hasUserEditedFormRef.current = false;
    hasUserInteractedRef.current = false;
    const path = `/node_modules/@netcracker/qip-schemas/assets/${node.data.elementType}.schema.yaml`;
    const raw = schemaModules[path];

    if (!raw) {
      notificationService.errorWithDetails(
        "Schema not found",
        `Schema for elementType "${node.data.elementType}" was not found.`,
        undefined,
      );
      return;
    }

    try {
      const parsed = yaml.load(raw) as JSONSchema7;

      // Extract description before setting schema to avoid mutating state
      if (parsed.description) {
        setElementDescription(parsed.description.trim());
      }
      // eslint-disable-next-line @typescript-eslint/no-unused-vars
      const { description: _, ...schemaWithoutDescription } = parsed;
      setSchema(schemaWithoutDescription as JSONSchema7);

      const initialFormData = {
        ...node.data,
        name: node.data.label,
        id: node.id,
      };
      setFormData(initialFormData);

      const formProperties = initialFormData.properties as Record<
        string,
        unknown
      >;

      const initialContext = buildFormContextFromProperties(
        formProperties,
        node.data.elementType,
        chainId,
        (updatedProperties: Record<string, unknown>) => {
          if (
            updatedProperties.integrationOperationProtocolType !== undefined
          ) {
            updatedProperties.integrationOperationProtocolType =
              normalizeProtocol(
                updatedProperties.integrationOperationProtocolType as string,
              );

            // Clean up properties that belong to other protocols
            const newProtocol =
              updatedProperties.integrationOperationProtocolType as string;
            for (const prop of getStaleProtocolProperties(
              newProtocol,
              updatedProperties,
            )) {
              updatedProperties[prop] = undefined;
            }
          }
          // For http-trigger, method is stored as httpMethodRestrict, not integrationOperationMethod
          if (
            node.data.elementType === "http-trigger" &&
            "integrationOperationMethod" in updatedProperties
          ) {
            updatedProperties.httpMethodRestrict =
              updatedProperties.integrationOperationMethod;
            delete updatedProperties.integrationOperationMethod;
          }
          setFormContext((prevContext) =>
            enrichPropertiesUtil(prevContext, updatedProperties),
          );

          // Directly clean formData.properties for keys not tracked in FormContext
          setFormData((prevFormData) => {
            const props = {
              ...(prevFormData.properties as Record<string, unknown>),
            };
            let changed = false;
            for (const [key, value] of Object.entries(updatedProperties)) {
              if (value === undefined && key in props) {
                delete props[key];
                changed = true;
              }
            }
            return changed
              ? { ...prevFormData, properties: props }
              : prevFormData;
          });

          if (
            !isInitializingRef.current &&
            (hasUserEditedFormRef.current || hasUserInteractedRef.current)
          ) {
            setHasChanges(true);
          }
        },
      );

      // Functional update so that operation schemas already loaded by the
      // centralized loader (see useEffect below) are preserved across
      // re-runs of this mount effect — otherwise a stale node reference
      // change would wipe out schemas and break auto-apply in MappingField.
      setFormContext((prev) => ({
        ...initialContext,
        reportMissingRequiredParams,
        operationSpecification: prev.operationSpecification,
        operationRequestSchema: prev.operationRequestSchema,
        operationResponseSchemas: prev.operationResponseSchemas,
      }));
    } catch (err) {
      console.error("Failed to parse schema:", err);
      notificationService.errorWithDetails(
        "Failed to load schema",
        `Error while parsing schema for "${node.data.elementType}"`,
        err,
      );
    }
  }, [
    node.data,
    node.id,
    notificationService,
    schemaModules,
    chainId,
    reportMissingRequiredParams,
  ]);

  useEffect(() => {
    if (!schema || Object.keys(schema).length === 0) return;
    if (!formData || Object.keys(formData).length === 0) return;

    const timer = globalThis.setTimeout(() => {
      isInitializingRef.current = false;
    }, 0);

    return () => {
      globalThis.clearTimeout(timer);
    };
  }, [schema, formData]);

  // WA Trigger initial validation after form mounts so required fields are highlighted
  useEffect(() => {
    if (schema && Object.keys(schema).length > 0) {
      setTimeout(() => {
        // eslint-disable-next-line @typescript-eslint/no-unsafe-call, @typescript-eslint/no-unsafe-member-access
        formRef.current?.validateForm?.();
      }, 50);
    }
  }, [schema]);

  const handleClose = useCallback(() => {
    closeContainingModal();
    onClose?.();
  }, [closeContainingModal, onClose]);

  const markUserInteracted = useCallback(() => {
    hasUserInteractedRef.current = true;
  }, []);

  /** RJSF logs `console.error('Form validation failed', …)` when `onError` is omitted. */
  const handleRjsfFormErrors = useCallback((_errors: RJSFValidationError[]) => {
    // Programmatic validateForm() after mount; errors are shown in the form UI.
  }, []);

  const handleFormChange = useCallback(
    (e: { formData?: Record<string, unknown> }) => {
      const nextFormData = e.formData;
      if (!nextFormData) return;
      setFormData(nextFormData);
      if (
        !isInitializingRef.current &&
        (hasUserEditedFormRef.current || hasUserInteractedRef.current)
      ) {
        hasUserEditedFormRef.current = true;
        setHasChanges(true);
      }
    },
    [],
  );

  /**
   * Centralized OperationInfo loader.
   *
   * Rationale: `SystemOperationField` is only mounted on the "Endpoint" tab
   * (in non-Endpoint tabs `ui:field: "hidden"` is applied by `uiSchemaForTab`),
   * so we can't rely on it to fetch schemas — otherwise custom fields on
   * "Prepare Request" / "Handle Response" would see empty request/response
   * schemas when the user opens those tabs first. This effect lives in the
   * parent, which is always mounted, and publishes schemas through `setFormContext`
   * on every real change of `integrationOperationId`.
   *
   * Query parameters are auto-filled (HTTP only) only when the form doesn't
   * have them yet — we don't want to overwrite values the user previously saved.
   */
  const loadedOperationIdRef = useRef<string | null>(null);
  // Snapshotted via refs (not deps) so protocol / query-param updates don't
  // re-trigger the loader — that would create a feedback loop via the
  // auto-fill for integrationOperationQueryParameters below.
  const protocolTypeRef = useRef<string | undefined>(undefined);
  const queryParamsRef = useRef<Record<string, string> | undefined>(undefined);
  protocolTypeRef.current = formContext.integrationOperationProtocolType;
  queryParamsRef.current = formContext.integrationOperationQueryParameters;

  useEffect(() => {
    const operationId = formContext.integrationOperationId;
    if (!operationId) {
      // Operation cleared — drop stale schemas so MappingField doesn't
      // auto-apply them to a freshly-selected different operation later.
      if (loadedOperationIdRef.current !== null) {
        loadedOperationIdRef.current = null;
        setFormContext((prev) => ({
          ...prev,
          operationSpecification: {},
          operationRequestSchema: {},
          operationResponseSchemas: {},
        }));
      }
      return;
    }
    if (loadedOperationIdRef.current === operationId) return;

    const controller = new AbortController();
    loadedOperationIdRef.current = operationId;

    void (async () => {
      try {
        const info = await api.getOperationInfo(operationId);
        if (controller.signal.aborted) return;

        const queryParamsUpdate = extractAutoFillQueryParams(
          info,
          protocolTypeRef.current,
          queryParamsRef.current,
        );

        setFormContext((prev) => ({
          ...prev,
          operationSpecification: info.specification ?? {},
          operationRequestSchema: info.requestSchema ?? {},
          operationResponseSchemas: info.responseSchemas ?? {},
          ...(queryParamsUpdate
            ? { integrationOperationQueryParameters: queryParamsUpdate }
            : {}),
        }));
      } catch (error) {
        if (controller.signal.aborted) return;
        notificationService.requestFailed(
          "Failed to load operation info",
          error,
        );
        setFormContext((prev) => ({
          ...prev,
          operationSpecification: {},
          operationRequestSchema: {},
          operationResponseSchemas: {},
        }));
      }
    })();

    return () => controller.abort();
  }, [formContext.integrationOperationId, notificationService]);

  // Sync formContext → formData.properties whenever context changes.
  // `enrichPropertiesUtil` also filters out metadata-only keys (see
  // METADATA_ONLY_CONTEXT_KEYS in Context.ts); we additionally null them here
  // so that the serialized-comparison below doesn't see stale references.
  useEffect(() => {
    setFormData((prevFormData) => {
      const contextProperties = {
        ...formContext,
        integrationOperationProtocolType: normalizeProtocol(
          formContext?.integrationOperationProtocolType as string,
        ),
        updateContext: undefined,
        elementType: undefined,
        chainId: undefined,
        reportMissingRequiredParams: undefined,
        operationSpecification: undefined,
        operationRequestSchema: undefined,
        operationResponseSchemas: undefined,
      };
      const enrichedProps = enrichPropertiesUtil(
        prevFormData.properties as Record<string, unknown>,
        contextProperties,
      );

      if (
        JSON.stringify(enrichedProps) ===
        JSON.stringify(prevFormData.properties)
      ) {
        return prevFormData;
      }

      return {
        ...prevFormData,
        properties: enrichedProps,
      };
    });
  }, [formContext]);

  // Memoized error transformer — only re-created when the protocol type changes,
  // not on every formContext update, to avoid triggering unnecessary AJV validation.
  const transformErrors = useMemo(
    () =>
      transformValidationErrors({
        integrationOperationProtocolType:
          formContext.integrationOperationProtocolType,
      }),
    [formContext.integrationOperationProtocolType],
  );

  const validationErrors = useMemo(() => {
    if (!schema || !formData || Object.keys(schema).length === 0) return [];
    const result = validator.validateFormData(formData, schema);
    return transformErrors(result.errors);
  }, [formData, schema, transformErrors]);

  const kafkaError = useMemo(
    () =>
      validateKafkaGroupId(
        formData?.properties as Record<string, unknown> | undefined,
        node.data.elementType,
      ),
    [formData?.properties, node.data.elementType],
  );

  const hasMissingRequiredParams = Object.values(missingRequiredParamsMap).some(
    (params) => params.length > 0,
  );

  const isSaveDisabled =
    hasCriticalErrors(validationErrors) ||
    !!kafkaError ||
    hasMissingRequiredParams;

  const handleNameSave = useCallback(
    async (newName: string) => {
      // Inline name edit is a name-only operation. Send the pristine
      // description/properties from node.data so we don't silently persist
      // the user's unsaved form draft along with the rename — otherwise
      // Cancel wouldn't revert those drafted changes.
      const request: PatchElementRequest = {
        name: newName,
        description: node.data.description,
        type: node.data.elementType,
        parentElementId: node.parentId,
        properties: node.data.properties as Record<string, unknown>,
      };
      const changedElement = await updateElement(chainId, elementId, request);
      if (changedElement) {
        setFormData((prev) => ({ ...prev, name: newName }));
        const elementWithProperties = {
          ...changedElement,
          properties: node.data.properties,
        } as Element;
        onSubmit?.(elementWithProperties, node);
      }
    },
    [node, chainId, elementId, updateElement, onSubmit],
  );

  const handleOk = useCallback(async () => {
    const pendingName = elementNameRef.current?.syncIfEditing?.();
    const nameToUse =
      (pendingName?.trim()?.length ?? 0) > 0
        ? (pendingName as string).trim()
        : (formData.name as string);

    setIsLoading(true);
    try {
      const request: PatchElementRequest = {
        name: nameToUse,
        description: formData.description as string,
        type: node.data.elementType,
        parentElementId: node.parentId,
        properties: formData.properties as Record<string, unknown>,
      };
      const changedElement: Element | undefined = await updateElement(
        chainId,
        elementId,
        request,
      );
      if (changedElement) {
        // API response doesn't include properties, so preserve them from formData
        const elementWithProperties = {
          ...changedElement,
          properties: formData.properties,
        } as Element;
        onSubmit?.(elementWithProperties, node);
      }
    } catch (error) {
      notificationService.errorWithDetails(
        "Save element failed",
        "Failed to save element",
        error,
      );
    } finally {
      setIsLoading(false);
      handleClose();
    }
  }, [
    formData,
    chainId,
    elementId,
    node,
    onSubmit,
    notificationService,
    updateElement,
    handleClose,
  ]);

  const handleCheckUnsavedAndClose = useCallback(() => {
    if (hasChanges) {
      showModal({
        component: (
          <UnsavedChangesModal
            onYes={() => {
              void handleOk();
            }}
            onNo={() => {
              handleClose();
              setHasChanges(false);
            }}
          />
        ),
      });
    } else {
      handleClose();
    }
  }, [showModal, handleClose, handleOk, hasChanges]);

  const collectTabFields = useCallback(function collect(
    schema: JSONSchema7,
    path: string[] = [],
    acc: TabField[] = [],
    elementType?: string,
  ): TabField[] {
    const currentPath = path.join(".");
    collectFromProperties(schema, path, acc, elementType, collect);
    collectFromCombinators(schema, path, acc, elementType, collect);
    collectFromArrayItems(schema, path, acc, elementType, collect);
    collectFromConditional(schema, path, acc, elementType, collect);
    if (path.length > 0) {
      const tab = getTabForPath(currentPath, elementType) || "Parameters";
      acc.push({ tab, path, schema });
    }
    return acc;
  }, []);

  const tabFields = useMemo(() => {
    if (!schema) return [];
    return collectTabFields(schema, [], [], node.data.elementType);
  }, [schema, collectTabFields, node.data.elementType]);

  const uniqueTabs = useMemo(() => {
    const rawTabs = new Set(tabFields.map((f) => f.tab));
    // Pass only the protocol type that conditionalTabs.isVisible actually reads,
    // so tabs don't recalculate on unrelated formContext property changes.
    const ctx: Record<string, unknown> = {
      integrationOperationProtocolType:
        formContext.integrationOperationProtocolType,
    };
    return desiredTabOrder.filter(rawTabs.has.bind(rawTabs)).filter((tab) => {
      const condition = conditionalTabs.find((ct) => ct.tab === tab);
      return !condition || condition.isVisible(ctx);
    });
  }, [tabFields, formContext.integrationOperationProtocolType]);

  useEffect(() => {
    if (
      uniqueTabs.length > 0 &&
      (!activeKey || !uniqueTabs.includes(activeKey))
    ) {
      setActiveKey(uniqueTabs[0]);
    }
  }, [uniqueTabs, activeKey]);

  const applyHiddenUiSchema = useCallback(
    (target: UiSchema, hidden: UiSchema) => {
      for (const key in hidden) {
        if (
          hidden[key] &&
          typeof hidden[key] === "object" &&
          !Array.isArray(hidden[key])
        ) {
          if (!target[key]) target[key] = {};
          applyHiddenUiSchema(target[key] as UiSchema, hidden[key] as UiSchema);
        } else {
          target[key] = hidden[key] as UiSchema;
        }
      }
    },
    [],
  );

  const buildUiSchemaForTab = useCallback(
    (
      tabFields: TabField[],
      tab: string,
      initialUiSchema: UiSchema,
    ): UiSchema => {
      // Helper to set value at path in UI Schema
      function setPath(obj: UiSchema, path: string[], value: object) {
        let curr = obj;
        for (let i = 0; i < path.length - 1; i++) {
          curr[path[i]] = (curr[path[i]] as UiSchema) || {};
          curr = (curr[path[i]] as UiSchema) || {};
        }
        curr[path.at(-1) ?? ""] = value;
      }

      const visiblePathsSet = new Set(
        tabFields.filter((f) => f.tab === tab).map((f) => f.path.join(".")),
      );

      const allPaths = tabFields.map((f) => f.path);

      const finalUiSchema: UiSchema = structuredClone(initialUiSchema || {});

      for (const path of allPaths) {
        const pathKey = path.join(".");
        // Don't hide the "properties" object itself, only its children
        if (pathKey === "properties") continue;

        if (!visiblePathsSet.has(pathKey)) {
          setPath(finalUiSchema, path, { "ui:widget": "hidden" });
        }
      }

      return finalUiSchema;
    },
    [],
  );

  const uiSchemaForTab = useCallback(
    (initialUiSchema: UiSchema, activeKey?: string) => {
      const ui = structuredClone(initialUiSchema || {});

      if (!ui.properties || typeof ui.properties !== "object") {
        ui.properties = {};
      }

      const props = ui.properties as Record<string, unknown>;

      if (node.data.elementType === "checkpoint") {
        props["httpMethodRestrict"] = { "ui:widget": "hidden" };
      }

      if (node.data.elementType === "http-trigger") {
        props["contextPath"] = { "ui:field": "contextPathWithPrefixField" };
        props["integrationOperationPath"] = { "ui:field": "basePathField" };
        props["externalRoute"] = { "ui:field": "externalRouteCheckbox" };
      }

      if (activeKey && activeKey !== "Endpoint") {
        props["ui:fieldReplacesAnyOrOneOf"] = true;
        props["ui:field"] = "hidden";
      } else if (
        activeKey === "Endpoint" &&
        node.data.elementType === "service-call"
      ) {
        props["ui:fieldReplacesAnyOrOneOf"] = true;
        // eslint-disable-next-line react/prop-types
        delete props["ui:field"];
      } else {
        // eslint-disable-next-line react/prop-types
        delete props["ui:fieldReplacesAnyOrOneOf"];
        // eslint-disable-next-line react/prop-types
        delete props["ui:field"];
      }

      ui.properties = props;

      return ui;
    },
    [node.data.elementType],
  );

  const uiSchema = useMemo(() => {
    if (!schema) return undefined;

    const baseUi: UiSchema = uiSchemaForTab(INITIAL_UI_SCHEMA, activeKey);
    return buildUiSchemaForTab(tabFields, activeKey ?? "", baseUi);
  }, [tabFields, activeKey, schema, uiSchemaForTab, buildUiSchemaForTab]);

  const handleTabChange = (newTab: string) => {
    setActiveKey(newTab);
  };

  return (
    <Modal
      open
      title={
        <Flex
          align="center"
          gap={4}
          wrap={false}
          justify="space-between"
          style={{ width: "100%" }}
        >
          <div
            className={styles["modal-title"]}
            style={{ minWidth: 0, flex: 1 }}
          >
            <ElementNameInlineEdit
              ref={elementNameRef}
              value={(formData.name as string) ?? node.data.label ?? ""}
              typeLabel={
                libraryElement?.title ??
                node.data.typeTitle ??
                node.data.elementType
              }
              onSave={handleNameSave}
              disabled={!canEditChain || libraryElementIsLoading}
            />
          </div>
          <Flex
            align="center"
            gap={4}
            wrap={false}
            style={{ flexShrink: 0, marginLeft: "auto" }}
          >
            {!isVsCode && (
              <Button
                icon={<OverridableIcon name="questionCircle" />}
                onClick={() => openElementDoc(node.data.elementType)}
                type="text"
                title="Help"
                size="small"
              />
            )}
            <FullscreenButton
              isFullscreen={isFullscreen}
              onClick={handleFullscreen}
            />
            <Button
              icon={<OverridableIcon name="close" />}
              onClick={handleCheckUnsavedAndClose}
              type="text"
              title="Close"
              size="small"
            />
          </Flex>
        </Flex>
      }
      onCancel={handleCheckUnsavedAndClose}
      closable={false}
      maskClosable={false}
      loading={libraryElementIsLoading}
      style={isFullscreen ? { top: 0, margin: 0, padding: 0 } : {}}
      footer={
        <Flex justify="space-between" align="center">
          <span>{elementDescription}</span>
          <Flex gap={8}>
            <Button
              key="submit"
              type="primary"
              form="elementModificationForm"
              htmlType={"submit"}
              loading={isLoading}
              disabled={isSaveDisabled || !canEditChain}
              onClick={() => void handleOk()}
            >
              Save
            </Button>
            <Button key="cancel" onClick={handleCheckUnsavedAndClose}>
              Cancel
            </Button>
          </Flex>
        </Flex>
      }
      classNames={{
        footer: styles["modal-footer"],
        content: isFullscreen
          ? [styles["modal"], styles["modal-fullscreen"]].join(" ")
          : styles["modal"],
        body: isFullscreen
          ? [styles["modal-body"], styles["modal-body-fullscreen"]].join(" ")
          : styles["modal-body"],
        header: styles["modal-header"],
      }}
    >
      {schema && activeKey && (
        <>
          <Tabs
            activeKey={activeKey}
            onChange={handleTabChange}
            items={uniqueTabs.map((tab) => ({ key: tab, label: tab }))}
          />
          <div
            onClickCapture={markUserInteracted}
            onKeyDownCapture={markUserInteracted}
            onMouseDownCapture={markUserInteracted}
          >
            <Form
              ref={formRef}
              className={styles["parameters-form"]}
              schema={schema}
              formData={formData}
              validator={validator}
              uiSchema={uiSchema}
              transformErrors={transformErrors}
              onError={handleRjsfFormErrors}
              liveValidate={"onChange"}
              showErrorList={false}
              experimental_defaultFormStateBehavior={
                FORM_DEFAULT_STATE_BEHAVIOR
              }
              formContext={formContext}
              templates={TEMPLATES}
              fields={FIELDS}
              widgets={WIDGETS}
              onChange={handleFormChange}
            />
          </div>
        </>
      )}
    </Modal>
  );
};
