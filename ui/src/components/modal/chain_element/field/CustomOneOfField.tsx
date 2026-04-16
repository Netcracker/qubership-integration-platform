import React from "react";
import { FieldProps, RJSFSchema } from "@rjsf/utils";
import { getDefaultRegistry } from "@rjsf/core";
import { FormContext } from "../ChainElementModificationContext";
import BeforeOneOfField from "./oneof/BeforeOneOfField";
import HttpTriggerOneOfField from "./oneof/HttpTriggerOneOfField";
import ProtocolOneOfField from "./oneof/ProtocolOneOfField";

const OriginalOneOfField = getDefaultRegistry().fields.OneOfField;

const BEFORE_FIELD_ID = "root_properties_before";
const PROPERTIES_FIELD_ID = "root_properties";

const CustomOneOfField: React.FC<
  FieldProps<Record<string, unknown>, RJSFSchema, FormContext>
> = (props) => {
  const { registry, schema, fieldPathId } = props;
  const elementType = registry.formContext?.elementType;
  const hasOneOf = Array.isArray(schema.oneOf);

  if (fieldPathId?.$id === BEFORE_FIELD_ID && hasOneOf) {
    return <BeforeOneOfField {...props} />;
  }

  if (fieldPathId?.$id === PROPERTIES_FIELD_ID && hasOneOf) {
    return elementType === "http-trigger" ? (
      <HttpTriggerOneOfField {...props} />
    ) : (
      <ProtocolOneOfField {...props} />
    );
  }

  return <OriginalOneOfField {...props} />;
};

export default CustomOneOfField;
