import React, { useMemo } from "react";
import { FieldProps, RJSFSchema } from "@rjsf/utils";
import { Select } from "antd";
import { FormContext } from "../../ChainElementModificationContext";
import { EMPTY_ONEOF_SCHEMA, OneOfOption } from "./oneof-utils";

export interface OneOfSelectFieldProps
  extends FieldProps<Record<string, unknown>, RJSFSchema, FormContext> {
  options: OneOfOption[];
  selectedIndex: number;
  onSwitch: (newIndex: number) => void;
  /** Optional schema transform applied to the option before rendering. */
  transformSchema?: (option: OneOfOption, index: number) => RJSFSchema;
}

/**
 * Generic oneOf field that renders a Select + SchemaField for the chosen
 * option. Selection is controlled; the owner is responsible for cleaning
 * option-specific fields in formData before calling `onChange`.
 */
const OneOfSelectField: React.FC<OneOfSelectFieldProps> = (props) => {
  const {
    options,
    selectedIndex,
    onSwitch,
    transformSchema,
    registry,
    uiSchema,
  } = props;
  const SchemaField = registry.fields.SchemaField;

  const dropdownOptions = useMemo(
    () =>
      options.map((opt, i) => ({
        label: opt.title ?? `Option ${i + 1}`,
        value: i,
      })),
    [options],
  );

  const matchedSchema = useMemo<RJSFSchema>(() => {
    const option = options[selectedIndex];
    if (!option) return EMPTY_ONEOF_SCHEMA;
    return transformSchema
      ? transformSchema(option, selectedIndex)
      : (option as RJSFSchema);
  }, [options, selectedIndex, transformSchema]);

  return (
    <>
      <Select
        value={selectedIndex}
        onChange={onSwitch}
        options={dropdownOptions}
        style={{ marginBottom: 16, width: "100%" }}
      />
      <SchemaField {...props} schema={matchedSchema} uiSchema={uiSchema} />
    </>
  );
};

export default OneOfSelectField;
