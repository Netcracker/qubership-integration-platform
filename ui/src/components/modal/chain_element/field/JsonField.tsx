import React from "react";
import { FieldProps } from "@rjsf/utils";
import { Script } from "../../../Script.tsx";
import styles from "../ChainElementModification.module.css";

const JsonField: React.FC<FieldProps> = ({
  formData,
  readonly,
  uiSchema,
  id,
  required,
  schema,
}) => {
  const title = uiSchema?.["ui:title"] ?? schema?.title ?? "";

  return (
    <div>
      <label htmlFor={id} className={styles["field-label"]}>
        {required ? <span className={styles["field-required"]}> *</span> : null}
        {title}
      </label>
      <Script
        value={JSON.stringify(formData, null, 2)}
        mode="json"
        readOnly={readonly}
      />
    </div>
  );
};

export default JsonField;
