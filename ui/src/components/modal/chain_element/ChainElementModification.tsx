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
import CustomSelectWidget from "./widget/CustomSelectWidget.tsx";
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
import AnyOfAsSingleSelectField from "./field/AnyOfAsSingleSelectField.tsx";
import MappingField from "./field/MappingField.tsx";
import CustomArrayField from "./field/CustomArrayField.tsx";
import ScriptField from "./field/ScriptField.tsx";
import JsonField from "./field/JsonField.tsx";
import ServiceField from "./field/ServiceField.tsx";
import SpecificationField from "./field/SpecificationField.tsx";
import SystemOperationField from "./field/SystemOperationField.tsx";
import CustomOneOfField from "./field/CustomOneOfField.tsx";
import EnhancedPatternPropertiesField from "./field/EnhancedPatternPropertiesField.tsx";
import BodyMimeTypeField from "./field/BodyMimeTypeField.tsx";

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

export type FormContext = {
  integrationOperationId?: string;
  integrationOperationPath?: string;
  integrationOperationMethod?: string;
  integrationOperationProtocolType?: string;
  elementType?: string;
  integrationSystemId?: string;
  integrationSpecificationGroupId?: string;
  integrationSpecificationId?: string;
  systemType?: string;
  updateContext(newContext: Record<string, unknown>): void;
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
  console.log(`[ChainElementModification] Component RENDER for element: ${elementId}, type: ${node.data.elementType}`);

  const normalizeProtocol = useCallback((protocol: unknown): string | undefined => {
    if (typeof protocol !== "string") {
      return undefined;
    }
    const trimmed = protocol.trim();
    return trimmed ? trimmed.toLowerCase() : undefined;
  }, []);

  const { isLoading: libraryElementIsLoading, libraryElement } =
    useLibraryElement(node.data.elementType);
  const [isLoading, setIsLoading] = useState(false);
  const { closeContainingModal } = useModalContext();
  const { updateElement } = useElement();
  const notificationService = useNotificationService();
  const [title, setTitle] = useState(constructTitle(`${node.data.label}`));
  const [schema, setSchema] = useState<JSONSchema7>({});
  const [formData, setFormData] = useState<Record<string, unknown>>({});
  const [formContext, setFormContext] = useState<Record<
      string,
      unknown
    >>({});

  const [activeKey, setActiveKey] = useState<string>();

  useEffect(() => {
    setTitle(constructTitle(`${node.data.label}`, libraryElement?.title));
  }, [libraryElement, node]);

  const enrichProperties = useCallback((
    targetProperties: Record<string, unknown>,
    sourceProperties: Record<string, unknown>,
  ): Record<string, unknown> => {
    const result = { ...targetProperties };
    Object.entries(sourceProperties).forEach(([key, value]) => {
      if (key === "integrationOperationProtocolType") {
        value = normalizeProtocol(value);
      }
      if (value === undefined) {
        delete result[key];
      } else {
        result[key] = value === null ? undefined : value;
      }
    });
    return result;
  }, [normalizeProtocol]);

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
      // eslint-disable-next-line @typescript-eslint/no-unsafe-call, @typescript-eslint/no-unsafe-member-access
      const parsed = yaml.load(raw) as JSONSchema7;
      setSchema(parsed);

      const initialFormData = {
        ...node.data,
        name: node.data.label,
        id: node.id,
      };
      setFormData(initialFormData);

      const formProperties = initialFormData.properties as Record<string, unknown>;

      setFormContext({
        integrationOperationId: formProperties.integrationOperationId,
        integrationOperationProtocolType: normalizeProtocol(formProperties.integrationOperationProtocolType),
        elementType: node.data.elementType,
        integrationSystemId: formProperties.integrationSystemId,
        systemType: formProperties.systemType,
        integrationSpecificationGroupId:
          formProperties.integrationSpecificationGroupId,
        integrationSpecificationId: formProperties.integrationSpecificationId,
        integrationOperationPath: formProperties.integrationOperationPath,
        integrationOperationMethod: formProperties.integrationOperationMethod,
        bodyFormData: formProperties.bodyFormData,
        updateContext: (updatedProperties: Record<string, unknown>) => {
          if (updatedProperties.integrationOperationProtocolType !== undefined) {
            updatedProperties.integrationOperationProtocolType = normalizeProtocol(
              updatedProperties.integrationOperationProtocolType
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
  }, [node.data, node.id, notificationService, schemaModules, enrichProperties]);

  const handleClose = useCallback(() => {
    closeContainingModal();
    onClose?.();
  }, [closeContainingModal, onClose]);

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
  }, [formData, chainId, elementId, node, onSubmit, notificationService, updateElement, handleClose]);

  const collectTabFields = useCallback((
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
        collectTabFields(schema.items as JSONSchema7, [...path, "items"], acc);
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
  }, []);

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
          formContext?.integrationOperationProtocolType
        ),
        updateContext: undefined,
      };
      const enrichedProps = enrichProperties(
        prevFormData.properties as Record<string, unknown>,
        newContextProperties,
      );

      // Only update if properties actually changed
      if (JSON.stringify(enrichedProps) === JSON.stringify(prevFormData.properties)) {
        return prevFormData;
      }

      return {
        ...prevFormData,
        properties: enrichedProps,
      };
    });
  }, [formContext, enrichProperties]);

  const applyHiddenUiSchema = useCallback((target: UiSchema, hidden: UiSchema) => {
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
  }, []);

  const buildUiSchemaForTab = useCallback((
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
          const isObjectSchema = typeof subSchema === 'object' &&
                                 'type' in subSchema && subSchema.type === "object";

          if ((isObjectSchema && isVisible) || key === "properties") {
            buildHiddenUiSchema(
              subSchema as JSONSchema7,
              currentPath,
            );
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
  }, [applyHiddenUiSchema]);

  const widgets: RegistryWidgetsType = {
    stringAsMultipleSelectWidget: StringAsMultipleSelectWidget,
    customSelectWidget: CustomSelectWidget,
    debouncedTextareaWidget: DebouncedTextareaWidget,
    TextWidget: DebouncedTextWidget,
    textarea: DebouncedTextareaWidget,
  };

  const uiSchemaForTab = useCallback((initialUiSchema: UiSchema, activeKey?: string) => {
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
    } else if (activeKey === "Endpoint" && node.data.elementType === "service-call") {
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
  }, [node.data.elementType]);

  const uiSchema = useMemo(() => {
    if (!schema) return [];

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
      onCancel={handleClose}
      maskClosable={false}
      loading={libraryElementIsLoading}
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
        <Button key="cancel" onClick={handleClose}>
          Cancel
        </Button>,
      ]}
      classNames={{
        footer: styles["modal-footer"],
        content: styles["modal"],
        body: styles["modal-body"],
      }}
    >
      {schema && activeKey && (
        <>
          <Tabs
            activeKey={activeKey}
            onChange={handleTabChange}
            className={"flex-tabs"}
            items={uniqueTabs.map((tab) => ({ key: tab, label: tab }))}
          />
          <Form
            schema={schema}
            formData={formData}
            validator={validator}
            uiSchema={uiSchema}
            transformErrors={(errors) => {
              // Suppress oneOf error when protocol is grpc (no matching oneOf branch by design)
              const proto = normalizeProtocol(formContext?.integrationOperationProtocolType);
              if (proto === 'grpc') {
                return errors.filter((e) => e.name !== 'oneOf');
              }
              return errors;
            }}
            liveValidate={false}
            experimental_defaultFormStateBehavior={{
              allOf: "populateDefaults",
              mergeDefaultsIntoFormData: "useFormDataIfPresent",
            }}
            formContext={
              formContext
            }
            templates={{
              ObjectFieldTemplate: CustomObjectFieldTemplate,
              ErrorListTemplate,
            }}
            fields={{
              OneOfField: CustomOneOfField,
              oneOfAsSingleInputField: OneOfAsSingleInputField,
              anyOfAsSingleSelectField: AnyOfAsSingleSelectField,
              patternPropertiesField: PatternPropertiesField,
              enhancedPatternPropertiesField: EnhancedPatternPropertiesField,
              mappingField: MappingField,
              // @ts-expect-error - CustomArrayField has incompatible prop types
              customArrayField: CustomArrayField,
              scriptField: ScriptField,
              jsonField: JsonField,
              serviceField: ServiceField,
              specificationField: SpecificationField,
              systemOperationField: SystemOperationField,
              bodyMimeTypeField: BodyMimeTypeField,
            }}
            widgets={widgets}
            onChange={(e) => {
              setFormData(e.formData as Record<string, object>);
            }}
          />
        </>
      )}
    </Modal>
  );
};
