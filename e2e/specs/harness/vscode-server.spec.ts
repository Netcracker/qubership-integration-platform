/**
 * The `vscode-server` setup's command line, the freshness check that decides its two builds, and
 * the state file that tells it which server to stop or refuse.
 *
 * Shown failing, each edit alone turning only the case named: `SERVER_ARGS` matching port 3918
 * ("the server runs the pinned build"); the library marker back to all of `ui/dist-lib` ("a missing
 * output rebuilds it"); `RecordedServer.stop` skipping the live-owner check ("a server of another
 * live run"), the stop of the group ("a server a finished run recorded"), and the command-line check
 * ("a recorded pid that now runs something else").
 */
import { spawn } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { test, expect } from "@playwright/test";
import { repoRoot } from "../../env/host.js";
import { groupAlive, RecordedServer } from "../../support/process-group.js";
import { readUntil } from "../../support/poll.js";
import { readStateFile } from "../../support/state-file.js";
import { SCHEMA_OUTPUTS } from "../../support/schemas-build.js";
import {
  EXTENSION_INPUTS,
  EXTENSION_OUTPUT,
  SERVER_ARGS,
  serverCommand,
  staleBuilds,
  UI_LIB_INPUTS,
  UI_LIB_OUTPUT,
  VSCODE_COMMIT,
} from "../../support/vscode-server.js";
import { withEnv } from "../../support/with-env.js";

/** Each input and output of the two builds, relative to the repository root; `path.join` resolves the `../`. */
const UI_LIB = UI_LIB_INPUTS.map((each) => path.join("ui", each));
const EXTENSION = EXTENSION_INPUTS.map((each) => path.join("vscode-extension", each));
const OUTPUTS = [path.join("ui", UI_LIB_OUTPUT), path.join("vscode-extension", EXTENSION_OUTPUT)];

/** The temporary trees a case made, removed after it. */
const made: string[] = [];

function tempDir(prefix: string): string {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), prefix));
  made.push(dir);
  return dir;
}

/**
 * A repository tree holding every input and output, at 100 s and 200 s, with `changes` applied on
 * top: a number sets that path's mtime in seconds, `null` leaves it out. A path whose name holds a
 * dot is a file, any other path a directory holding one file.
 */
function repoTree(changes: Record<string, number | null>): string {
  const root = tempDir("e2e-vscode-server-");
  const mtimes: Record<string, number | null> = {
    ...Object.fromEntries([...UI_LIB, ...EXTENSION].map((each) => [each, 100])),
    ...Object.fromEntries(OUTPUTS.map((each) => [each, 200])),
    ...changes,
  };
  for (const [relative, seconds] of Object.entries(mtimes)) {
    if (seconds === null) continue;
    const file = path.basename(relative).includes(".") ? path.join(root, relative) : path.join(root, relative, "file");
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, "");
    fs.utimesSync(file, seconds, seconds);
  }
  return root;
}

test.describe("vscode-server", () => {
  test.afterEach(() => {
    for (const dir of made.splice(0)) fs.rmSync(dir, { recursive: true, force: true });
  });

  test("the server runs the pinned build on the fixture workspace", () => {
    const root = repoRoot();
    const { command, args, cwd } = withEnv({ E2E_VSCODE_COMMIT: undefined }, () => serverCommand(root));
    // The binary comes from the root `npm ci`, which the stack-free job does not run.
    expect(command).toBe(path.join(root, "node_modules", ".bin", "vscode-test-web"));
    const workspace = path.join(root, "e2e", "fixtures", "vscode", "workspace");
    expect(args).toEqual([
      "--browser",
      "none",
      "--host",
      "localhost",
      "--port",
      "3917",
      "--quality",
      "insiders",
      "--commit",
      VSCODE_COMMIT,
      "--extensionDevelopmentPath=.",
      workspace,
    ]);
    expect(VSCODE_COMMIT).toMatch(/^[0-9a-f]{40}$/);
    // The server exits on a workspace path that is not a directory.
    expect(path.isAbsolute(workspace) && fs.statSync(workspace).isDirectory()).toBe(true);
    // `.` resolves against the working directory to the extension.
    const manifest = JSON.parse(fs.readFileSync(path.join(cwd, "package.json"), "utf-8")) as { name: string };
    expect(manifest.name).toBe("@netcracker/qip-vscode-extension");
    // The teardown finds the server by this pattern; one that misses the command line stops nothing.
    expect(SERVER_ARGS.test([command, ...args].join(" "))).toBe(true);
  });

  test("E2E_VSCODE_COMMIT overrides the pinned build", () => {
    const commit = "0123456789abcdef0123456789abcdef01234567";
    const { args } = withEnv({ E2E_VSCODE_COMMIT: commit }, () => serverCommand("/repo"));
    expect(args[args.indexOf("--commit") + 1]).toBe(commit);
  });

  test("the builds skip the scripts that build the schemas", () => {
    const scripts = (dir: string) =>
      (JSON.parse(fs.readFileSync(path.join(repoRoot(), dir, "package.json"), "utf-8")) as { scripts: Record<string, string> })
        .scripts;
    // `prepare-deps`, which builds the schemas again, runs only from the extension's `build`.
    expect(scripts("vscode-extension")["compile-web"]).toBe("webpack");
    for (const [dir, name] of [["ui", "build:lib:bundled"], ["ui", "build:lib:types"], ["vscode-extension", "compile-web"]]) {
      expect(scripts(dir)[name], name).toBeDefined();
      expect(scripts(dir)[`pre${name}`], `pre${name}`).toBeUndefined();
    }
  });

  test("every input exists or is the output of an earlier build", () => {
    const built = [...SCHEMA_OUTPUTS.map((each) => path.join("schemas", each)), path.join("ui", "dist-lib")];
    const tracked = [...UI_LIB, ...EXTENSION].filter((each) => !built.includes(each));
    expect(tracked.filter((each) => !fs.existsSync(path.join(repoRoot(), each)))).toEqual([]);
  });

  test("current when both outputs are newer than their inputs", async () => {
    expect(await staleBuilds(repoTree({}))).toEqual({ uiLib: false, extension: false });
  });

  test("a library input rebuilds both, an extension input one", async () => {
    for (const input of new Set([...UI_LIB, ...EXTENSION])) {
      const uiLib = UI_LIB.includes(input);
      expect(await staleBuilds(repoTree({ [input]: 300 })), input).toEqual({ uiLib, extension: true });
    }
    // The cases the setup meets most: a schema build, a library build, and an edit to the manifest.
    expect(await staleBuilds(repoTree({ "schemas/dist": 300 }))).toEqual({ uiLib: true, extension: true });
    expect(await staleBuilds(repoTree({ "ui/dist-lib": 300 }))).toEqual({ uiLib: false, extension: true });
    expect(await staleBuilds(repoTree({ "vscode-extension/package.json": 300 }))).toEqual({
      uiLib: false,
      extension: true,
    });
  });

  test("a missing output rebuilds it and what is built from it", async () => {
    // `build:lib` empties `ui/dist-lib`, the marker included.
    expect(await staleBuilds(repoTree({ "ui/dist-lib/.e2e-build": null }))).toEqual({ uiLib: true, extension: true });
    expect(await staleBuilds(repoTree({ "vscode-extension/dist/.e2e-build": null }))).toEqual({
      uiLib: false,
      extension: true,
    });
  });

  test.describe("the state file", () => {
    /** A process group of its own whose command line `pattern` matches, as the setup starts the server. */
    function startGroup(): number {
      const child = spawn(process.execPath, ["-e", "setTimeout(() => {}, 60000)", "e2e-recorded-server"], { detached: true, stdio: "ignore" });
      child.unref();
      if (child.pid === undefined) throw new Error("the stand-in server did not start");
      return child.pid;
    }
    const pattern = /e2e-recorded-server/;
    /** A pid no process holds: a child that has exited. */
    async function deadPid(): Promise<number> {
      const child = spawn(process.execPath, ["-e", ""], { stdio: "ignore" });
      await new Promise((resolve) => child.on("exit", resolve));
      return child.pid!;
    }
    const stateFile = () => path.join(tempDir("e2e-recorded-server-"), "server.json");

    test("a server of another live run is refused and left running", async () => {
      const file = stateFile();
      const pid = startGroup();
      try {
        // This worker stands for the other run's runner, alive and not this run's.
        new RecordedServer(file, pattern, process.pid).record(pid);
        const thisRun = new RecordedServer(file, pattern, await deadPid());
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
        new RecordedServer(file, pattern, await deadPid()).record(pid);
        const thisRun = new RecordedServer(file, pattern, process.pid);
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
        const server = new RecordedServer(file, /vscode-test-web\b.*--port 3917\b/, process.pid);
        server.record(pid);
        await server.stop();
        expect(groupAlive(pid)).toBe(true);
        expect(fs.existsSync(file)).toBe(false);
      } finally {
        process.kill(-pid, "SIGKILL");
      }
    });
  });
});
