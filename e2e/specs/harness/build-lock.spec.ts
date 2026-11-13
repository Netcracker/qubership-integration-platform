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
import path from "node:path";
import { test, expect } from "@playwright/test";
import { repoRoot } from "../../env/host.js";
import { BuildLock, BUNDLE_HOLDER } from "../../support/build-lock.js";
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
    expect(fs.existsSync(file), "the last holder removes the lock directory").toBe(false);

    // The holder may be another process.
    expect(new BuildLock(file, sleeper.pid!, NO_RUNNER, POLL_MS).reserve("vscode-server")).toBe(true);
    const waiting = first.run("ui-server", async () => {});
    expect(await within(waiting, 300)).toBe("pending");
    fs.rmSync(file, { recursive: true });
    await waiting;
  });

  test("an EPERM collision waits for the lock owner", async () => {
    const original = fs.renameSync;
    fs.renameSync = (from, to) => {
      if (String(to) === file && fs.existsSync(file)) {
        throw Object.assign(new Error("destination exists"), { code: "EPERM" });
      }
      original(from, to);
    };
    try {
      const events: string[] = [];
      const first = new BuildLock(file, process.pid, NO_RUNNER, POLL_MS);
      const second = new BuildLock(file, process.ppid, NO_RUNNER, POLL_MS);
      await Promise.all([first.run("ui-server", build(events, "first")), second.run("vscode-server", build(events, "second"))]);
      expect(events).toEqual(["first start", "first end", "second start", "second end"]);
    } finally {
      fs.renameSync = original;
    }
  });

  test("an EPERM destination removed during rename is retried", async () => {
    const original = fs.renameSync;
    let blocked = true;
    fs.renameSync = (from, to) => {
      if (String(to) === file && blocked) {
        blocked = false;
        throw Object.assign(new Error("destination removal is pending"), { code: "EPERM" });
      }
      original(from, to);
    };
    try {
      const body = test.info().outputPath("built");
      await new BuildLock(file, process.pid, NO_RUNNER, POLL_MS).run("ui-server", async () => {
        fs.writeFileSync(body, "built");
      });
      expect(fs.readFileSync(body, "utf8")).toBe("built");
      expect(fs.existsSync(file)).toBe(false);
    } finally {
      fs.renameSync = original;
    }
  });

  test("a lock left by an exited process is reclaimed", async () => {
    const exited = spawnSync(process.execPath, ["-e", ""]).pid;
    expect(new BuildLock(file, exited, NO_RUNNER, POLL_MS).reserve("vscode-server")).toBe(true);
    const lock = new BuildLock(file, process.pid, NO_RUNNER, POLL_MS);
    expect(await within(lock.run("ui-server", async () => {}), 2_000)).toBe("settled");
  });

  test("two processes reclaiming the same lock never overlap", async () => {
    const exited = spawnSync(process.execPath, ["-e", ""]).pid;
    expect(new BuildLock(file, exited, NO_RUNNER, POLL_MS).reserve("abandoned")).toBe(true);
    const source = `
      import fs from "node:fs";
      import { registerHooks } from "node:module";
      import { pathToFileURL } from "node:url";
      registerHooks({ resolve(specifier, context, next) {
        try { return next(specifier, context); }
        catch (error) {
          if (specifier.endsWith(".js")) return next(specifier.slice(0, -3) + ".ts", context);
          throw error;
        }
      } });
      const { BuildLock } = await import(pathToFileURL(process.argv[1]).href);
      const [file, deadPid, prefix] = process.argv.slice(2);
      const pause = () => new Promise(resolve => setTimeout(resolve, 10));
      const signal = name => fs.writeFileSync(prefix + "." + name, "");
      const exists = name => fs.existsSync(prefix + "." + name);
      const originalKill = process.kill;
      let stopped = false;
      process.kill = function(pid, value) {
        try { return originalKill.call(process, pid, value); }
        catch (error) {
          if (pid === Number(deadPid) && value === 0 && !stopped) {
            stopped = true;
            signal("read");
            while (!exists("recover")) Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 10);
          }
          throw error;
        }
      };
      console.log = () => signal("waiting");
      await new BuildLock(file, process.pid, 0, 10).run("contender", async () => {
        signal("entered");
        while (!exists("finish")) await pause();
      });
    `;
    const children: ChildProcess[] = [];
    const start = (name: string) => {
      const prefix = test.info().outputPath(name);
      const child = spawn(process.execPath, ["--experimental-transform-types", "--input-type=module", "-e", source,
        path.join(repoRoot(), "e2e/support/build-lock.ts"), file, String(exited), prefix], { stdio: "pipe" });
      children.push(child);
      let stderr = "";
      child.stderr!.on("data", (chunk) => { stderr += String(chunk); });
      const exists = (name: string) => fs.existsSync(`${prefix}.${name}`);
      return {
        child,
        exists,
        signal: (name: string) => fs.writeFileSync(`${prefix}.${name}`, ""),
        wait: (name: string) => expect.poll(() => exists(name) ? "ready" : child.exitCode === null ? "pending" : stderr).toBe("ready"),
        done: new Promise((resolve) => child.on("exit", resolve)),
      };
    };
    try {
      const first = start("first");
      const second = start("second");
      await Promise.all([first.wait("read"), second.wait("read")]);
      first.signal("recover");
      await first.wait("entered");
      second.signal("recover");
      await expect.poll(() => second.exists("waiting") || second.exists("entered")).toBe(true);
      expect(second.exists("entered"), "the second reclaimer waits for the new owner").toBe(false);
      first.signal("finish");
      expect(await first.done).toBe(0);
      await second.wait("entered");
      second.signal("finish");
      expect(await second.done).toBe(0);
      expect(fs.existsSync(file)).toBe(false);
    } finally {
      for (const child of children) if (child.exitCode === null) child.kill("SIGKILL");
    }
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
    expect(fs.readdirSync(file)).toHaveLength(1);
  });
});
