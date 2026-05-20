import React, { useCallback, useState } from "react";
import { FieldProps, RJSFSchema } from "@rjsf/utils";
import { FormContext } from "../../ChainElementModificationContext";
import OneOfSelectField from "./OneOfSelectField";
import { OneOfOption } from "./oneof-utils";

const IMPLEMENTED_SERVICE_FIELDS = [
  "integrationSystemId",
  "integrationSpecificationId",
  "integrationSpecificationGroupId",
  "integrationOperationId",
  "integrationOperationPath",
  "systemType",
];

const CUSTOM_FIELDS = ["contextPath"];

/** httpMethodRestrict is set programmatically via updateContext for
 *  ImplementedService, so we strip it from the rendered schema. */
function transformHttpTriggerSchema(
  option: OneOfOption,
  index: number,
): RJSFSchema {
  if (index !== 1 || !option.properties) return option as RJSFSchema;
  const restProps = { ...option.properties };
  delete restProps.httpMethodRestrict;
  return {
    ...option,
    properties: restProps,
    required: option.required?.filter((r) => r !== "httpMethodRestrict"),
  } as RJSFSchema;
}

/**
 * Http-trigger has a oneOf between Custom and ImplementedService endpoints.
 */
const HttpTriggerOneOfField: React.FC<
  FieldProps<Record<string, unknown>, RJSFSchema, FormContext>
> = (props) => {
  const { schema, formData, fieldPathId, onChange } = props;
  const oneOfOptions = schema.oneOf as OneOfOption[];

  const [selectedIndex, setSelectedIndex] = useState(() =>
    formData?.integrationSystemId ? 1 : 0,
  );

  const handleSwitch = useCallback(
    (newIndex: number) => {
      setSelectedIndex(newIndex);
      const cleaned = { ...formData };
      const fieldsToRemove =
        newIndex === 0 ? IMPLEMENTED_SERVICE_FIELDS : CUSTOM_FIELDS;
      for (const key of fieldsToRemove) delete cleaned[key];
      delete cleaned.httpMethodRestrict;
      onChange(cleaned, fieldPathId?.path ?? []);
    },
    [formData, onChange, fieldPathId],
  );

  return (
    <OneOfSelectField
      {...props}
      options={oneOfOptions}
      selectedIndex={selectedIndex}
      onSwitch={handleSwitch}
      transformSchema={transformHttpTriggerSchema}
    />
  );
};

export default HttpTriggerOneOfField;
