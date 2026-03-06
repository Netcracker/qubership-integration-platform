import React, { useCallback, useMemo, useState } from "react";
import { FieldProps, RJSFSchema } from "@rjsf/utils";
import { Select } from "antd";
import { FormContext } from "../ChainElementModificationContext";
import {
  isHttpProtocol,
  normalizeProtocol,
} from "../../../../misc/protocol-utils";
import { getDefaultRegistry } from "@rjsf/core";

const defaultRegistry = getDefaultRegistry();
const OriginalOneOfField = defaultRegistry.fields.OneOfField;

interface OneOfOption {
  title?: string;
  properties?: {
    integrationOperationProtocolType?: {
      const?: string;
    };
    [key: string]: unknown;
  };
  required?: string[];
  oneOf?: OneOfOption[];

  [key: string]: unknown;
}

/**
 * Http-trigger has a oneOf between Custom and ImplementedService endpoints.
 * This component provides a dropdown to switch and hides httpMethodRestrict
 * for ImplementedService (it's set programmatically via updateContext).
 */
const HttpTriggerOneOfField: React.FC<
  FieldProps<Record<string, unknown>, RJSFSchema, FormContext>
> = (props) => {
  const { registry, schema, uiSchema, formData, fieldPathId } = props;
  const { fields } = registry;
  const oneOfOptions = schema.oneOf as OneOfOption[];

  const fd = formData as Record<string, unknown>;
  const initialIndex = fd?.integrationSystemId ? 1 : 0;
  const [selectedIndex, setSelectedIndex] = useState(initialIndex);

  const implementedServiceFields = [
    "integrationSystemId",
    "integrationSpecificationId",
    "integrationSpecificationGroupId",
    "integrationOperationId",
    "integrationOperationPath",
    "systemType",
  ];

  const customFields = ["contextPath"];

  const handleSwitch = useCallback(
    (newIndex: number) => {
      setSelectedIndex(newIndex);

      const cleaned = { ...(formData as Record<string, unknown>) };
      const fieldsToRemove =
        newIndex === 0
          ? implementedServiceFields
          : customFields;

      for (const key of fieldsToRemove) {
        delete cleaned[key];
      }
      // Always reset httpMethodRestrict on switch
      delete cleaned.httpMethodRestrict;

      props.onChange(cleaned, fieldPathId?.path ?? []);
    },
    [formData, props, fieldPathId],
  );

  const dropdownOptions = useMemo(
    () =>
      oneOfOptions.map((opt, i) => ({
        label: opt.title ?? `Option ${i + 1}`,
        value: i,
      })),
    [oneOfOptions],
  );

  const matchedSchema = useMemo(() => {
    const option = oneOfOptions[selectedIndex];
    if (!option) return { type: "object", properties: {} } as RJSFSchema;

    // For ImplementedService, hide httpMethodRestrict (set programmatically)
    if (selectedIndex === 1 && option.properties) {
      // eslint-disable-next-line @typescript-eslint/no-unused-vars
      const { httpMethodRestrict, ...restProps } = option.properties;
      return {
        ...option,
        properties: restProps,
        required: option.required?.filter((r) => r !== "httpMethodRestrict"),
      } as RJSFSchema;
    }

    return option as RJSFSchema;
  }, [oneOfOptions, selectedIndex]);

  const SchemaField = fields.SchemaField;

  return (
    <>
      <Select
        value={selectedIndex}
        onChange={handleSwitch}
        options={dropdownOptions}
        style={{ marginBottom: 16, width: "100%" }}
      />
      <SchemaField {...props} schema={matchedSchema} uiSchema={uiSchema} />
    </>
  );
};

const CustomOneOfField: React.FC<
  FieldProps<Record<string, unknown>, RJSFSchema, FormContext>
> = (props) => {
  const { registry, schema, uiSchema, fieldPathId, formData } = props;

  const { fields } = registry;
  const context = registry.formContext;

  const isPropertiesField = fieldPathId?.$id === "root_properties";

  if (
    isPropertiesField &&
    Array.isArray(schema.oneOf) &&
    context?.elementType === "http-trigger"
  ) {
    return <HttpTriggerOneOfField {...props} />;
  }

  if (
    isPropertiesField &&
    Array.isArray(schema.oneOf) &&
    context?.elementType !== "http-trigger"
  ) {
    const oneOfOptions = schema.oneOf as OneOfOption[];
    const protocolType = (() => {
      const value =
        context?.integrationOperationProtocolType ??
        (formData as Record<string, unknown>)?.integrationOperationProtocolType;
      return normalizeProtocol(value as string);
    })();

    if (protocolType === "grpc") {
      const SchemaField = fields.SchemaField;
      return (
        <SchemaField
          {...props}
          schema={{
            type: "object",
            properties: {},
          }}
          uiSchema={uiSchema}
        />
      );
    }

    const matchingIndex = oneOfOptions.findIndex((option: OneOfOption) => {
      const protocolConst = normalizeProtocol(
        option?.properties?.integrationOperationProtocolType?.const as string,
      );

      if (protocolType) {
        if (isHttpProtocol(protocolType)) {
          return Array.isArray(option?.oneOf) || protocolConst === protocolType;
        }
        return protocolConst === protocolType;
      }

      return !protocolConst;
    });

    if (matchingIndex >= 0) {
      const matchedOption = oneOfOptions[matchingIndex];
      let matchedSchema: Record<string, unknown> = { ...matchedOption };
      const SchemaField = fields.SchemaField;

      if (Array.isArray(matchedOption.oneOf) && isHttpProtocol(protocolType)) {
        const nestedOptions = matchedOption.oneOf;
        const nestedIndex = nestedOptions.findIndex(
          (opt: OneOfOption) =>
            normalizeProtocol(
              opt?.properties?.integrationOperationProtocolType
                ?.const as string,
            ) === protocolType,
        );

        if (nestedIndex >= 0) {
          const nestedOption = nestedOptions[nestedIndex];
          matchedSchema = {
            ...matchedOption,
            ...nestedOption,
            properties: {
              ...(matchedOption.properties || {}),
              ...(nestedOption.properties || {}),
            },
          };
          delete matchedSchema.oneOf;
        }
      }

      return (
        <SchemaField
          {...props}
          schema={matchedSchema as RJSFSchema}
          uiSchema={uiSchema}
        />
      );
    }
  }

  return <OriginalOneOfField {...props} />;
};

export default CustomOneOfField;
