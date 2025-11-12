import React from 'react';
import { FieldProps, RJSFSchema } from '@rjsf/utils';
import { FormContext } from '../ChainElementModification';
import OriginalMultiSchemaField from '@rjsf/core/lib/components/fields/MultiSchemaField';

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
      return typeof value === "string" ? value.toLowerCase() : undefined;
    })();

    if (protocolType === 'grpc') {
      const SchemaField = fields.SchemaField;
      return (
        <SchemaField
          {...props}
          schema={{
            type: 'object',
            properties: {
              synchronous: { type: 'boolean', title: 'Synchronous' },
            },
          }}
          uiSchema={uiSchema}
        />
      );
    }

    const matchingIndex = oneOfOptions.findIndex((option: OneOfOption) => {
      const protocolConst: string | undefined = option?.properties?.integrationOperationProtocolType?.const?.toLowerCase();

      if (protocolType) {
        if (protocolType === 'http' || protocolType === 'soap') {
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

      if (Array.isArray(matchedOption.oneOf) && (protocolType === 'http' || protocolType === 'soap')) {
        const nestedOptions = matchedOption.oneOf;
        const nestedIndex = nestedOptions.findIndex((opt: OneOfOption) =>
          opt?.properties?.integrationOperationProtocolType?.const?.toLowerCase() === protocolType
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

