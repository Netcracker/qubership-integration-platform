import React from "react";
import { Select } from "antd";
import { FieldProps } from "@rjsf/utils";
import { JSONSchema7 } from "json-schema";
import styles from "../../ChainElementModification.module.css";

const AnyOfAsSingleSelectField: React.FC<FieldProps<string>> = ({
  id,
  formData,
  onChange,
  schema,
  uiSchema,
  required,
  fieldPathId,
}) => {
  const title = uiSchema?.["ui:title"] ?? schema?.title ?? "";

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
    onChange(selected[0] ?? "", fieldPathId.path);
  };

  return (
    <div>
      {title ? (
        <label htmlFor={id} className={styles["field-label"]}>
          {required ? <span className={styles["field-required"]}> *</span> : null}
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
