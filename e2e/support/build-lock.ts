/**
 * A lock file that keeps the UI bundle build of `ui-server` and the UI library and extension builds
 * of `vscode-server` from running at the same time. On a 4-core CI runner, beside the seed and the
 * cluster, the three builds at once pushed the `vscode-server` setup past its 10-minute timeout.
 *
 * The file records the holder's pid, and a waiter removes it once that pid has exited. The bundle
 * goes first, because the `ui` project waits on it: when `ui-server` will build it, `globalSetup`
 * reserves the lock for `ui-server` in the runner's name before any worker starts, and the
 * `ui-server` setup takes the reservation over.
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { processAlive } from "./run.js";
import { readStateFile } from "./state-file.js";

/** Outside `test-results/`, which Playwright clears at the start of a run. */
export const BUILD_LOCK_FILE = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", ".e2e-build.lock");

export const BUNDLE_HOLDER = "ui-server";

export interface BuildLockRecord {
  holder: string;
  pid: number;
}

export class BuildLock {
  constructor(
    readonly file = BUILD_LOCK_FILE,
    /** The process that holds the lock while its build runs. */
    readonly pid = process.pid,
    /** The Playwright runner of this run, which reserves the lock; the setups run in its workers. */
    readonly runner = process.ppid,
    readonly pollMs = 500,
  ) {}

  /**
   * Creates the lock file holding `record`, or returns `false` when it exists. A complete staging
   * file is linked to the lock path, so a reader never sees it half written.
   */
  private create(record: BuildLockRecord): boolean {
    const staging = `${this.file}.${this.pid}.tmp`;
    fs.writeFileSync(staging, JSON.stringify(record));
    try {
      fs.linkSync(staging, this.file);
      return true;
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code === "EEXIST") return false;
      throw error;
    }
  }

  private read(): BuildLockRecord | null {
    return readStateFile<BuildLockRecord>(this.file);
  }

  /**
   * Holds the lock for `holder` until that setup takes it over or drops it. Called in the runner,
   * whose pid is then `pid`.
   */
  reserve(holder: string): boolean {
    try {
      return this.create({ holder, pid: this.pid });
    } finally {
      fs.rmSync(`${this.file}.${this.pid}.tmp`, { force: true });
    }
  }

  /** Removes the reservation for `holder`, which then builds nothing. */
  dropReservation(holder: string): void {
    const record = this.read();
    if (record?.holder === holder && record.pid === this.runner) fs.rmSync(this.file, { force: true });
  }

  /** Runs `body` while holding the lock, after any other holder has released it or exited. */
  async run<T>(holder: string, body: () => Promise<T>): Promise<T> {
    await this.acquire(holder);
    try {
      return await body();
    } finally {
      if (this.read()?.pid === this.pid) fs.rmSync(this.file, { force: true });
    }
  }

  private async acquire(holder: string): Promise<void> {
    const mine = { holder, pid: this.pid };
    const staging = `${this.file}.${this.pid}.tmp`;
    let waitingFor = "";
    try {
      for (;;) {
        if (this.create(mine)) return;
        const record = this.read();
        // Released between the two calls.
        if (!record && !fs.existsSync(this.file)) continue;
        if (record?.holder === holder && record.pid === this.runner) {
          // The reservation is ours: a rename replaces it with our own record in one step.
          fs.renameSync(staging, this.file);
          return;
        }
        if (record && !processAlive(record.pid)) {
          fs.rmSync(this.file, { force: true });
          continue;
        }
        const current = record ? `${record.holder} (pid ${record.pid})` : `an unreadable ${this.file}`;
        if (current !== waitingFor) console.log(`[build-lock] ${holder} waits for ${current}`);
        waitingFor = current;
        await new Promise((resolve) => setTimeout(resolve, this.pollMs));
      }
    } finally {
      fs.rmSync(staging, { force: true });
    }
  }
}
