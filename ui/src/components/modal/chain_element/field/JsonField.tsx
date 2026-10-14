import React from "react";
import { FieldProps } from "@rjsf/utils";
import { Script } from "../../../Script.tsx";
import { DescriptionTooltipIcon } from "../DescriptionTooltipFieldTemplate.tsx";
import styles from "../ChainElementModification.module.css";

const JsonField: React.FC<FieldProps> = ({
  formData,
  onChange,
  fieldPathId,
  readonly,
  disabled,
  uiSchema,
  id,
  required,
  schema,
}) => {
  const title = uiSchema?.["ui:title"] ?? schema?.title ?? "";
  const description = schema?.description;
  const value =
    typeof formData === "string"
      ? formData
      : formData === undefined || formData === null
        ? ""
        : JSON.stringify(formData, null, 2);

  return (
    <div>
      <label htmlFor={id} className={styles["field-label"]}>
        {required ? <span className={styles["field-required"]}> *</span> : null}
        {title}
        {description && (
          <DescriptionTooltipIcon
            description={description}
            style={{ marginLeft: 6 }}
          />
        )}
      </label>
      <Script
        value={value}
        mode="json"
        readOnly={readonly || disabled}
        onChange={(value) => onChange(value, fieldPathId.path)}
      />
    </div>
  );
};

export default JsonField;
