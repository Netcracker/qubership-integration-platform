// The `migrations` value the catalog exports for each document type. A file the extension creates
// is in the current format, so it carries every migration. e2e/specs/api/extension-output.spec.ts
// fails when a list falls behind the catalog.
export const CHAIN_MIGRATIONS = "[100, 101, 102, 103, 104, 105, 106, 107, 108]";
export const SERVICE_MIGRATIONS = "[100, 101, 102]";
export const MCP_SERVICE_MIGRATIONS = "[100]";
