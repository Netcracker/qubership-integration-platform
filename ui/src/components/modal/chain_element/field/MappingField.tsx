import React from "react";
import { FieldProps } from "@rjsf/utils";
import { Mapping } from "../../../mapper/Mapping.tsx";
import { MappingUtil } from "../../../../mapper/util/mapping.ts";
import { MappingDescription } from "../../../../mapper/model/model.ts";

const MappingField: React.FC<FieldProps> = ({
  formData,
  onChange,
  fieldPathId,
}) => {
  return (
    <Mapping
      elementId={fieldPathId?.$id || "mapping"}
      mapping={
        formData &&
        typeof formData === "object" &&
        Object.keys(formData as object).length > 0
          ? (formData as MappingDescription)
          : MappingUtil.emptyMapping()
      }
      onChange={(value) => onChange(value, fieldPathId.path)}
    />
  );
};

export default MappingField;
