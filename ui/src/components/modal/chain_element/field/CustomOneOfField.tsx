import React from "react";
import { FieldProps, RJSFSchema } from "@rjsf/utils";
import { FormContext } from "../ChainElementModification";
import {
  isHttpProtocol,
  normalizeProtocol,
} from "../../../../misc/protocol-utils";
import { getDefaultRegistry } from "@rjsf/core";

const defaultRegistry = getDefaultRegistry();
const OriginalOneOfField = defaultRegistry.fields.OneOfField;

interface OneOfOption {
  properties?: {
    integrationOperationProtocolType?: {
      const?: string;
    };
    [key: string]: unknown;
  };
  oneOf?: OneOfOption[];

  [key: string]: unknown;
}

const CustomOneOfField: React.FC<
  FieldProps<Record<string, unknown>, RJSFSchema, FormContext>
> = (props) => {
  const { registry, schema, uiSchema, fieldPathId, formData } = props;

  const { fields } = registry;
  const context = registry.formContext;

  const isPropertiesField = fieldPathId.$id === "root_properties";
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
