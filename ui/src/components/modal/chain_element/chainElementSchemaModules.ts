/**
 * Schema modules loaded via import.meta.glob.
 * Extracted for testability — can be mocked in tests.
 */
export function getSchemaModules(): Record<string, string> {
  return import.meta.glob(
    "/node_modules/@netcracker/qip-schemas/assets/*.schema.yaml",
    { as: "raw", eager: true },
  ) as Record<string, string>;
}
