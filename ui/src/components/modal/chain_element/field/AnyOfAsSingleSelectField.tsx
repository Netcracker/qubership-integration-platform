import React from "react";
import { Select } from "antd";
import { FieldProps } from "@rjsf/utils";
import { JSONSchema7 } from "json-schema";

const AnyOfAsSingleSelectField: React.FC<FieldProps<string>> = ({
  id,
  formData,
  onChange,
  schema,
  uiSchema,
  required,
}) => {
  const title = uiSchema?.["ui:title"] ?? schema?.title ?? "";

  const labelStyle: React.CSSProperties = {
    display: "block",
    marginBottom: 6,
    fontWeight: 500,
  };
  const requiredStyle: React.CSSProperties = {
    color: "#ff4d4f",
    marginRight: 4,
  };

  function collectOptions(schema: JSONSchema7): { value: string }[] {
    const values: string[] = [];

    if (Array.isArray(schema.anyOf)) {
      for (const element of schema.anyOf) {
        if (
          typeof element === "object" &&
          element?.enum &&
          Array.isArray(element.enum)
        ) {
          values.push(...(element.enum as string[]));
        }
      }
    }

    return [...new Set(values)].map((val) => ({ value: val }));
  }

  const handleChange = (selected: string[]) => {
    onChange(selected[0] ?? "");
  };

  return (
    <div>
      {title ? (
        <label htmlFor={id} style={labelStyle}>
          {required ? <span style={requiredStyle}> *</span> : null}
          {title}
        </label>
      ) : null}
      <Select
        mode="tags"
        allowClear={false}
        maxCount={1}
        style={{ width: "100%" }}
        placeholder={schema.default as string | undefined}
        value={formData ? [String(formData)] : []}
        onChange={handleChange}
        options={collectOptions(schema)}
      />
    </div>
  );
};
export default AnyOfAsSingleSelectField;
