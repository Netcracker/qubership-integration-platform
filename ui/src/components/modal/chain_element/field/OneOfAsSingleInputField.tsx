import React from "react";
import { FieldProps } from "@rjsf/utils";
import { Input } from "antd";
import styles from "../ChainElementModification.module.css";

const OneOfAsSingleInputField: React.FC<FieldProps<string>> = ({
  id,
  formData,
  onChange,
  schema,
  uiSchema,
  required,
  fieldPathId,
}) => {
  const title = uiSchema?.["ui:title"] ?? schema?.title ?? "";

  return (
    <div>
      {title ? (
        <label htmlFor={id} className={styles["field-label"]}>
          {required ? <span className={styles["field-required"]}> *</span> : null}
          {title}
        </label>
      ) : null}

      <Input
        id={id}
        value={formData ?? ""}
        onChange={(e) => {
          const val = e.target.value;
          onChange(val, fieldPathId.path);
        }}
        placeholder={schema.default as string | undefined}
      />
    </div>
  );
};

export default OneOfAsSingleInputField;
