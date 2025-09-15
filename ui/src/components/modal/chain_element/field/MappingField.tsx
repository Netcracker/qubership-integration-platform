import React from "react";
import { FieldProps } from "@rjsf/utils";
import { Mapping } from "../../../mapper/Mapping.tsx";
import { MappingUtil } from "../../../../mapper/util/mapping.ts";
import { MappingDescription } from "../../../../mapper/model/model.ts";

const MappingField: React.FC<FieldProps> = ({
  formData,
  onChange,
  idSchema,
}) => {
  return (
    <Mapping
      elementId={idSchema?.$id || "mapping"}
      mapping={
        formData &&
        typeof formData === "object" &&
        Object.keys(formData as object).length > 0
          ? (formData as MappingDescription)
          : MappingUtil.emptyMapping()
      }
      onChange={onChange}
    />
  );
};

export default MappingField;
