import { normalizeProtocol } from "../../../misc/protocol-utils.ts";
import {
  toBodyFormData,
  BodyFormEntry,
} from "../../../misc/body-form-data-utils.ts";

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
  keyof Omit<FormContext, "elementType" | "chainId" | "updateContext">,
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
  // Start with metadata
  const context: FormContext = {
    elementType,
    chainId,
    updateContext: updateContextCallback,
  };

  // Process all fields declaratively
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

/**
 * Enriches target properties with source properties, removing undefined/null values.
 * Used to update properties without polluting them with empty fields.
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
    // Special handling for protocol normalization
    let normalizedValue = value;
    if (key === "integrationOperationProtocolType") {
      normalizedValue = normalizeProtocol(value as string);
    }

    // Remove undefined/null values from result
    if (normalizedValue === undefined || normalizedValue === null) {
      delete result[key];
    } else {
      result[key] = normalizedValue;
    }
  });

  return result;
};
