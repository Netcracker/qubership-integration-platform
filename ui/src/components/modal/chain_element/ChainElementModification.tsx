/// <reference types="vite/client" />
import React, { useEffect, useState, useMemo, useCallback } from "react";
import { Button, Modal, Tabs } from "antd";
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
import { DebouncedTextareaWidget } from "./widget/DebouncedTextareaWidget.tsx";
import { DebouncedTextWidget } from "./widget/DebouncedTextWidget.tsx";
import OneOfAsSingleInputField from "./field/OneOfAsSingleInputField.tsx";
import PatternPropertiesField from "./field/PatternPropertiesField.tsx";
import {
  INITIAL_UI_SCHEMA,
  pathToTabMap,
  desiredTabOrder,
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
        contextServiceId: getOptionalString(
          formProperties.contextServiceId,
        ),
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
        integrationOperationSkipEmptyQueryParameters: formProperties.integrationOperationSkipEmptyQueryParameters as boolean | undefined,
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
    ): TabField[] => {
      const currentPath = path.join(".");

      if (schema.properties && typeof schema.properties === "object") {
        for (const [key, subSchema] of Object.entries(schema.properties)) {
          collectTabFields(subSchema as JSONSchema7, [...path, key], acc);
        }
      }

      const combinators = ["allOf", "anyOf", "oneOf"] as const;
      for (const keyword of combinators) {
        if (Array.isArray(schema[keyword])) {
          for (const subSchema of schema[keyword]) {
            if (typeof subSchema === "object" && subSchema !== null) {
              collectTabFields(subSchema, path, acc);
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
            );
          }
        } else {
          collectTabFields(
            schema.items as JSONSchema7,
            [...path, "items"],
            acc,
          );
        }
      }

      if (schema.if) {
        if (schema.then) {
          collectTabFields(schema.then as JSONSchema7, path, acc);
        }
        if (schema.else) {
          collectTabFields(schema.else as JSONSchema7, path, acc);
        }
      }

      if (path.length > 0) {
        const tab = pathToTabMap[currentPath] || "Parameters";
        acc.push({ tab, path, schema });
      }

      return acc;
    },
    [],
  );

  const tabFields = useMemo(() => {
    if (!schema) return [];
    return collectTabFields(schema);
  }, [schema, collectTabFields]);

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
      schema: JSONSchema7,
      initialUiSchema: UiSchema,
    ): UiSchema => {
      const hiddenUiSchema: UiSchema = {};

      function setPath(obj: UiSchema, path: string[], value: object) {
        let curr = obj;
        for (let i = 0; i < path.length - 1; i++) {
          curr[path[i]] = (curr[path[i]] as UiSchema) || {};
          curr = (curr[path[i]] as UiSchema) || {};
        }
        curr[path[path.length - 1]] = value;
      }

      const visiblePaths = tabFields
        .filter((f) => f.tab === tab)
        .map((f) => f.path.map((p) => ["properties", p]).flat());

      function buildHiddenUiSchema(schema: JSONSchema7, path: string[] = []) {
        const combinators = ["allOf", "anyOf", "oneOf"] as const;
        for (const keyword of combinators) {
          if (Array.isArray(schema[keyword])) {
            for (const subSchema of schema[keyword]) {
              if (typeof subSchema === "object" && subSchema !== null) {
                buildHiddenUiSchema(subSchema, path);
              }
            }
          }
        }

        if (schema.if) {
          if (schema.then) {
            buildHiddenUiSchema(schema.then as JSONSchema7, path);
          }
          if (schema.else) {
            buildHiddenUiSchema(schema.else as JSONSchema7, path);
          }
        }

        if (schema && schema.properties) {
          for (const key of Object.keys(schema.properties)) {
            const currentPath = [...path, key];
            const fullPath = currentPath.map((p) => ["properties", p]).flat();

            const isVisible = visiblePaths.some(
              (v) => v.join(".") === fullPath.join("."),
            );

            const subSchema = schema.properties[key];
            const isObjectSchema =
              typeof subSchema === "object" &&
              "type" in subSchema &&
              subSchema.type === "object";

            if ((isObjectSchema && isVisible) || key === "properties") {
              buildHiddenUiSchema(subSchema as JSONSchema7, currentPath);
            } else if (!isVisible) {
              setPath(hiddenUiSchema, currentPath, { "ui:widget": "hidden" });
            }
          }
        }
      }

      buildHiddenUiSchema(schema);

      const finalUiSchema: UiSchema = structuredClone(initialUiSchema || {});
      applyHiddenUiSchema(finalUiSchema, hiddenUiSchema);
      return finalUiSchema;
    },
    [applyHiddenUiSchema],
  );

  const widgets: RegistryWidgetsType = {
    stringAsMultipleSelectWidget: StringAsMultipleSelectWidget,
    multipleSelectWidget: MultipleSelectWidget,
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
    return buildUiSchemaForTab(tabFields, activeKey ?? "", schema, baseUi);
  }, [tabFields, activeKey, schema, uiSchemaForTab, buildUiSchemaForTab]);

  const handleTabChange = (newTab: string) => {
    setActiveKey(newTab);
  };

  return (
    <Modal
      open
      title={title}
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
        content: isFullscreen ?  [styles["modal"], styles["modal-fullscreen"]].join(' ') : styles["modal"],
        body: isFullscreen ? [styles["modal-body"], styles["modal-body-fullscreen"]].join(' ') : styles["modal-body"],
        header: styles["modal-header"],
      }}
    >
      {schema && activeKey && (
        <>
          <FullscreenButton isFullscreen={isFullscreen} onClick={handleFullscreen} />
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
