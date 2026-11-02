/**
 * Builds `schemas/` once per run, before the `ui-server` setup bundles the UI.
 *
 * `schemas/assets`, `types`, and `dist` are gitignored and `npm install` creates none of them. The
 * UI resolves `@netcracker/qip-schemas` to `dist`.
 */
import path from "node:path";
import { repoRoot, runToCompletion } from "../env/host.js";
import { BuildState } from "./build-state.js";

/** Schema sources, build scripts, compiler configuration, and dependencies, relative to `schemas/`. */
export const SCHEMA_INPUTS = ["src/main", "package.json", "tsconfig.json", "../package.json", "../package-lock.json"];

/** What `npm run build` writes, relative to `schemas/`. The build deletes all three first. */
export const SCHEMA_OUTPUTS = ["assets", "types", "dist"];
export const SCHEMA_MARKER = "dist/.e2e-build";

const SCHEMAS_BUILD_ARGS = ["run", "build", "-w", "@netcracker/qip-schemas"];

export function schemasBuildState(schemasDir = path.resolve(repoRoot(), "schemas")): BuildState {
  return new BuildState(schemasDir, SCHEMA_INPUTS, SCHEMA_MARKER, SCHEMA_OUTPUTS);
}

/** Whether an output is missing or the inputs differ from the last build. */
export async function schemasAreStale(schemasDir = path.resolve(repoRoot(), "schemas")): Promise<boolean> {
  return schemasBuildState(schemasDir).isStale();
}

/** Builds the schemas when they are stale, and reports whether it did. */
export async function ensureSchemas(): Promise<boolean> {
  const state = schemasBuildState();
  const fingerprint = state.fingerprint();
  if (!(await state.isStale(fingerprint))) return false;
  await runToCompletion("npm", SCHEMAS_BUILD_ARGS, repoRoot(), process.env);
  state.record(fingerprint);
  return true;
}
