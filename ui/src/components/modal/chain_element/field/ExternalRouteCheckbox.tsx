import { FieldProps } from "@rjsf/utils";
import { JSONSchema7 } from "json-schema";
import { FormContext } from "../ChainElementModification";
import { Checkbox, CheckboxChangeEvent } from "antd";
import React, { useCallback } from "react";

const ExternalRouteCheckbox: React.FC<
  FieldProps<string, JSONSchema7, FormContext>
> = ({ registry }) => {
  const checked = registry.formContext?.externalRoute;
  const updateContext = registry.formContext?.updateContext;

  const handleChange = useCallback(
    (e: CheckboxChangeEvent) => {
      updateContext?.({ externalRoute: e.target.checked });
    },
    [updateContext],
  );

  return (
    <Checkbox checked={checked} onChange={handleChange}>
      External route
    </Checkbox>
  );
};

export default ExternalRouteCheckbox;
