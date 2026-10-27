/**
 * A server the suite starts in a process group of its own, so that its teardown stops the npm or
 * node wrapper and the server behind it together. `ui-server` and `vscode-server` use it.
 */
import { execFileSync } from "node:child_process";
import { sleep } from "./poll.js";

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
