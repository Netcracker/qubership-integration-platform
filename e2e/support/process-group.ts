/**
 * A server the suite starts in a process group of its own, so that its teardown stops the npm or
 * node wrapper and the server behind it together. `ui-server` and `vscode-server` use it.
 */
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import { sleep } from "./poll.js";
import { processAlive } from "./run.js";
import { readStateFile, writeStateFile } from "./state-file.js";

/** Whether any process of the group led by `pid` is still running; a wrapper may exit before its child. */
export function groupAlive(pid: number): boolean {
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
  return execFileSync("ps", ["-A", "-o", "pgid=,args="], { encoding: "utf-8" })
    .split("\n")
    .some((line) => {
      const [, pgid, command] = /^\s*(\d+)\s+(.*)$/.exec(line) ?? [];
      return Number(pgid) === pid && args.test(command);
    });
}

/** Sends SIGTERM to the group, and SIGKILL when it is still running 10 s later. */
export async function stopGroup(pid: number): Promise<void> {
  try {
    process.kill(-pid, "SIGTERM");
  } catch {
    return; // The group has already exited.
  }
  const deadline = Date.now() + 10_000;
  while (groupAlive(pid) && Date.now() < deadline) {
    await sleep(100);
  }
  if (groupAlive(pid)) process.kill(-pid, "SIGKILL");
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
