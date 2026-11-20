/** The name under which the token travels to the workers. */
export const RUN_TOKEN_ENV = "E2E_RUN";

/** First character a letter, the rest letters or digits. Anything else breaks the secret name. */
export const RUN_TOKEN_PATTERN = /^[a-z][a-z0-9]{5}$/;

/**
 * A token of that shape, fixed, for a spec that needs one without minting it.
 *
 * The `schema` and `harness` projects render fixtures and read the manifest with no stack and no
 * `globalSetup`, so they cannot ask for the run's own token. Five specs spelled the same six
 * characters instead. A token this module hands out is one that stays valid the day the alphabet
 * changes.
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
