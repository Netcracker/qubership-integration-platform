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
import { Element, PatchElementRequest } from "../../../api/apiTypes.ts";
import { useLibraryElement } from "../../../hooks/useLibraryElement.tsx";
import Form from "@rjsf/antd";
import yaml from "js-yaml";
import { useElement } from "../../../hooks/useElement.tsx";
import { useNotificationService } from "../../../hooks/useNotificationService.tsx";
import {
  ObjectFieldTemplateProps,
  RegistryWidgetsType,
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
import { normalizeProtocol } from "../../../misc/protocol-utils.ts";
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

function constructTitle(name: string, type?: string): string {
  return type ? `${name} (${type})` : `${name}`;
}

// WA to hide array properties
function CustomObjectFieldTemplate({ properties }: ObjectFieldTemplateProps) {
  return <div>{properties.filter((p) => !p.hidden).map((p) => p.content)}</div>;
}

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
  const [title, setTitle] = useState(constructTitle(`${node.data.label}`));
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

  useEffect(() => {
    setTitle(constructTitle(`${node.data.label}`, libraryElement?.title));
  }, [libraryElement, node]);

  const schemaModules = useMemo(
    () =>
      import.meta.glob(
        "/node_modules/@netcracker/qip-schemas/assets/*.schema.yaml",
        { as: "raw", eager: true },
      ),
    [],
  );

  useEffect(() => {
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
          }
          setFormContext((prevContext) =>
            enrichPropertiesUtil(prevContext, updatedProperties),
          );
          setHasChanges(true);
        },
      );

      setFormContext({ ...initialContext, reportMissingRequiredParams });
    } catch (err) {
      console.error("Failed to parse schema:", err);
      notificationService.errorWithDetails(
        "Failed to load schema",
        `Error while parsing schema for "${node.data.elementType}"`,
        err,
      );
    }
  }, [node.data, node.id, notificationService, schemaModules, chainId]);

  // WA Trigger initial validation after form mounts so required fields are highlighted
  useEffect(() => {
    if (schema && Object.keys(schema).length > 0) {
      setTimeout(() => {
        formRef.current?.validateForm();
      }, 50);
    }
  }, [schema]);

  const handleClose = useCallback(() => {
    closeContainingModal();
    onClose?.();
  }, [closeContainingModal, onClose]);

  const handleCheckUnsavedAndClose = useCallback(() => {
    if (hasChanges) {
      showModal({
        component: (
          <UnsavedChangesModal
            onYes={() => {
              handleClose();
              setHasChanges(false);
            }}
          />
        ),
      });
    } else {
      handleClose();
    }
  }, [showModal, handleClose, hasChanges]);

  // Sync formContext → formData.properties whenever context changes
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

  const validationErrors = useMemo(() => {
    if (!schema || !formData || Object.keys(schema).length === 0) return [];
    const result = validator.validateFormData(formData, schema);
    return transformValidationErrors(formContext)(result.errors);
  }, [formData, schema, formContext]);

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

  const handleOk = useCallback(async () => {
    setIsLoading(true);
    try {
      const request: PatchElementRequest = {
        name: formData.name as string,
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

  const collectTabFields = useCallback(
    (
      schema: JSONSchema7,
      path: string[] = [],
      acc: TabField[] = [],
      elementType?: string,
    ): TabField[] => {
      const currentPath = path.join(".");

      if (schema.properties && typeof schema.properties === "object") {
        for (const [key, subSchema] of Object.entries(schema.properties)) {
          collectTabFields(
            subSchema as JSONSchema7,
            [...path, key],
            acc,
            elementType,
          );
        }
      }

      const combinators = ["allOf", "anyOf", "oneOf"] as const;
      for (const keyword of combinators) {
        if (Array.isArray(schema[keyword])) {
          for (const subSchema of schema[keyword]) {
            if (typeof subSchema === "object" && subSchema !== null) {
              collectTabFields(subSchema, path, acc, elementType);
            }
          }
        }
      }

      if (schema.type === "array" && schema.items) {
        if (Array.isArray(schema.items)) {
          for (let i = 0; i < schema.items.length; i++) {
            collectTabFields(
              schema.items[i] as JSONSchema7,
              [...path, `${i}`],
              acc,
              elementType,
            );
          }
        } else {
          collectTabFields(
            schema.items as JSONSchema7,
            [...path, "items"],
            acc,
            elementType,
          );
        }
      }

      if (schema.if) {
        if (schema.then) {
          collectTabFields(schema.then as JSONSchema7, path, acc, elementType);
        }
        if (schema.else) {
          collectTabFields(schema.else as JSONSchema7, path, acc, elementType);
        }
      }

      if (path.length > 0) {
        const tab = getTabForPath(currentPath, elementType) || "Parameters";
        acc.push({ tab, path, schema });
      }

      return acc;
    },
    [],
  );

  const tabFields = useMemo(() => {
    if (!schema) return [];
    return collectTabFields(schema, [], [], node.data.elementType);
  }, [schema, collectTabFields, node.data.elementType]);

  const uniqueTabs = useMemo(() => {
    const rawTabs = new Set(tabFields.map((f) => f.tab));
    return desiredTabOrder.filter(rawTabs.has.bind(rawTabs)).filter((tab) => {
      const condition = conditionalTabs.find((ct) => ct.tab === tab);
      return !condition || condition.isVisible(formContext);
    });
  }, [tabFields, formContext]);

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
        curr[path[path.length - 1]] = value;
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

  const widgets: RegistryWidgetsType = {
    stringAsMultipleSelectWidget: StringAsMultipleSelectWidget,
    multipleSelectWidget: MultipleSelectWidget,
    singleColumnTableWidget: SingleColumnTableWidget,
    debouncedTextareaWidget: DebouncedTextareaWidget,
    textarea: DebouncedTextareaWidget,
  };

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
        <Flex align="center" gap={8}>
          <span>{title}</span>
          {
            <Button
              icon={<OverridableIcon name="questionCircle" />}
              onClick={() => openElementDoc(node.data.elementType)}
              type="text"
              title="Help"
              size="small"
            />
          }
        </Flex>
      }
      onCancel={handleCheckUnsavedAndClose}
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
              disabled={isSaveDisabled}
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
          <FullscreenButton
            isFullscreen={isFullscreen}
            onClick={handleFullscreen}
          />
          <Tabs
            activeKey={activeKey}
            onChange={handleTabChange}
            items={uniqueTabs.map((tab) => ({ key: tab, label: tab }))}
          />
          <Form
            ref={formRef}
            className={styles["parameters-form"]}
            schema={schema}
            formData={formData}
            validator={validator}
            uiSchema={uiSchema}
            transformErrors={transformValidationErrors(formContext)}
            liveValidate={"onChange"}
            showErrorList={false}
            experimental_defaultFormStateBehavior={{
              allOf: "populateDefaults",
              mergeDefaultsIntoFormData: "useFormDataIfPresent",
            }}
            formContext={formContext}
            templates={{
              ObjectFieldTemplate: CustomObjectFieldTemplate,
            }}
            fields={{
              OneOfField: CustomOneOfField, //Rewrite default oneOfField
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
            }}
            widgets={widgets}
            onChange={(e) => {
              setFormData(e.formData as Record<string, unknown>);
              setHasChanges(true);
            }}
          />
        </>
      )}
    </Modal>
  );
};
