// Path-traversal guard for source paths joined onto a service's `resources/`
// folder. Read and delete paths share this single check.

/**
 * Returns true only for a non-empty string with no `..` segment, so a crafted
 * source path cannot escape the service's `resources/` folder.
 */
export function isSafeResourcePath(value: unknown): value is string {
  return (
    typeof value === "string" &&
    value.length > 0 &&
    !value.split(/[\\/]/).includes("..")
  );
}
