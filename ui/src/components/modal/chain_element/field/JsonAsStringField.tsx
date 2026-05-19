import React from "react";
import { FieldProps } from "@rjsf/utils";
import { Script } from "../../../Script.tsx";

export const JsonAsStringField: React.FC<FieldProps> = ({
  formData,
  onChange,
  fieldPathId,
}) => {
  return (
    <Script
      value={formData as string}
      mode={"json"}
      onChange={(value) => onChange(value, fieldPathId.path)}
    />
  );
};
