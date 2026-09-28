/**
 * The record of `restartWith` overrides still in force, which each target's provisioner clears.
 * Compose and Kubernetes keep one file each; the format and the notices are the same.
 */
import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { ROLES } from "./containers.js";
import { writeStateFile } from "../support/state-file.js";
import type { ServiceRole } from "./index.js";

const HERE = path.dirname(fileURLToPath(import.meta.url));

/**
 * Where a `restartWith` override is recorded until the restart that undoes it.
 *
 * A container recreated with extra settings differs from the committed configuration in a way
 * nothing on disk shows: the override file `restartWith` writes lives in a temporary directory and
 * is deleted as soon as Compose has read it. So a run killed between `restartWith` and its
 * `afterAll` leaves the catalog running with `QIP_EXPORT_LEGACY_FORMAT=true`, provisioning finds
 * every configuration file older than the container and touches nothing, and the next run's
 * `service-type-roundtrip` refuses to start on a flag it did not set. This file is the handle
 * `provisionCompose()` clears that by, and it is outside `test-results/`, which Playwright clears.
 */
export const OVERRIDES_FILE =
  process.env.E2E_OVERRIDES_FILE ?? path.resolve(HERE, "..", ".e2e-overrides.json");

/** One service running settings the committed configuration does not carry. */
export interface ServiceOverride {
  settings: Record<string, string>;
  /** The run that applied it, so a report can name who left it behind. */
  run?: string;
  at: string;
}

/**
 * The services a file that will not parse still names, as overrides to put back.
 *
 * The two ways of being wrong here do not cost the same. A service named in error is recreated,
 * which is seconds of provisioning the next run may well have spent anyway; a service missed is one
 * left running `QIP_EXPORT_LEGACY_FORMAT=true` with nothing on disk saying so, and the next run's
 * `service-type-roundtrip` refuses to start on a flag it did not set. So any role the text still
 * mentions is reported, and a text mentioning none says so rather than passing for a clean stack.
 *
 * The settings are not salvaged, because nothing acts on them: the restore is a recreate from the
 * committed configuration, which discards whatever the container was started with either way.
 */
function salvageOverrides(
  text: string,
  file: string,
  cause: unknown,
): Record<string, ServiceOverride> {
  const salvaged: Record<string, ServiceOverride> = {};
  for (const role of ROLES) {
    if (text.includes(`"${role}"`)) salvaged[role] = { settings: {}, at: "an unrecorded time" };
  }
  const named = Object.keys(salvaged);
  const restoring = named.length
    ? `Restoring ${named.join(", ")}.`
    : "It names no service, so a service may be running settings nothing can now name.";
  console.error(
    `[overrides] ${file} does not parse as a record of overrides (${String(cause)}), so it is a ` +
      `record something left half-written. ${restoring}`,
  );
  return salvaged;
}

/**
 * Why `parsed` is not a record of overrides, or `null` when it is one.
 *
 * A file can parse and still be nonsense, and each shape is wrong in its own way: `[]` reads as a
 * clean stack, `null` and an entry without `settings` throw inside provisioning — over a container
 * that is still running what a killed run set. All of them mean the same thing, that the record
 * cannot be trusted, so all of them take the salvage path a file that will not parse takes.
 */
function shapeFault(parsed: unknown): string | null {
  if (parsed === null || typeof parsed !== "object") {
    return `it is ${parsed === null ? "null" : typeof parsed} rather than an object`;
  }
  if (Array.isArray(parsed)) return "it is a list rather than an object";
  for (const [role, entry] of Object.entries(parsed as Record<string, unknown>)) {
    if (entry === null || typeof entry !== "object" || Array.isArray(entry)) {
      return `the entry for ${role} is not an object`;
    }
    const { settings, at } = entry as { settings?: unknown; at?: unknown };
    if (settings === null || typeof settings !== "object" || Array.isArray(settings)) {
      return `the entry for ${role} names no settings`;
    }
    // `at` is what both the provisioning log and the `never`-mode notice print, so a record without
    // one is read for the role it still names rather than reported as `left at undefined`.
    if (typeof at !== "string") return `the entry for ${role} names no time`;
  }
  return null;
}

/**
 * The overrides in force, by service role. Empty for a stack nobody has restarted.
 *
 * A file that does not parse is **not** an empty one, and that difference is the whole point of the
 * file: read as empty, it hands provisioning a clean stack over a container still running what a
 * killed run set. So only a missing file answers `{}`, and anything else is read for what it still
 * names — including a file that parses into something that is not a record of overrides, which is
 * the same fact arriving through a different door.
 */
export async function readOverrides(
  file: string = OVERRIDES_FILE,
): Promise<Record<string, ServiceOverride>> {
  // No file is the normal state. Any other read failure is a fact about a file that exists, and
  // swallowing it reports a stack nobody has restarted.
  const text = await fs.readFile(file, "utf-8").catch((cause: NodeJS.ErrnoException) => {
    if (cause.code === "ENOENT") return null;
    throw cause;
  });
  if (text === null) return {};
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch (cause) {
    return salvageOverrides(text, file, cause);
  }
  const fault = shapeFault(parsed);
  if (fault !== null) return salvageOverrides(text, file, fault);
  return parsed as Record<string, ServiceOverride>;
}


/** Records an override, before the recreate that applies it. */
export async function recordOverride(
  service: ServiceRole,
  settings: Record<string, string>,
  file: string = OVERRIDES_FILE,
): Promise<void> {
  const overrides = await readOverrides(file);
  overrides[service] = { settings, run: process.env.E2E_RUN, at: new Date().toISOString() };
  writeStateFile(file, overrides);
}

/** Drops the record, after the restart that has put the committed settings back. */
export async function forgetOverride(
  service: ServiceRole,
  file: string = OVERRIDES_FILE,
): Promise<void> {
  const overrides = await readOverrides(file);
  if (overrides[service] === undefined) return;
  delete overrides[service];
  writeStateFile(file, overrides);
}

/**
 * One recorded override in the words both modes print.
 *
 * The settings are what a reader acts on, and a record salvaged from a file that would not parse
 * carries none: `salvageOverrides` keeps the role the text still names and drops everything else. So
 * an empty list is rendered as the fact that the record no longer names the settings, rather than as
 * `clearing , left on qip-runtime-catalog`, which reads as a bug in the provisioner.
 */
export function describeOverride(service: string, override: ServiceOverride): string {
  const named = Object.keys(override.settings);
  const settings = named.length ? named.join(", ") : "settings the record no longer names";
  return `${settings} on ${service}, left by run ${override.run ?? "unknown"} at ${override.at}`;
}

/**
 * What `E2E_PROVISION=never` says about a record it will not act on.
 *
 * The mode recreates nothing by definition, so reporting is all it owes the reader — and it owes it,
 * because this is the mode a hand-provisioned stack runs under and a `restartWith` left by a killed
 * run is the one kind of staleness no file on disk shows. Without the line the only symptom is
 * `service-type-roundtrip` aborting on a flag the developer never set, which names neither the
 * record nor the run that wrote it. So the notice carries both, plus the two ways out.
 */
export function overrideNotice(
  service: string,
  override: ServiceOverride,
  file: string = OVERRIDES_FILE,
): string {
  return (
    `[provision] E2E_PROVISION=never leaves ${describeOverride(service, override)} in force. ` +
    `${file} is the record; recreate the service from the committed configuration, or run with ` +
    `E2E_PROVISION unset to have provisioning clear it.`
  );
}

