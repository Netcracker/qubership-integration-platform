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
import path from "node:path";
import { fileURLToPath } from "node:url";
import { stateFileName } from "../env/target.js";
import { readStateFile, writeStateFile } from "./state-file.js";

const HERE = path.dirname(fileURLToPath(import.meta.url));

/** The name under which the token travels to the workers. */
export const RUN_TOKEN_ENV = "E2E_RUN";

/** First character a letter, the rest letters or digits. Anything else breaks the secret name. */
export const RUN_TOKEN_PATTERN = /^[a-z][a-z0-9]{5}$/;

/**
 * A token of that shape, fixed, for a spec that needs one without minting it.
 *
 * The `schema` project renders fixtures and reads the manifest with no stack and no `globalSetup`,
 * so it cannot ask for the run's own token. Five specs spelled the same six characters instead. A
 * token this module hands out is one that stays valid the day the alphabet changes.
 */
export const EXAMPLE_RUN_TOKEN = "ab12cd";

/** `SecretControllerV2.java:47`, copied literally so a spec can pin it without a stack. */
export const SECRET_NAME_PATTERN = /^[a-z]+[-a-z0-9]*$/;

/**
 * The one secret name the suite ever creates.
 *
 * `SecretControllerV2` exposes create and template and nothing else, and `SecretService` declares
 * no delete, so every secret this suite posts is permanent. A run-token-named secret would leak one
 * unremovable object per run, forever; a committed name leaks one over the repository's lifetime,
 * and `POST /v2/secret/{name}` on an existing secret answers 200, so reusing it is free.
 */
export const SECRET_FIXTURE_NAME = "e2e-secret-fixture";

const FIRST = "abcdefghijklmnopqrstuvwxyz";
const REST = `${FIRST}0123456789`;

function pick(alphabet: string): string {
  return alphabet[Math.floor(Math.random() * alphabet.length)];
}

/** A fresh token over the alphabet above. Six characters, first a letter. */
export function generateRunToken(length = 6): string {
  let token = pick(FIRST);
  while (token.length < length) token += pick(REST);
  return token;
}

/**
 * The run's token, minted on first call and exported to every worker.
 *
 * A token already in the environment is validated rather than trusted: `E2E_RUN=Nightly` is an
 * easy thing to type and it fails much later, on a secret name, in a spec that has nothing to do
 * with it.
 */
export function ensureRunToken(): string {
  const existing = process.env[RUN_TOKEN_ENV];
  if (existing !== undefined) {
    if (!RUN_TOKEN_PATTERN.test(existing)) {
      throw new Error(
        `${RUN_TOKEN_ENV}=${JSON.stringify(existing)} is not a run token: it must match ` +
          `${RUN_TOKEN_PATTERN}, because the catalog validates a secret name against ` +
          `${SECRET_NAME_PATTERN} and the token names one.`,
      );
    }
    return existing;
  }
  const token = generateRunToken();
  process.env[RUN_TOKEN_ENV] = token;
  return token;
}

/**
 * The token this worker runs under.
 *
 * Deliberately not a generator: a worker that mints its own token has silently opted out of the
 * teardown sweep and of the seed corpus, and nothing would say so.
 */
export function runToken(): string {
  const token = process.env[RUN_TOKEN_ENV];
  if (token === undefined) {
    throw new Error(
      `${RUN_TOKEN_ENV} is unset. It is minted in globalSetup and inherited by every worker, so ` +
        `this is either a module loaded outside the suite or a globalSetup that did not run.`,
    );
  }
  return token;
}

/** The per-worker folder name. Deleting the folder cascades to everything chain-scoped in it. */
export function workerFolderName(run: string, workerIndex: number): string {
  return `e2e-${run}-w${workerIndex}`;
}

/**
 * A name carrying the run token, for entities that have no folder to hang off.
 *
 * The sweep finds them by this token, so an entity named any other way survives the run.
 */
export function tokenized(run: string, what: string): string {
  return `e2e-${run}-${what}`;
}

/** Whether a name was minted by this run — the sweep's and the residue check's only filter. */
export function carriesRunToken(name: string | null | undefined, run: string): boolean {
  return typeof name === "string" && name.includes(`e2e-${run}`);
}

/**
 * A token for one chain call, distinct from the run token.
 *
 * `GET /v1/sessions/external-id/{id}` answers with a single session, so correlating on anything
 * two calls share is a coin flip that reads as a pass.
 */
export function callToken(prefix = "call"): string {
  return `${prefix}-${generateRunToken()}${generateRunToken()}`;
}

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

/** The manifest's records. A truncated manifest reads as empty, which costs one uncollected sweep. */
export function readRuns(file: string = RUN_MANIFEST_FILE): RunRecord[] {
  const parsed = readStateFile<unknown>(file);
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
export function recordRun(run: string, file: string = RUN_MANIFEST_FILE): RunRecord {
  const record: RunRecord = { run, pid: process.pid, startedAt: new Date().toISOString() };
  writeRuns([...readRuns(file).filter((each) => each.run !== run), record], file);
  return record;
}

/** Drops a run from the manifest, once its own teardown has swept what it created. */
export function forgetRun(run: string, file: string = RUN_MANIFEST_FILE): void {
  const left = readRuns(file).filter((each) => each.run !== run);
  writeRuns(left, file);
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
