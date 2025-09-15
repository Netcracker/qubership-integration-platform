import React from "react";
import { FieldProps } from "@rjsf/utils";
import { Script } from "../../../Script.tsx";

const ScriptField: React.FC<FieldProps> = ({ formData, onChange }) => {
  return <Script value={formData as string} onChange={onChange} />;
};

export default ScriptField;
