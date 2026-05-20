import type { ErrorTransformer, RJSFValidationError } from "@rjsf/utils";
import {
  isKafkaProtocol,
  normalizeProtocol,
} from "../../../misc/protocol-utils";

export const validateKafkaGroupId = (
  properties?: Record<string, unknown>,
  elementType?: string,
): string | null => {
  if (!properties || elementType !== "async-api-trigger") return null;
  if (
    !isKafkaProtocol(properties["integrationOperationProtocolType"] as string)
  ) {
    return null;
  }
  const asyncProperties = properties["integrationOperationAsyncProperties"] as
    | Record<string, unknown>
    | undefined;
  const groupIdValue = asyncProperties?.["groupId"];
  const groupId = typeof groupIdValue === "string" ? groupIdValue.trim() : "";

  if (!groupId) {
    return "Group ID is required for Kafka operations.";
  }

  return null;
};

/**
 * Filters out validation errors that are false positives in complex schemas.
 *
 * Common issues with oneOf/anyOf/allOf:
 * - When one branch of oneOf is selected, validator complains about missing fields from other branches
 * - Conditional schemas (if/then/else) can produce misleading errors
 *
 * @param errors - Array of validation errors from RJSF
 * @param formContext - Form context with protocol type and other metadata
 * @returns Filtered array of errors
 */
export const transformValidationErrors: (
  formContext?: Record<string, unknown>,
) => ErrorTransformer = (formContext) => (errors) => {
  const proto = normalizeProtocol(
    formContext?.integrationOperationProtocolType as string,
  );

  return errors.filter((error) => {
    // Suppress oneOf errors ONLY at .properties level when protocol is selected
    // This is the main source of false positives
    if (error.name === "oneOf" && error.property === ".properties") {
      // If we have a valid protocol, suppress oneOf error at properties level
      if (
        proto &&
        ["http", "kafka", "amqp", "graphql", "grpc", "soap"].includes(proto)
      ) {
        return false;
      }
    }

    // Suppress oneOf errors for gRPC specifically (no matching branch by design)
    if (proto === "grpc" && error.name === "oneOf") {
      return false;
    }

    if (
      error.name === "oneOf" &&
      error.message?.includes("must match exactly one schema")
    ) {
      return false;
    }

    if (
      error.name === "if" &&
      (error.message?.includes('must match "then" schema') ||
        error.message?.includes('must match "else" schema'))
    ) {
      return false;
    }

    if (
      error.name === "then" &&
      error.message?.includes('must match "then" schema')
    ) {
      return false;
    }

    if (
      error.name === "const" &&
      error.message?.includes("must be equal to constant")
    ) {
      return false;
    }

    return true;
  });
};

export const hasCriticalErrors = (errors: RJSFValidationError[]): boolean => {
  if (errors.length === 0) return false;

  return errors.some((error) => {
    if (error.name === "required") {
      return true;
    }

    if (error.name === "type") {
      return true;
    }

    if (error.name === "pattern" || error.name === "format") {
      return true;
    }

    if (error.name === "enum") {
      return true;
    }

    if (error.name === "minimum" || error.name === "maximum") {
      return true;
    }

    if (error.name === "oneOf" || error.name === "anyOf") {
      return false;
    }

    return true;
  });
};

/**
 * Formats validation errors for display to user
 */
export const formatValidationError = (error: RJSFValidationError): string => {
  const field = error.property ? `Field "${error.property}"` : "Form";
  return `${field}: ${error.message}`;
};
