/**
 * Builds `schemas/` once per run, before the `ui-server` setup bundles the UI.
 *
 * `schemas/assets`, `types`, and `dist` are gitignored and `npm install` creates none of them. The
 * UI resolves `@netcracker/qip-schemas` to `dist`.
 */
import path from "node:path";
import { newestMtimeMs, repoRoot, runToCompletion } from "../env/host.js";

/** What the schemas are built from, relative to `schemas/`: the schema sources and the build scripts. */
const SCHEMA_SOURCES = ["src/main"];

/** What `npm run build` writes, relative to `schemas/`. The build deletes all three first. */
export const SCHEMA_OUTPUTS = ["assets", "types", "dist"];

export const SCHEMAS_BUILD_ARGS = ["run", "build", "-w", "@netcracker/qip-schemas"];

/** Whether an output is missing or older than the newest source. */
export async function schemasAreStale(schemasDir = path.resolve(repoRoot(), "schemas")): Promise<boolean> {
  const sources = await newestMtimeMs(schemasDir, SCHEMA_SOURCES);
  for (const output of SCHEMA_OUTPUTS) {
    if ((await newestMtimeMs(schemasDir, [output])) < sources) return true;
  }
  return false;
}

/** Builds the schemas when they are stale, and reports whether it did. */
export async function ensureSchemas(): Promise<boolean> {
  if (!(await schemasAreStale())) return false;
  await runToCompletion("npm", SCHEMAS_BUILD_ARGS, repoRoot(), process.env);
  return true;
}
