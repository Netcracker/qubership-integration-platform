/**
 * The run token: six characters that separate this run's entities from every other run's.
 *
 * One token per run, minted in `globalSetup` and exported through the environment so every worker
 * shares it. Workers are child processes, so an assignment to `process.env` before they start is
 * how they see it; a token minted per worker would defeat the point, because the teardown sweep and
 * the seed corpus have to agree on one name.
 *
 * The alphabet is lowercase letters and digits, first character a letter. That is not arbitrary:
 * `SecretControllerV2.createSecret` validates the path variable against `^[a-z]+[-a-z0-9]*$`
 * (`SecretControllerV2.java:47`), so a token that starts with a digit or carries an uppercase
 * letter makes `POST /v2/secret/{name}` answer 400. A generator emitting `[A-Za-z0-9]{6}` fails on
 * roughly a third of its runs and passes on the rest, which is worse than one that fails always.
 *
 * The run token is not a session correlation key. `GET /v1/sessions/external-id/{id}` returns a
 * single session, so a lookup keyed on a token every spec shares returns some other spec's session
 * and the assertion passes for the wrong reason. Every chain call mints its own token with
 * `callToken()`.
 *
 * The module also keeps the manifest of runs, because the token is what a sweep of a *previous*
 * run has to match on and nothing else records one.
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { externalRunStateDir, externalSettings } from "../env/external-settings.js";
import { RUN_TOKEN_PATTERN } from "./run-token.js";
import { stateFileName } from "../env/target.js";
import { readStateFile, writeStateFile } from "./state-file.js";

const HERE = path.dirname(fileURLToPath(import.meta.url));

export * from "./run-token.js";

// ---------------------------------------------------------------------------
// The manifest of runs, and what a later run may sweep
// ---------------------------------------------------------------------------

/**
 * Where the runs are recorded.
 *
 * Outside `test-results/`, which Playwright owns and clears: an entry has to survive into the
 * *next* run, and that is the only reason it exists. Each target has a manifest of its own, so a
 * run never sweeps another target's residue against the stack in front of it.
 */
export const RUN_MANIFEST_FILE =
  process.env.E2E_RUN_MANIFEST ?? path.join(HERE, "..", stateFileName(".e2e-runs.json"));

/** One run, and enough about it to tell a finished run from one still going. */
export interface RunRecord {
  run: string;
  /** The process that minted the token. Playwright's own, so it dies with the run. */
  pid: number;
  /** When the run started. The sweep header prints it: how old residue is decides whether to care. */
  startedAt: string;
}

function externalStandDir(file: string | undefined): string | undefined {
  return file === undefined && process.env.E2E_RUN_MANIFEST === undefined
    ? externalSettings()?.full?.standDir : undefined;
}

function externalManifest(standDir: string, run: string): string {
  return path.join(externalRunStateDir(standDir, run), "manifest.json");
}

/** The manifest's records. A truncated manifest reads as empty, which costs one uncollected sweep. */
export function readRuns(file?: string): RunRecord[] {
  const standDir = externalStandDir(file);
  if (standDir) {
    const runs = path.join(standDir, "runs");
    if (!fs.existsSync(runs)) return [];
    return fs.readdirSync(runs, { withFileTypes: true }).flatMap((entry) => {
      if (!entry.isDirectory() || !RUN_TOKEN_PATTERN.test(entry.name)) return [];
      const record = readStateFile<RunRecord>(externalManifest(standDir, entry.name));
      return record?.run === entry.name ? [record] : [];
    });
  }
  const parsed = readStateFile<unknown>(file ?? RUN_MANIFEST_FILE);
  return Array.isArray(parsed) ? (parsed as RunRecord[]) : [];
}

/**
 * Replaces the manifest through `writeStateFile`'s staging file and rename.
 *
 * The manifest holds every cleanup handle this machine has. Truncated in place, a kill during
 * `recordRun` or `forgetRun` would leave `readRuns` answering `[]`, and residue that no sweep can
 * then collect.
 */
export function writeRuns(records: readonly RunRecord[], file: string = RUN_MANIFEST_FILE): void {
  writeStateFile(file, records);
}

/** Records this run, so a later one can clean up after it if this one never gets the chance. */
export function recordRun(run: string, file?: string): RunRecord {
  const record: RunRecord = { run, pid: process.pid, startedAt: new Date().toISOString() };
  const standDir = externalStandDir(file);
  if (standDir) writeStateFile(externalManifest(standDir, run), record);
  else writeRuns([...readRuns(file).filter((each) => each.run !== run), record], file);
  return record;
}

/** Drops a run from the manifest, once its own teardown has swept what it created. */
export function forgetRun(run: string, file?: string): void {
  const standDir = externalStandDir(file);
  if (standDir) fs.rmSync(externalManifest(standDir, run), { force: true });
  else writeRuns(readRuns(file).filter((each) => each.run !== run), file);
}

/** Whether a process is still around: a recorded run, or the runner that started a UI preview. */
export function processAlive(pid: number): boolean {
  try {
    process.kill(pid, 0);
    return true;
  } catch (cause) {
    // EPERM means the process exists and belongs to somebody else, which still means "alive".
    return (cause as NodeJS.ErrnoException).code === "EPERM";
  }
}

/**
 * The runs a sweep may collect, which is deliberately not "every token that looks like one".
 *
 * The suite's contract is **one run per stack at a time**. The `env` project restarts shared
 * containers and flips catalog environment flags, so two runs against one stack cannot both be
 * correct however carefully everything else is templated. What the manifest is for is the previous
 * run rather than a concurrent one: a `kill -9`, a closed terminal or a second Ctrl-C leaves a
 * deployed corpus behind, and this is what makes it collectable.
 *
 * Ownership is therefore taken from the manifest and from one fact about each entry: whether the
 * process that wrote it is still running. An interrupted run leaves a dead pid and its residue is
 * collected; a run still in flight does not, so a second run started by mistake takes nothing out
 * from under the first.
 *
 * A live pid is trusted as it stands. A recycled one would make an entry uncollectable, and the
 * suite has never met one; guarding against it costs either a second recorded identity per run or a
 * rule keyed on the host's uptime, and neither is worth carrying for a hazard nobody has hit. The
 * residue stays named in the manifest either way, so the worst case is a sweep that skips it.
 */
export function sweepableRuns(
  current: string,
  records: readonly RunRecord[] = readRuns(),
  alive: (pid: number) => boolean = processAlive,
): RunRecord[] {
  return records.filter((each) => each.run !== current && !alive(each.pid));
}
