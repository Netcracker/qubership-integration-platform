import React from "react";
import { Input } from "antd";
import styles from "../ChainElementModification.module.css";
import { FormContext } from "../ChainElementModification";
import { FieldProps } from "@rjsf/utils";
import { JSONSchema7 } from "json-schema";
import { buildPrefix } from "./BasePathField";

const ContextPathWithPrefixField: React.FC<
  FieldProps<string, JSONSchema7, FormContext>
> = ({
  id,
  formData,
  schema,
  required,
  uiSchema,
  registry,
  onChange,
  fieldPathId,
}) => {
  const title = uiSchema?.["ui:title"] ?? schema?.title ?? "";

  return (
    <div>
      <label htmlFor={id} className={styles["field-label"]}>
        {required ? <span className={styles["field-required"]}> *</span> : null}
        {title}
      </label>
      <Input
        value={formData}
        prefix={buildPrefix(registry.formContext.externalRoute)}
        onChange={(e) => onChange(e.target.value, fieldPathId.path)}
      />
    </div>
  );
};

export default ContextPathWithPrefixField;
