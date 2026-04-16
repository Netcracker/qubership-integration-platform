import React, { useMemo } from "react";
import { FieldProps, RJSFSchema } from "@rjsf/utils";
import { getDefaultRegistry } from "@rjsf/core";
import { FormContext } from "../../ChainElementModificationContext";
import {
  isGrpcProtocol,
  normalizeProtocol,
} from "../../../../../misc/protocol-utils";
import {
  EMPTY_ONEOF_SCHEMA,
  OneOfOption,
  hasNoProtocolConstraint,
  protocolMatchesOption,
} from "./oneof-utils";

const OriginalOneOfField = getDefaultRegistry().fields.OneOfField;

/**
 * Resolves the rendered option for `root_properties` oneOf based on the
 * currently-selected operation's protocol. Used for non-http-trigger
 * elements (service-call, etc.) where the user cannot pick the oneOf
 * option directly — it follows the operation. Falls back to RJSF's default
 * OneOfField when no option matches.
 */
const ProtocolOneOfField: React.FC<
  FieldProps<Record<string, unknown>, RJSFSchema, FormContext>
> = (props) => {
  const { registry, schema, uiSchema, formData } = props;
  const { fields, formContext } = registry;
  const SchemaField = fields.SchemaField;
  const oneOfOptions = schema.oneOf as OneOfOption[];

  const protocolValue =
    formContext?.integrationOperationProtocolType ??
    formData?.integrationOperationProtocolType;
  const protocolType = useMemo(
    () => normalizeProtocol(protocolValue as string),
    [protocolValue],
  );

  const matchingIndex = useMemo(() => {
    if (protocolType && isGrpcProtocol(protocolType)) return -2; // sentinel: gRPC special-cased
    return oneOfOptions.findIndex((option) =>
      protocolType
        ? protocolMatchesOption(option, protocolType)
        : hasNoProtocolConstraint(option),
    );
  }, [oneOfOptions, protocolType]);

  if (matchingIndex === -2) {
    return (
      <SchemaField
        {...props}
        schema={EMPTY_ONEOF_SCHEMA}
        uiSchema={uiSchema}
      />
    );
  }

  if (matchingIndex < 0) return <OriginalOneOfField {...props} />;

  return (
    <SchemaField
      {...props}
      schema={oneOfOptions[matchingIndex] as RJSFSchema}
      uiSchema={uiSchema}
    />
  );
};

export default ProtocolOneOfField;
