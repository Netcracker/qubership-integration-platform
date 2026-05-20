import React, { useEffect, useMemo } from "react";
import { FieldProps } from "@rjsf/utils";
import type { JSONSchema7 } from "json-schema";
import { Mapping } from "../../../mapper/Mapping.tsx";
import { MappingUtil } from "../../../../mapper/util/mapping.ts";
import { MappingDescription } from "../../../../mapper/model/model.ts";
import type { FormContext } from "../ChainElementModificationContext.ts";
import {
  applySchemaToMapping,
  getMappingDirectionFromPath,
  hasExistingBodyForDirection,
  pickPrimaryRequestSchema,
  pickPrimaryResponseSchema,
  tryBuildDataTypeFromSchema,
} from "../../../../mapper/util/auto-schema.ts";

type MappingFieldProps = FieldProps<unknown, JSONSchema7, FormContext>;

function isNonEmptyMappingDescription(value: unknown): value is MappingDescription {
  return (
    !!value &&
    typeof value === "object" &&
    Object.keys(value).length > 0
  );
}

/**
 * Resolves the RJSF field path to discrete segments.
 *
 * `fieldPathId.path` is typed as `FieldPathList = (string | number)[]` in RJSF
 * 5+, but the runtime value is sometimes a string (either by legacy code or
 * from test mocks). Handle both to stay robust.
 */
function toPathSegments(
  path: unknown,
  fallbackId: string | undefined,
): string[] {
  if (Array.isArray(path)) {
    return path.map(String);
  }
  if (typeof path === "string" && path.length > 0) {
    return path.split(/[._]/).filter((segment) => segment !== "root");
  }
  if (typeof fallbackId === "string" && fallbackId.length > 0) {
    return fallbackId.split(/[._]/).filter((segment) => segment !== "root");
  }
  return [];
}

const MappingField: React.FC<MappingFieldProps> = ({
  formData,
  onChange,
  fieldPathId,
  registry,
}) => {
  const pathSegments = useMemo(
    () => toPathSegments(fieldPathId?.path, fieldPathId?.$id),
    [fieldPathId?.path, fieldPathId?.$id],
  );
  const direction = useMemo(
    () => getMappingDirectionFromPath(pathSegments),
    [pathSegments],
  );

  const requestSchema = registry.formContext?.operationRequestSchema;
  const responseSchemas = registry.formContext?.operationResponseSchemas;

  const currentMapping: MappingDescription = isNonEmptyMappingDescription(
    formData,
  )
    ? formData
    : MappingUtil.emptyMapping();

  useEffect(() => {
    if (direction === "none") return;
    // Respect user's existing work; refreshing is manual via LoadSchemaDialog.
    if (hasExistingBodyForDirection(currentMapping, direction)) return;

    const schemaSource =
      direction === "request"
        ? pickPrimaryRequestSchema(requestSchema)
        : pickPrimaryResponseSchema(responseSchemas);
    if (!schemaSource) return;

    const dataType = tryBuildDataTypeFromSchema(schemaSource);
    if (!dataType) return;

    const next = applySchemaToMapping(currentMapping, direction, dataType);
    onChange(next, fieldPathId?.path);
    // currentMapping is derived from formData; re-running on every render
    // would create a loop. We rely on schema / direction changes instead.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [direction, requestSchema, responseSchemas]);

  return (
    <Mapping
      elementId={fieldPathId?.$id || "mapping"}
      mapping={currentMapping}
      onChange={(value) => onChange(value, fieldPathId?.path)}
    />
  );
};

export default MappingField;
