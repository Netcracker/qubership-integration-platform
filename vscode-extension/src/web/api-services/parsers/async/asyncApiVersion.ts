export type AsyncApiVersion = "V2" | "V3";

/**
 * Detects the AsyncAPI major version from the document's `asyncapi` field.
 * Mirrors the backend `AsyncApiVersion.detect`: a value starting with "3."
 * is treated as 3.x; everything else (2.x, unknown, missing) is treated as 2.x.
 */
export function detectAsyncApiVersion(asyncapiField: unknown): AsyncApiVersion {
  return typeof asyncapiField === "string" && asyncapiField.startsWith("3.")
    ? "V3"
    : "V2";
}
