import { FieldProps } from "@rjsf/utils";
import { JSONSchema7 } from "json-schema";
import React, {  } from "react";
import { FormContext } from "../ChainElementModification";
import ChainTriggerSelectField from "./select/ChainTriggerSelectField";
import ChainTriggerUsedByField from "./ChainTriggerUsedByField";

const ChainTriggerElementIdField: React.FC<
  FieldProps<string, JSONSchema7, FormContext>
> = (props) => {
  return props.schema.readOnly ? (
    <ChainTriggerUsedByField {...props} />
  ) : (
    <ChainTriggerSelectField {...props} />
  );
};

export default ChainTriggerElementIdField;
