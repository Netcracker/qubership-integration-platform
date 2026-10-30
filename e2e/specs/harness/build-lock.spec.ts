/**
 * The build lock of `support/build-lock.ts`, which the `ui-server` and `vscode-server` setups hold
 * around their builds, and the reservation `globalSetup` makes for the UI bundle.
 *
 * Shown failing, each edit on its own case alone: the lock released as soon as it is taken ("two
 * holders never overlap"); every holder taken for alive ("a lock left by an exited process is
 * reclaimed"); a reservation taken over by any holder of the run ("the reserved bundle builds
 * first"); the reservation made for a fresh bundle ("the bundle is reserved only when ui-server will
 * build it").
 */
import { spawn, spawnSync, type ChildProcess } from "node:child_process";
import fs from "node:fs";
import { test, expect } from "@playwright/test";
import { BuildLock, BUNDLE_HOLDER, type BuildLockRecord } from "../../support/build-lock.js";
import { reserveBundleBuild } from "../../support/ui-server.js";

const POLL_MS = 20;

/** The runner of a case that makes no reservation: no record names pid 0. */
const NO_RUNNER = 0;

const pause = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

/** Resolves to `"settled"` when `promise` settles within `ms`, and to `"pending"` otherwise. */
const within = (promise: Promise<unknown>, ms: number) =>
  Promise.race([promise.then(() => "settled"), pause(ms).then(() => "pending")]);

/** A body that logs its start and end around a pause, as a build would run. */
const build = (events: string[], name: string) => async () => {
  events.push(`${name} start`);
  await pause(150);
  events.push(`${name} end`);
};

test.describe("build lock", () => {
  let sleeper: ChildProcess;
  let file: string;

  test.beforeAll(() => {
    // A third live pid besides the worker and its parent.
    sleeper = spawn(process.execPath, ["-e", "setTimeout(() => {}, 60000)"], { stdio: "ignore" });
  });

  test.afterAll(() => {
    sleeper.kill();
  });

  test.beforeEach(() => {
    file = test.info().outputPath("build.lock");
  });

  test("two holders never overlap", async () => {
    const events: string[] = [];
    const first = new BuildLock(file, process.pid, NO_RUNNER, POLL_MS);
    const second = new BuildLock(file, process.ppid, NO_RUNNER, POLL_MS);
    await Promise.all([first.run("ui-server", build(events, "first")), second.run("vscode-server", build(events, "second"))]);
    const inTurn = (one: string, other: string) => [`${one} start`, `${one} end`, `${other} start`, `${other} end`];
    expect([inTurn("first", "second"), inTurn("second", "first")]).toContainEqual(events);
    expect(fs.existsSync(file), "the last holder removes the lock file").toBe(false);

    // The holder may be another process.
    fs.writeFileSync(file, JSON.stringify({ holder: "vscode-server", pid: sleeper.pid! } satisfies BuildLockRecord));
    const waiting = first.run("ui-server", async () => {});
    expect(await within(waiting, 300)).toBe("pending");
    fs.rmSync(file);
    await waiting;
  });

  test("a lock left by an exited process is reclaimed", async () => {
    const exited = spawnSync(process.execPath, ["-e", ""]).pid;
    fs.writeFileSync(file, JSON.stringify({ holder: "vscode-server", pid: exited } satisfies BuildLockRecord));
    const lock = new BuildLock(file, process.pid, NO_RUNNER, POLL_MS);
    expect(await within(lock.run("ui-server", async () => {}), 2_000)).toBe("settled");
  });

  test("the reserved bundle builds first, or a drop frees the lock", async () => {
    // The runner is this process; the two setups run in its workers.
    const runner = new BuildLock(file, process.pid, NO_RUNNER, POLL_MS);
    const uiServer = new BuildLock(file, process.ppid, process.pid, POLL_MS);
    const vscodeServer = new BuildLock(file, sleeper.pid!, process.pid, POLL_MS);

    const events: string[] = [];
    expect(runner.reserve(BUNDLE_HOLDER)).toBe(true);
    const later = vscodeServer.run("vscode-server", build(events, "vscode-server"));
    expect(await within(later, 300), "vscode-server waits on the reservation").toBe("pending");
    await Promise.all([uiServer.run(BUNDLE_HOLDER, build(events, BUNDLE_HOLDER)), later]);
    expect(events[0]).toBe("ui-server start");

    expect(runner.reserve(BUNDLE_HOLDER)).toBe(true);
    const next = vscodeServer.run("vscode-server", async () => {});
    expect(await within(next, 300)).toBe("pending");
    uiServer.dropReservation(BUNDLE_HOLDER);
    expect(await within(next, 2_000), "vscode-server goes once ui-server builds nothing").toBe("settled");
  });

  test("the bundle is reserved only when ui-server will build it", async () => {
    const lock = new BuildLock(file, process.pid, NO_RUNNER, POLL_MS);
    const stale = async () => true;
    const fresh = async () => false;
    expect(await reserveBundleBuild(["schemas-build", "vscode-server", "vscode"], lock, stale)).toBe(false);
    expect(await reserveBundleBuild(["schemas-build", "ui-server", "vscode-server"], lock, fresh)).toBe(false);
    expect(fs.existsSync(file)).toBe(false);
    expect(await reserveBundleBuild(["schemas-build", "ui-server", "vscode-server"], lock, stale)).toBe(true);
    expect(JSON.parse(fs.readFileSync(file, "utf-8"))).toEqual({ holder: BUNDLE_HOLDER, pid: process.pid });
  });
});
