import type { ErrorResponse } from "../api/apiTypes.ts";

export type ErrorDetailsFields = {
  service: string;
  timestamp: string;
  message: string;
  stacktrace: string;
};

/**
 * Fields for the error modal: the backend's `responseBody` when the error carries one,
 * otherwise the error's own message.
 */
export function getErrorDetails(error: unknown): ErrorDetailsFields {
  const body = (error as { responseBody?: ErrorResponse } | undefined)
    ?.responseBody;
  return {
    service: body?.serviceName ?? "",
    timestamp: body?.errorDate ?? "",
    message: body?.errorMessage || getErrorMessage(error, ""),
    stacktrace: body?.stackTrace ?? "",
  };
}

export function getErrorMessage(
  error: unknown,
  fallback = "Unknown error",
): string {
  if (
    typeof error === "object" &&
    error !== null &&
    "message" in error &&
    typeof (error as Record<string, unknown>).message === "string"
  ) {
    return error.message as string;
  }

  return fallback;
}
