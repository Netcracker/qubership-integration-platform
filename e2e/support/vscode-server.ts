/**
 * VS Code for the web on port 3917, serving the extension of this checkout and the fixture
 * workspace `fixtures/vscode/workspace/`.
 *
 * The `vscode-server` setup project calls `ensureVsCodeServer`, and its teardown project calls
 * `stopVsCodeServer`. The setup refuses a server it did not start: it may serve another checkout's
 * extension or another workspace.
 */
import { spawn } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { httpStatus, newestMtimeMs, repoRoot, runToCompletion } from "../env/host.js";
import { BuildLock } from "./build-lock.js";
import { groupAlive, RecordedServer } from "./process-group.js";
import { readUntil } from "./poll.js";

export const VSCODE_PORT = 3917;

/**
 * The insiders build the `vscode` project runs against, so that a new build reaches it only through
 * a change here. The extension's own integration suite keeps following the latest insiders build.
 */
export const VSCODE_COMMIT = "c718b461a175300ec6f4949c808291d2149f3f8d";

/** The pinned build, or the one `E2E_VSCODE_COMMIT` names. */
function vscodeCommit(): string {
  return process.env.E2E_VSCODE_COMMIT ?? VSCODE_COMMIT;
}

const HERE = path.dirname(fileURLToPath(import.meta.url));

/** Outside `test-results/`, which Playwright clears at the start of a run the server may outlive. */
const STATE_FILE = path.resolve(HERE, "..", ".e2e-vscode-server.json");
export const SERVER_LOG = path.resolve(HERE, "..", ".e2e-vscode-server.log");

/** What the UI library is built from, relative to `ui/`. */
export const UI_LIB_INPUTS = [
  "src",
  ".env",
  "package.json",
  "vite.config.lib.bundled.mts",
  "tsconfig.lib.json",
  "../schemas/dist",
  "../package-lock.json",
];

/**
 * Written into `ui/dist-lib` after the suite's two library builds, and read as the time of those
 * builds. Other builds write `dist-lib` too: `build:lib` empties it and leaves no bundled file.
 */
export const UI_LIB_OUTPUT = "dist-lib/.e2e-build";

const UI_LIB_BUILDS = [
  ["run", "build:lib:bundled", "-w", "@netcracker/qip-ui"],
  ["run", "build:lib:types", "-w", "@netcracker/qip-ui"],
];

/** What `compile-web` bundles and copies, relative to `vscode-extension/`. */
export const EXTENSION_INPUTS = [
  "src",
  "package.json",
  "webpack.config.js",
  "media",
  "../ui/dist-lib",
  "../schemas/dist",
  "../schemas/assets",
];

/**
 * Written into `vscode-extension/dist` after the suite's `compile-web`, and read as the time of that
 * build. Webpack leaves an asset whose content has not changed unwritten, so the mtimes in `dist`
 * stay older than a rebuilt `ui/dist-lib` that came out the same.
 */
export const EXTENSION_OUTPUT = "dist/.e2e-build";

const EXTENSION_BUILD = ["run", "compile-web", "-w", "@netcracker/qip-vscode-extension"];

/** Whether `output` is missing or older than the newest of `inputs`, all relative to `dir`. */
async function outputIsStale(dir: string, inputs: string[], output: string): Promise<boolean> {
  const built = await newestMtimeMs(dir, [output]);
  return built === 0 || (await newestMtimeMs(dir, inputs)) > built;
}

/** Which of the two builds the setup runs, judged before either of them. */
export async function staleBuilds(root = repoRoot()): Promise<{ uiLib: boolean; extension: boolean }> {
  const uiLib = await outputIsStale(path.join(root, "ui"), UI_LIB_INPUTS, UI_LIB_OUTPUT);
  // A rebuilt library is an input of the extension, newer than its marker once built.
  const extension =
    uiLib || (await outputIsStale(path.join(root, "vscode-extension"), EXTENSION_INPUTS, EXTENSION_OUTPUT));
  return { uiLib, extension };
}

/**
 * The server's command line. `--browser none` leaves the browser to the cases, and the working
 * directory puts the downloaded build under `vscode-extension/.vscode-test-web/`, which is gitignored.
 */
export function serverCommand(root = repoRoot()): { command: string; args: string[]; cwd: string } {
  return {
    command: path.join(root, "node_modules", ".bin", "vscode-test-web"),
    args: [
      "--browser",
      "none",
      "--host",
      "localhost",
      "--port",
      String(VSCODE_PORT),
      "--quality",
      "insiders",
      "--commit",
      vscodeCommit(),
      "--extensionDevelopmentPath=.",
      path.join(root, "e2e", "fixtures", "vscode", "workspace"),
    ],
    cwd: path.join(root, "vscode-extension"),
  };
}

/** The server's command line, which `stopVsCodeServer` looks for in the recorded process group. */
export const SERVER_ARGS = new RegExp(`vscode-test-web\\b.*--port ${VSCODE_PORT}\\b`);

const server = new RecordedServer(STATE_FILE, SERVER_ARGS);

const workbenchUrl = () => `http://localhost:${VSCODE_PORT}/`;

/** Runs the stale builds while holding the build lock, after the UI bundle when `ui-server` builds it. */
async function buildStale(): Promise<string[]> {
  const { uiLib, extension } = await staleBuilds();
  const built: string[] = [];
  if (!uiLib && !extension) return built;
  await new BuildLock().run("vscode-server", async () => {
    if (uiLib) {
      for (const args of UI_LIB_BUILDS) await runToCompletion("npm", args, repoRoot(), process.env);
      fs.writeFileSync(path.join(repoRoot(), "ui", UI_LIB_OUTPUT), "");
      built.push("the UI library");
    }
    if (extension) {
      await runToCompletion("npm", EXTENSION_BUILD, repoRoot(), process.env);
      fs.writeFileSync(path.join(repoRoot(), "vscode-extension", EXTENSION_OUTPUT), "");
      built.push("the extension");
    }
  });
  return built;
}

/** Starts the server in its own process group, so the teardown stops node and its children together. */
function startServer(): number {
  const { command, args, cwd } = serverCommand();
  const log = fs.openSync(SERVER_LOG, "w");
  const child = spawn(command, args, { cwd, detached: true, stdio: ["ignore", log, log] });
  fs.closeSync(log);
  child.unref();
  if (child.pid === undefined) throw new Error("vscode-test-web did not start: no process id");
  return child.pid;
}

/**
 * Polls the workbench page until it answers 200. On a cold cache the server first downloads the
 * pinned build, a 54 MB (53561667 bytes) archive, and listens only after that. It exits when the
 * download fails or the workspace path is not a directory.
 */
async function waitForWorkbench(pid: number, budgetMs: number): Promise<boolean> {
  const last = await readUntil(
    async () => ({ status: await httpStatus(workbenchUrl()), alive: groupAlive(pid) }),
    ({ status, alive }) => status === 200 || !alive,
    budgetMs,
  );
  return last.status === 200;
}

/**
 * Builds what is stale and serves the workbench on 3917, or fails naming the reason.
 *
 * A server left running by an interrupted run is stopped first, since its state file still names
 * it. A server whose run is still going fails the setup, and so does any other server on the port.
 */
export async function ensureVsCodeServer(): Promise<{ pid: number; built: string[] }> {
  const owner = server.otherLiveOwner();
  if (owner !== null) {
    throw new Error(
      `port ${VSCODE_PORT} is served by the vscode-test-web of another e2e run still in progress ` +
        `(runner pid ${owner}). Wait for it to finish or stop it: one run at a time.`,
    );
  }
  await stopVsCodeServer();
  if ((await httpStatus(workbenchUrl())) !== 0) {
    throw new Error(
      `port ${VSCODE_PORT} already answers, and this run did not start the server. It may serve ` +
        `another checkout or another workspace, so stop it and run again.`,
    );
  }
  // A missing binary would otherwise surface after the builds, as an unhandled `error` event of the child.
  const { command } = serverCommand();
  if (!fs.existsSync(command)) {
    throw new Error(`${command} does not exist: run \`npm ci --legacy-peer-deps\` at the repository root`);
  }
  const built = await buildStale();
  const pid = startServer();
  server.record(pid);
  if (!(await waitForWorkbench(pid, 300_000))) {
    await stopVsCodeServer();
    const log = fs.readFileSync(SERVER_LOG, "utf-8").split("\n").slice(-20).join("\n");
    throw new Error(`the VS Code workbench does not answer on port ${VSCODE_PORT}. Server log:\n${log}`);
  }
  return { pid, built };
}

/** Stops the recorded server, as `RecordedServer.stop` does. */
export async function stopVsCodeServer(): Promise<void> {
  await server.stop();
}
