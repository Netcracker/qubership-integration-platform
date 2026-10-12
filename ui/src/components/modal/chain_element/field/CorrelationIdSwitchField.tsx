import React, { useCallback } from "react";
import { Checkbox, CheckboxChangeEvent } from "antd";
import { FieldProps } from "@rjsf/utils";
import { JSONSchema7 } from "json-schema";
import { FormContext } from "../ChainElementModificationContext";

// The engine reads the position and the name, not this switch, so turning it off clears both.
const CorrelationIdSwitchField: React.FC<
  FieldProps<boolean, JSONSchema7, FormContext>
> = ({ formData, onChange, fieldPathId, schema, disabled, readonly }) => {
  const handleChange = useCallback(
    (e: CheckboxChangeEvent) => {
      const path = fieldPathId.path;
      onChange(e.target.checked, path);
      if (!e.target.checked) {
        const parent = path.slice(0, -1);
        onChange(undefined, [...parent, "correlationIdPosition"]);
        onChange(undefined, [...parent, "correlationIdName"]);
      }
    },
    [fieldPathId, onChange],
  );

  return (
    <Checkbox
      checked={formData === true}
      disabled={disabled || readonly}
      onChange={handleChange}
    >
      {schema.title}
    </Checkbox>
  );
};

export default CorrelationIdSwitchField;
