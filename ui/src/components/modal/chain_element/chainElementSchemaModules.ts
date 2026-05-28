/**
 * Schema modules loaded via import.meta.glob.
 */

let cachedModules: Record<string, string> | null = null;

function loadSchemaModules(): Record<string, string> {
  if (!cachedModules) {
    cachedModules = import.meta.glob(
      "./../../../../../schemas/assets/*.schema.yaml",
      {
        query: "?raw",
        import: "default",
        eager: true,
      },
    ) as Record<string, string>;
  }
  return cachedModules;
}

export function getSchemaModules(): Record<string, string> {
  return loadSchemaModules();
}

/** Raw YAML for an element type (e.g. "http-trigger" --- http-trigger.schema.yaml). */
export function getSchemaRawByElementType(
  elementType: string,
): string | undefined {
  const suffix = `/${elementType}.schema.yaml`;
  const modules = loadSchemaModules();
  const entry = Object.entries(modules).find(([path]) => path.endsWith(suffix));
  return entry?.[1];
}
