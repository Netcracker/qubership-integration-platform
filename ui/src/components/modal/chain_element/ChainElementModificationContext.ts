import { normalizeProtocol } from "../../../misc/protocol-utils.ts";
import {
  toBodyFormData,
  BodyFormEntry,
} from "../../../misc/body-form-data-utils.ts";

/**
 * Keys that live only in memory on FormContext and must never be serialized
 * back into `element.properties`.
 *
 * Used both to keep the TypeScript union of metadata keys in sync and to
 * enforce the invariant at runtime in `enrichProperties`.
 */
export const METADATA_ONLY_CONTEXT_KEYS = [
  "elementType",
  "chainId",
  "updateContext",
  "reportMissingRequiredParams",
  "operationSpecification",
  "operationRequestSchema",
  "operationResponseSchemas",
] as const;

type MetadataOnlyKey = (typeof METADATA_ONLY_CONTEXT_KEYS)[number];

/**
 * FormContext type definition
 * Separates metadata (UI-only) from actual element properties
 */
export type FormContext = {
  // === METADATA (not saved to element properties) ===
  readonly elementType?: string;
  readonly chainId?: string;
  readonly updateContext?: (newProperties: Record<string, unknown>) => void;
  readonly reportMissingRequiredParams?: (
    key: string,
    params: string[],
  ) => void;

  // Not persisted in element.properties — re-fetched on open.
  readonly operationSpecification?: Record<string, unknown>;
  readonly operationRequestSchema?: Record<string, unknown>;
  readonly operationResponseSchemas?: Record<string, unknown>;

  // === PROPERTIES (saved to element) ===
  integrationSystemId?: string;
  integrationSpecificationId?: string;
  integrationSpecificationGroupId?: string;
  systemType?: string;
  contextServiceId?: string;
  integrationOperationId?: string;
  integrationOperationPath?: string;
  integrationOperationMethod?: string;
  integrationOperationProtocolType?: string;
  integrationOperationPathParameters?: Record<string, string>;
  integrationOperationQueryParameters?: Record<string, string>;
  integrationOperationSkipEmptyQueryParameters?: boolean;
  bodyFormData?: BodyFormEntry[];
  externalRoute?: boolean;
  synchronousGrpcCall?: boolean;
};

/**
 * Helper to extract optional string from unknown value
 */
const getOptionalString = (value: unknown): string | undefined =>
  typeof value === "string" ? value : undefined;

/**
 * Helper to check if value should be included in context
 * Filters out undefined, null, empty objects, and empty arrays
 */
const shouldInclude = (value: unknown): boolean => {
  if (value === undefined || value === null) return false;

  if (Array.isArray(value)) return value.length > 0;

  if (typeof value === "object") {
    return Object.keys(value).length > 0;
  }

  return true;
};

/**
 * Field configuration for declarative property mapping
 */
type FieldConfig<T = unknown> = {
  transform: (value: unknown) => T | undefined;
  validate?: (value: T) => boolean;
};

/**
 * Declarative mapping of FormContext fields with their transformations
 */
const FORM_CONTEXT_FIELD_CONFIG: Record<
  keyof Omit<FormContext, MetadataOnlyKey>,
  FieldConfig
> = {
  // String fields
  contextServiceId: { transform: getOptionalString },
  integrationOperationId: { transform: getOptionalString },
  integrationSystemId: { transform: getOptionalString },
  systemType: { transform: getOptionalString },
  integrationSpecificationGroupId: { transform: getOptionalString },
  integrationSpecificationId: { transform: getOptionalString },
  integrationOperationPath: { transform: getOptionalString },
  integrationOperationMethod: { transform: getOptionalString },

  // Protocol type with normalization
  integrationOperationProtocolType: {
    transform: (val) => normalizeProtocol(val as string),
  },

  // Record fields
  integrationOperationPathParameters: {
    transform: (val) => val as Record<string, string> | undefined,
  },
  integrationOperationQueryParameters: {
    transform: (val) => val as Record<string, string> | undefined,
  },

  // Boolean fields
  externalRoute: {
    transform: (val) =>
      val !== undefined && val !== null ? Boolean(val) : undefined,
  },
  synchronousGrpcCall: {
    transform: (val) =>
      val !== undefined && val !== null ? Boolean(val) : undefined,
  },
  integrationOperationSkipEmptyQueryParameters: {
    transform: (val) => val as boolean | undefined,
  },

  // Complex fields
  bodyFormData: {
    transform: (val) => toBodyFormData(val),
  },
};

/**
 * Builds FormContext from element properties using declarative field configuration.
 * Only includes fields that have defined, non-empty values.
 *
 * @param formProperties - Element properties object
 * @param elementType - Element type identifier
 * @param chainId - Current chain ID
 * @param updateContextCallback - Callback to update properties
 * @returns FormContext with only populated fields
 */
export const buildFormContextFromProperties = (
  formProperties: Record<string, unknown>,
  elementType: string,
  chainId: string,
  updateContextCallback: (updatedProperties: Record<string, unknown>) => void,
): FormContext => {
  const context: FormContext = {
    elementType,
    chainId,
    updateContext: updateContextCallback,
  };

  (
    Object.entries(FORM_CONTEXT_FIELD_CONFIG) as Array<
      [keyof typeof FORM_CONTEXT_FIELD_CONFIG, FieldConfig]
    >
  ).forEach(([fieldName, config]) => {
    const rawValue = formProperties[fieldName];
    const transformedValue = config.transform(rawValue);

    if (shouldInclude(transformedValue)) {
      // @ts-expect-error - TypeScript doesn't narrow the union type properly here
      context[fieldName] = transformedValue;
    }
  });

  return context;
};

const METADATA_ONLY_KEY_SET: ReadonlySet<string> = new Set(
  METADATA_ONLY_CONTEXT_KEYS,
);

/**
 * Enriches target properties with source properties, removing undefined/null values.
 * Used to update properties without polluting them with empty fields.
 *
 * Metadata-only keys (see METADATA_ONLY_CONTEXT_KEYS) are always skipped — they
 * live only in FormContext and must never leak into `element.properties` (large
 * schemas would bloat the save payload and are refetched on open anyway).
 *
 * @param targetProperties - Properties to enrich
 * @param sourceProperties - New properties to merge
 * @returns Enriched properties object
 */
export const enrichProperties = (
  targetProperties: Record<string, unknown>,
  sourceProperties: Record<string, unknown>,
): Record<string, unknown> => {
  const result = { ...targetProperties };

  Object.entries(sourceProperties).forEach(([key, value]) => {
    if (METADATA_ONLY_KEY_SET.has(key)) {
      return;
    }

    // Special handling for protocol normalization
    let normalizedValue = value;
    if (key === "integrationOperationProtocolType") {
      normalizedValue = normalizeProtocol(value as string);
    }

    if (normalizedValue === undefined || normalizedValue === null) {
      delete result[key];
    } else {
      result[key] = normalizedValue;
    }
  });

  return result;
};
