// Import-migration versions a file written here claims as already applied.
//
// The backend refuses a document with no `migrations` field, and an empty list
// makes it re-run every migration: V101 wraps `content` a second time and the
// import then fails on a null `integrationSystemType`. Values mirror
// runtime-catalog's `*ImportFileMigration` classes and must never run ahead of
// them — an unknown version is rejected as exported from a newer version.

/** Services and context services share one migration list. */
export const SERVICE_MIGRATIONS = "[100, 101, 102, 103, 104]";

export const MCP_SERVICE_MIGRATIONS = "[100]";

export const CHAIN_MIGRATIONS = "[100, 101, 102, 103, 104, 105, 106, 107, 108]";

/**
 * Repairs a document's migrations claim in place.
 *
 * A missing claim makes the backend's VersionsGetterService throw outright, and
 * the empty array older versions of this extension wrote makes it re-run every
 * migration. An existing claim is left alone: it may name an older set the
 * backend still has to migrate through.
 */
export function repairMigrationsClaim(
  content: unknown,
  versions: string,
): void {
  if (!content || typeof content !== "object" || Array.isArray(content)) {
    return;
  }
  const claim = (content as Record<string, unknown>).migrations;
  if (!claim || Array.isArray(claim)) {
    (content as Record<string, unknown>).migrations = versions;
  }
}
