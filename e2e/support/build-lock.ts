/**
 * Serializes the UI bundle, UI library, and extension builds. The UI bundle goes first when
 * `globalSetup` reserves the lock for `ui-server` in the runner's name.
 *
 * A directory holds a uniquely named owner file. A waiter removes only the dead owner's entry;
 * rmdir then either removes the empty directory or leaves a new owner's nonempty directory alone.
 */
import { randomUUID } from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { processAlive } from "./run.js";

/** Outside `test-results/`, which Playwright clears at the start of a run. */
export const BUILD_LOCK_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", ".e2e-build.lock.d");

export const BUNDLE_HOLDER = "ui-server";

interface Owner {
  holder: string;
  pid: number;
  entry: string;
}

export class BuildLock {
  constructor(
    readonly file = BUILD_LOCK_DIR,
    readonly pid = process.pid,
    readonly runner = process.ppid,
    readonly pollMs = 500,
  ) {}

  private entry(): string {
    return `${this.pid}.${randomUUID()}.json`;
  }

  private staging(entry: string): string {
    return `${this.file}.${entry}.tmp`;
  }

  private create(holder: string, entry: string): boolean {
    const staging = this.staging(entry);
    fs.mkdirSync(staging, { recursive: true });
    fs.writeFileSync(path.join(staging, entry), JSON.stringify({ holder }));
    try {
      // The directory is already nonempty when it becomes visible at the lock path.
      fs.renameSync(staging, this.file);
      return true;
    } catch (error) {
      const code = (error as NodeJS.ErrnoException).code;
      if (["EEXIST", "ENOTEMPTY"].includes(code ?? "")) return false;
      // Windows also reports EPERM while the destination directory is being removed.
      if (code === "EPERM") {
        const destination = fs.statSync(this.file, { throwIfNoEntry: false });
        if (!destination || destination.isDirectory()) return false;
      }
      throw error;
    }
  }

  private read(): Owner | null {
    try {
      for (const entry of fs.readdirSync(this.file)) {
        try {
          const record = JSON.parse(fs.readFileSync(path.join(this.file, entry), "utf-8")) as { holder: string };
          // Renaming the entry transfers a reservation to its worker atomically.
          return { holder: record.holder, pid: Number(entry.split(".")[0]), entry };
        } catch (error) {
          // Windows can report a deletion in progress as EPERM instead of ENOENT.
          if (!["ENOENT", "EPERM"].includes((error as NodeJS.ErrnoException).code ?? "")) throw error;
        }
      }
      return null;
    } catch (error) {
      if (["ENOENT", "EPERM"].includes((error as NodeJS.ErrnoException).code ?? "")) return null;
      throw error;
    }
  }

  private release(entry: string): void {
    fs.rmSync(path.join(this.file, entry), { force: true });
    try {
      fs.rmdirSync(this.file);
    } catch (error) {
      if (!["ENOENT", "ENOTEMPTY", "EEXIST"].includes((error as NodeJS.ErrnoException).code ?? "")) throw error;
    }
  }

  reserve(holder: string): boolean {
    const entry = this.entry();
    try {
      return this.create(holder, entry);
    } finally {
      fs.rmSync(this.staging(entry), { recursive: true, force: true });
    }
  }

  dropReservation(holder: string): void {
    const record = this.read();
    if (record?.holder === holder && record.pid === this.runner) this.release(record.entry);
  }

  async run<T>(holder: string, body: () => Promise<T>): Promise<T> {
    const entry = await this.acquire(holder);
    try {
      return await body();
    } finally {
      this.release(entry);
    }
  }

  private async acquire(holder: string): Promise<string> {
    const entry = this.entry();
    let waitingFor = "";
    try {
      for (;;) {
        if (this.create(holder, entry)) return entry;
        const record = this.read();
        if (!record) {
          await new Promise((resolve) => setTimeout(resolve, this.pollMs));
          continue;
        }
        if (record.holder === holder && record.pid === this.runner) {
          try {
            fs.renameSync(path.join(this.file, record.entry), path.join(this.file, entry));
            return entry;
          } catch (error) {
            if ((error as NodeJS.ErrnoException).code === "ENOENT") continue;
            throw error;
          }
        }
        if (!processAlive(record.pid)) {
          this.release(record.entry);
          continue;
        }
        const current = `${record.holder} (pid ${record.pid})`;
        if (current !== waitingFor) console.log(`[build-lock] ${holder} waits for ${current}`);
        waitingFor = current;
        await new Promise((resolve) => setTimeout(resolve, this.pollMs));
      }
    } finally {
      fs.rmSync(this.staging(entry), { recursive: true, force: true });
    }
  }
}
