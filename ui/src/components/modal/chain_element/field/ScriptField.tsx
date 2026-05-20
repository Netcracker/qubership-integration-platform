import React from "react";
import { FieldProps } from "@rjsf/utils";
import { Script } from "../../../Script.tsx";

const ScriptField: React.FC<FieldProps> = ({
  formData,
  onChange,
  fieldPathId,
}) => {
  return (
    <Script
      value={formData as string}
      onChange={(value) => onChange(value, fieldPathId.path)}
    />
  );
};

export default ScriptField;
