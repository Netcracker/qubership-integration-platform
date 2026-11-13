/**
 * A server the suite starts in a process group of its own, so that its teardown stops the npm or
 * node wrapper and the server behind it together. `ui-server` and `vscode-server` use it.
 */
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import { readUntil } from "./poll.js";
import { processAlive } from "./run.js";
import { readStateFile, writeStateFile } from "./state-file.js";

// Windows checks the wrapper; POSIX can also find children after the wrapper exits.
export function groupAlive(pid: number): boolean {
  if (process.platform === "win32") return processAlive(pid);
  try {
    process.kill(-pid, 0);
    return true;
  } catch {
    return false;
  }
}

/**
 * Whether a process of the group led by `pid` still runs a command line matching `args`. A stale
 * state file can name a pid that has since been reused by an unrelated group.
 */
export function groupRuns(pid: number, args: RegExp): boolean {
  if (process.platform === "win32") {
    const output = execFileSync("powershell.exe", [
      "-NoProfile", "-NonInteractive", "-Command",
      "Get-CimInstance Win32_Process | Select-Object ProcessId,ParentProcessId,CommandLine | ConvertTo-Json -Compress",
    ], { encoding: "utf-8", windowsHide: true });
    const rows = JSON.parse(output) as { ProcessId: number; ParentProcessId: number; CommandLine: string | null }[];
    const descendants = new Set([pid]);
    let size: number;
    do {
      size = descendants.size;
      for (const row of rows) {
        if (descendants.has(row.ParentProcessId)) descendants.add(row.ProcessId);
      }
    } while (size !== descendants.size);
    return rows.some((row) => descendants.has(row.ProcessId) && args.test(row.CommandLine ?? ""));
  }
  return execFileSync("ps", ["-A", "-o", "pgid=,args="], { encoding: "utf-8" })
    .split("\n")
    .some((line) => {
      const [, pgid, command] = /^\s*(\d+)\s+(.*)$/.exec(line) ?? [];
      return Number(pgid) === pid && args.test(command);
    });
}

// taskkill stops the Windows tree; POSIX gets SIGTERM, then SIGKILL after 10 seconds.
export async function stopGroup(pid: number): Promise<void> {
  if (process.platform === "win32") {
    if (processAlive(pid)) {
      try {
        terminateGroup(pid);
      } catch (error) {
        if (processAlive(pid)) throw error;
      }
    }
    return;
  }
  try {
    process.kill(-pid, "SIGTERM");
  } catch {
    return; // The group has already exited.
  }
  const alive = await readUntil(async () => groupAlive(pid), (each) => !each, 10_000, 100);
  if (alive) process.kill(-pid, "SIGKILL");
}

export function terminateGroup(pid: number): void {
  if (process.platform === "win32") {
    execFileSync("taskkill.exe", ["/PID", String(pid), "/T", "/F"], { stdio: "ignore", windowsHide: true });
  } else {
    process.kill(-pid, "SIGTERM");
  }
}

/** What a server's state file holds: its process group, and the runner of the run that started it. */
export interface ServerRecord {
  pid: number;
  owner: number;
}

/**
 * A server whose state file records its process group, so that a later run can stop the server an
 * interrupted run left behind. `owner` defaults to the Playwright runner, whose pid `.e2e-runs.json`
 * records; setup and teardown run in its child workers.
 */
export class RecordedServer {
  constructor(
    readonly stateFile: string,
    /** The server's command line, which tells it apart from an unrelated group that reuses its pid. */
    readonly commandLine: RegExp,
    readonly owner: number = process.ppid,
  ) {}

  /** The runner pid of another run still in progress that started the recorded server, or `null`. */
  otherLiveOwner(): number | null {
    const state = readStateFile<ServerRecord>(this.stateFile);
    return state && state.owner !== this.owner && processAlive(state.owner) ? state.owner : null;
  }

  record(pid: number): void {
    writeStateFile<ServerRecord>(this.stateFile, { pid, owner: this.owner });
  }

  /**
   * Stops the recorded server, if this run or a dead one started it, and waits for its process
   * group to exit. A server of a run still in progress stays up.
   */
  async stop(): Promise<void> {
    if (this.otherLiveOwner() !== null) return;
    const state = readStateFile<ServerRecord>(this.stateFile);
    fs.rmSync(this.stateFile, { force: true });
    if (!state?.pid || !groupRuns(state.pid, this.commandLine)) return;
    await stopGroup(state.pid);
  }
}
