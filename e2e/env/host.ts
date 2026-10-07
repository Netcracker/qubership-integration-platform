/**
 * Helpers both targets and their provisioners share: the checkout root, polling, long commands, and
 * file and HTTP probes. It imports no adapter, so either target can load it without the other.
 */
import { execFile, spawn } from "node:child_process";
import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);
const HERE = path.dirname(fileURLToPath(import.meta.url));

/**
 * The checkout this file sits in, which is what every provisioned source path is resolved against.
 *
 * Anchored to this module and never to the working directory. `npx playwright test --config
 * e2e/playwright.config.ts` from the repository root is a supported way to start the suite, and
 * under a `cwd`-derived root every path in `PROVISIONED[].sources` resolves to nothing: staleness
 * then reads as "no source is newer than the image", no service is rebuilt, and the run silently
 * tests whatever containers happen to be up. That is the stale-jar failure provisioning exists to
 * prevent.
 */
export function repoRoot(): string {
  return process.env.CIP_REPO_ROOT ?? path.resolve(HERE, "..", "..");
}

/**
 * Polls until `ready` reports nothing left to wait for, or fails with what the last attempt saw.
 *
 * `ready` answers `null` when the wait is over and a short description of what it saw otherwise, so
 * the timeout message carries the last reading rather than only the budget that ran out.
 */
export async function pollUntil(
  timeoutMs: number,
  intervalMs: number,
  ready: () => Promise<string | null>,
  onTimeout: (last: string) => string,
): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  let last = "no response";
  while (Date.now() < deadline) {
    const notYet = await ready();
    if (notYet === null) return;
    last = notYet;
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
  }
  throw new Error(onTimeout(last));
}

/** Runs `command` and returns its standard output; a non-zero exit throws with its stderr. */
export async function capture(command: string, args: string[]): Promise<string> {
  const { stdout } = await execFileAsync(command, args, { maxBuffer: 64 * 1024 * 1024 });
  return stdout;
}

/**
 * Run a long command with its output on the terminal.
 *
 * A run that silently spends four minutes in Maven is indistinguishable from a hung suite, and the
 * report at the end of provisioning arrives too late to say so.
 */
export function stream(command: string, args: string[], cwd: string): Promise<void> {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { cwd, stdio: "inherit" });
    child.on("error", reject);
    child.on("close", (code) =>
      code === 0
        ? resolve()
        : reject(new Error(`${command} ${args.join(" ")} exited ${code}`)),
    );
  });
}

/** Newest file mtime under the given paths, 0 when none of them exists. */
export async function newestMtimeMs(root: string, relative: string[]): Promise<number> {
  let newest = 0;
  for (const rel of relative) {
    const target = path.resolve(root, rel);
    const stat = await fs.stat(target).catch(() => null);
    if (!stat) continue;
    if (stat.isFile()) {
      newest = Math.max(newest, stat.mtimeMs);
      continue;
    }
    const entries = await fs.readdir(target, { recursive: true, withFileTypes: true });
    for (const entry of entries) {
      if (!entry.isFile()) continue;
      const each = await fs
        .stat(path.join(entry.parentPath ?? target, entry.name))
        .catch(() => null);
      if (each) newest = Math.max(newest, each.mtimeMs);
    }
  }
  return newest;
}

/** The status `url` answers within 5 s, or 0 when it answers nothing. */
export async function httpStatus(url: string): Promise<number> {
  return fetch(url, { signal: AbortSignal.timeout(5_000) })
    .then((response) => response.status)
    .catch(() => 0);
}
