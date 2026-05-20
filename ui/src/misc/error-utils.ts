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
