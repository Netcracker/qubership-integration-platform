import React from "react";
import { FieldProps } from "@rjsf/utils";
import { Script } from "../../../Script.tsx";

const JsonField: React.FC<FieldProps> = ({
  formData,
  readonly,
  uiSchema,
  id,
  required,
  schema,
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

  return (
    <div>
      <label htmlFor={id} style={labelStyle}>
        {required ? <span style={requiredStyle}> *</span> : null}
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
