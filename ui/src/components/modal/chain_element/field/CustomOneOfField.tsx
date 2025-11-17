import React from "react";
import { FieldProps, RJSFSchema } from "@rjsf/utils";
import { FormContext } from "../ChainElementModification";
import OriginalMultiSchemaField from "@rjsf/core/lib/components/fields/MultiSchemaField";
import {
  isHttpProtocol,
  normalizeProtocol,
} from "../../../../misc/protocol-utils";

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

const CustomOneOfField: React.FC<FieldProps<Record<string, unknown>, RJSFSchema, FormContext>> = (props) => {
  const {
    registry,
    schema,
    uiSchema,
    idSchema,
    formContext,
    formData,
  } = props;

  const { fields } = registry;
  const context = formContext;
  const isPropertiesField = idSchema?.$id === 'root_properties';

  if (isPropertiesField && Array.isArray(schema.oneOf)) {
    const oneOfOptions = schema.oneOf as OneOfOption[];
    const protocolType = (() => {
      const value =
        context?.integrationOperationProtocolType ??
        (formData as Record<string, unknown>)?.integrationOperationProtocolType;
      return normalizeProtocol(value);
    })();

    if (protocolType === "grpc") {
      const SchemaField = fields.SchemaField;
      return (
        <SchemaField
          {...props}
          schema={{
            type: 'object',
            properties: {},
          }}
          uiSchema={uiSchema}
        />
      );
    }

    const matchingIndex = oneOfOptions.findIndex((option: OneOfOption) => {
      const protocolConst = normalizeProtocol(
        option?.properties?.integrationOperationProtocolType?.const,
      );

      if (protocolType) {
        if (isHttpProtocol(protocolType)) {
          return Array.isArray(option?.oneOf) || protocolConst === protocolType;
        }
        return protocolConst === protocolType;
      }

      if (protocolConst) {
        return false;
      }

      return true;
    });

    if (matchingIndex >= 0) {
      const matchedOption = oneOfOptions[matchingIndex];
      let matchedSchema: Record<string, unknown> = { ...matchedOption };
      const SchemaField = fields.SchemaField;

      if (Array.isArray(matchedOption.oneOf) && isHttpProtocol(protocolType)) {
        const nestedOptions = matchedOption.oneOf;
        const nestedIndex = nestedOptions.findIndex((opt: OneOfOption) =>
          normalizeProtocol(
            opt?.properties?.integrationOperationProtocolType?.const,
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

  return <OriginalMultiSchemaField {...props} />;
};

export default CustomOneOfField;

