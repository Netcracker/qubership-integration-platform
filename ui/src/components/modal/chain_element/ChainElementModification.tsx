/// <reference types="vite/client" />
import React, { useEffect, useState, useMemo, useCallback } from "react";
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
import { DebouncedTextWidget } from "./widget/DebouncedTextWidget.tsx";
import OneOfAsSingleInputField from "./field/OneOfAsSingleInputField.tsx";
import PatternPropertiesField from "./field/PatternPropertiesField.tsx";
import {
  INITIAL_UI_SCHEMA,
  desiredTabOrder,
  getTabForPath,
} from "./ChainElementModificationConstants.ts";
import { ChainGraphNode } from "../../graph/nodes/ChainGraphNodeTypes.ts";
import AnyOfAsSingleSelectField from "./field/select/AnyOfAsSingleSelectField.tsx";
import MappingField from "./field/MappingField.tsx";
import CustomArrayField from "./field/CustomArrayField.tsx";
import ScriptField from "./field/ScriptField.tsx";
import JsonField from "./field/JsonField.tsx";
import ServiceField from "./field/select/ServiceField.tsx";
import SpecificationField from "./field/select/SpecificationField.tsx";
import SystemOperationField from "./field/select/SystemOperationField.tsx";
import EnhancedPatternPropertiesField from "./field/EnhancedPatternPropertiesField.tsx";
import BodyMimeTypeField from "./field/BodyMimeTypeField.tsx";
import type { BodyFormEntry } from "../../../misc/body-form-data-utils.ts";
import { toBodyFormData } from "../../../misc/body-form-data-utils.ts";
import { useModalsContext } from "../../../Modals.tsx";
import { UnsavedChangesModal } from "../UnsavedChangesModal.tsx";
import {
  isKafkaProtocol,
  normalizeProtocol,
} from "../../../misc/protocol-utils.ts";
import CustomOneOfField from "./field/CustomOneOfField.tsx";
import SingleSelectField from "./field/select/SingleSelectField.tsx";
import ContextServiceField from "./field/select/ContextServiceField.tsx";
import { FullscreenButton } from "../FullscreenButton.tsx";
import { useDocumentation } from "../../../hooks/useDocumentation.ts";
import { OverridableIcon } from "../../../icons/IconProvider.tsx";
import { isVsCode } from "../../../api/rest/vscodeExtensionApi.ts";
import ChainTriggerElementIdField from "./field/ChainTriggerElementIdField.tsx";
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

const getOptionalString = (value: unknown): string | undefined =>
  typeof value === "string" ? value : undefined;

const validateKafkaGroupId = (
  properties?: Record<string, unknown>,
  elementType?: string,
) => {
  if (!properties || elementType !== "async-api-trigger") return;
  if (
    !isKafkaProtocol(properties["integrationOperationProtocolType"] as string)
  )
    return;

  const asyncProperties = properties["integrationOperationAsyncProperties"] as
    | Record<string, unknown>
    | undefined;
  const groupIdValue = asyncProperties?.["groupId"];
  const groupId = typeof groupIdValue === "string" ? groupIdValue.trim() : "";
  if (!groupId) {
    throw new Error("Group ID is required for Kafka operations.");
  }
};

export type FormContext = {
  contextServiceId?: string;
  integrationOperationId?: string;
  integrationOperationPath?: string;
  integrationOperationMethod?: string;
  integrationOperationPathParameters?: Record<string, string>;
  integrationOperationQueryParameters?: Record<string, string>;
  integrationOperationProtocolType?: string;
  elementType?: string;
  integrationSystemId?: string;
  integrationSpecificationGroupId?: string;
  integrationSpecificationId?: string;
  systemType?: string;
  integrationOperationSkipEmptyQueryParameters?: boolean;
  bodyFormData?: BodyFormEntry[];
  synchronousGrpcCall?: boolean;
  chainId?: string;
  externalRoute?: boolean;
  updateContext?: (newContext: Record<string, unknown>) => void;
};

function constructTitle(name: string, type?: string): string {
  return type ? `${name} (${type})` : `${name}`;
}

function ErrorListTemplate() {
  return <div></div>;
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

  const handleFullscreen = useCallback(() => {
    setIsFullscreen((prevValue) => !prevValue);
  }, []);

  useEffect(() => {
    setTitle(constructTitle(`${node.data.label}`, libraryElement?.title));
  }, [libraryElement, node]);

  const enrichProperties = useCallback(
    (
      targetProperties: Record<string, unknown>,
      sourceProperties: Record<string, unknown>,
    ): Record<string, unknown> => {
      const result = { ...targetProperties };
      Object.entries(sourceProperties).forEach(([key, value]) => {
        if (key === "integrationOperationProtocolType") {
          value = normalizeProtocol(value as string);
        }
        if (value === undefined) {
          delete result[key];
        } else {
          result[key] = value === null ? undefined : value;
        }
      });
      return result;
    },
    [],
  );

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
      setSchema(parsed);

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

      setFormContext({
        contextServiceId: getOptionalString(formProperties.contextServiceId),
        integrationOperationId: getOptionalString(
          formProperties.integrationOperationId,
        ),
        integrationOperationProtocolType: normalizeProtocol(
          formProperties.integrationOperationProtocolType as string,
        ),
        elementType: node.data.elementType,
        integrationSystemId: getOptionalString(
          formProperties.integrationSystemId,
        ),
        systemType: getOptionalString(formProperties.systemType),
        integrationSpecificationGroupId: getOptionalString(
          formProperties.integrationSpecificationGroupId,
        ),
        integrationSpecificationId: getOptionalString(
          formProperties.integrationSpecificationId,
        ),
        integrationOperationPath: getOptionalString(
          formProperties.integrationOperationPath,
        ),
        integrationOperationMethod: getOptionalString(
          formProperties.integrationOperationMethod,
        ),
        integrationOperationPathParameters:
          formProperties.integrationOperationPathParameters as Record<
            string,
            string
          >,
        integrationOperationQueryParameters:
          formProperties.integrationOperationQueryParameters as Record<
            string,
            string
          >,
        bodyFormData: toBodyFormData(formProperties.bodyFormData),
        synchronousGrpcCall: Boolean(formProperties.synchronousGrpcCall),
        chainId: chainId,
        integrationOperationSkipEmptyQueryParameters:
          formProperties.integrationOperationSkipEmptyQueryParameters as
            | boolean
            | undefined,
        externalRoute: Boolean(formProperties.externalRoute),
        updateContext: (updatedProperties: Record<string, unknown>) => {
          if (
            updatedProperties.integrationOperationProtocolType !== undefined
          ) {
            updatedProperties.integrationOperationProtocolType =
              normalizeProtocol(
                updatedProperties.integrationOperationProtocolType as string,
              );
          }
          setFormContext((prevContext) =>
            enrichProperties(prevContext, updatedProperties),
          );

          setFormData((prevFormData) => ({
            ...prevFormData,
            properties: enrichProperties(
              prevFormData.properties as Record<string, unknown>,
              updatedProperties,
            ),
          }));
          setHasChanges(true);
        },
      });
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
    enrichProperties,
    chainId,
  ]);

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

  const handleOk = useCallback(async () => {
    try {
      validateKafkaGroupId(
        formData?.properties as Record<string, unknown> | undefined,
        node.data.elementType,
      );
    } catch (error) {
      const message =
        error instanceof Error
          ? error.message
          : typeof error === "string"
            ? error
            : "Unknown error";
      notificationService.info("Validation failed", message);
      return;
    }

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
        onSubmit?.(changedElement, node);
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
    return desiredTabOrder.filter(rawTabs.has.bind(rawTabs));
  }, [tabFields]);

  useEffect(() => {
    if (uniqueTabs.length > 0 && !activeKey) {
      setActiveKey(uniqueTabs[0]);
    }
  }, [uniqueTabs, activeKey]);

  useEffect(() => {
    setFormData((prevFormData) => {
      const newContextProperties = {
        ...formContext,
        integrationOperationProtocolType: normalizeProtocol(
          formContext?.integrationOperationProtocolType as string,
        ),
        updateContext: undefined,
      };
      const enrichedProps = enrichProperties(
        prevFormData.properties as Record<string, unknown>,
        newContextProperties,
      );

      // Only update if properties actually changed
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
  }, [formContext, enrichProperties]);

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
    TextWidget: DebouncedTextWidget,
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
          {!isVsCode && (
            <Button
              icon={<OverridableIcon name="questionCircle" />}
              onClick={() => openElementDoc(node.data.elementType)}
              type="text"
              title="Help"
              size="small"
            />
          )}
        </Flex>
      }
      onCancel={handleCheckUnsavedAndClose}
      maskClosable={false}
      loading={libraryElementIsLoading}
      style={isFullscreen ? { top: 0, margin: 0, padding: 0 } : {}}
      footer={[
        <Button
          key="submit"
          type="primary"
          form="elementModificationForm"
          htmlType={"submit"}
          loading={isLoading}
          onClick={() => void handleOk()}
        >
          Save
        </Button>,
        <Button key="cancel" onClick={handleCheckUnsavedAndClose}>
          Cancel
        </Button>,
      ]}
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
            className={styles["parameters-form"]}
            schema={schema}
            formData={formData}
            validator={validator}
            uiSchema={uiSchema}
            transformErrors={(errors) => {
              // Suppress oneOf error when protocol is grpc (no matching oneOf branch by design)
              const proto = normalizeProtocol(
                formContext?.integrationOperationProtocolType as string,
              );
              if (proto === "grpc") {
                return errors.filter((e) => e.name !== "oneOf");
              }
              return errors;
            }}
            liveValidate={false}
            experimental_defaultFormStateBehavior={{
              allOf: "populateDefaults",
              mergeDefaultsIntoFormData: "useFormDataIfPresent",
            }}
            formContext={formContext}
            templates={{
              ObjectFieldTemplate: CustomObjectFieldTemplate,
              ErrorListTemplate,
            }}
            fields={{
              OneOfField: CustomOneOfField, //Rewrite default oneOfField
              oneOfAsSingleInputField: OneOfAsSingleInputField,
              anyOfAsSingleSelectField: AnyOfAsSingleSelectField,
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
              const newFormData = e.formData as Record<string, object>;
              if (
                newFormData.type &&
                formData.type &&
                JSON.stringify(newFormData) !== JSON.stringify(formData)
              ) {
                setHasChanges(true);
              }
              setFormData(newFormData);
            }}
          />
        </>
      )}
    </Modal>
  );
};
