/**
 * `RecordedServer`, the state file that tells `ui-server` and `vscode-server` which server to stop or
 * refuse.
 *
 * Shown failing, each edit alone turning only the case named: `RecordedServer.stop` skipping the
 * live-owner check ("a server of another live run"), the stop of the group ("a server a finished run
 * recorded"), and the command-line check ("a recorded pid that now runs something else").
 */
import { spawn } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { test, expect } from "@playwright/test";
import { groupAlive, RecordedServer } from "../../support/process-group.js";
import { readUntil } from "../../support/poll.js";
import { readStateFile } from "../../support/state-file.js";

/** The command line of the stand-in server, which `PATTERN` matches. */
const PATTERN = /e2e-recorded-server/;

/** A process group of its own whose command line `PATTERN` matches, as a setup starts its server. */
function startGroup(): number {
  const child = spawn(process.execPath, ["-e", "setTimeout(() => {}, 60000)", "e2e-recorded-server"], { detached: true, stdio: "ignore" });
  child.unref();
  if (child.pid === undefined) throw new Error("the stand-in server did not start");
  return child.pid;
}

/** A pid no process holds: a child that has exited. */
async function deadPid(): Promise<number> {
  const child = spawn(process.execPath, ["-e", ""], { stdio: "ignore" });
  await new Promise((resolve) => child.on("exit", resolve));
  return child.pid!;
}

test.describe("recorded server", () => {
  let dir: string;
  test.beforeEach(() => {
    dir = fs.mkdtempSync(path.join(os.tmpdir(), "e2e-recorded-server-"));
  });
  test.afterEach(() => {
    fs.rmSync(dir, { recursive: true, force: true });
  });
  const stateFile = () => path.join(dir, "server.json");

  test("a server of another live run is refused and left running", async () => {
    const file = stateFile();
    const pid = startGroup();
    try {
      // This worker stands for the other run's runner, alive and not this run's.
      new RecordedServer(file, PATTERN, process.pid).record(pid);
      const thisRun = new RecordedServer(file, PATTERN, await deadPid());
      expect(thisRun.otherLiveOwner()).toBe(process.pid);
      await thisRun.stop();
      expect(groupAlive(pid)).toBe(true);
      expect(readStateFile(file)).toEqual({ pid, owner: process.pid });
    } finally {
      process.kill(-pid, "SIGKILL");
    }
  });

  test("a server a finished run recorded is stopped", async () => {
    const file = stateFile();
    const pid = startGroup();
    try {
      new RecordedServer(file, PATTERN, await deadPid()).record(pid);
      const thisRun = new RecordedServer(file, PATTERN, process.pid);
      expect(thisRun.otherLiveOwner()).toBeNull();
      await thisRun.stop();
      expect(await readUntil(async () => groupAlive(pid), (alive) => !alive, 5_000, 50)).toBe(false);
      expect(fs.existsSync(file)).toBe(false);
    } finally {
      if (groupAlive(pid)) process.kill(-pid, "SIGKILL");
    }
  });

  test("a recorded pid that now runs something else is left alone", async () => {
    const file = stateFile();
    const pid = startGroup();
    try {
      // The recorded command line is another server's, so the stand-in is not the recorded server.
      const server = new RecordedServer(file, /e2e-another-server/, process.pid);
      server.record(pid);
      await server.stop();
      expect(groupAlive(pid)).toBe(true);
      expect(fs.existsSync(file)).toBe(false);
    } finally {
      process.kill(-pid, "SIGKILL");
    }
  });
});
