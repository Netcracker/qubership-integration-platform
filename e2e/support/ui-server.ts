/**
 * The production bundle on port 4200, where nginx sends every request outside `/api/`.
 *
 * The `ui-server` setup project calls `ensureUiServer`, and its teardown project calls
 * `stopUiServer`. A server already answering on the port is used as found and never stopped, unless
 * the state file records it as a preview this suite started: it may be a developer's dev server,
 * which serves a different artifact than the bundle.
 */
import { execFileSync, spawn } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { newestMtimeMs, repoRoot } from "../env/host.js";
import { processAlive } from "./run.js";
import { sleep } from "./poll.js";
import { readStateFile, writeStateFile } from "./state-file.js";

export const UI_PORT = 4200;

const HERE = path.dirname(fileURLToPath(import.meta.url));

/**
 * Outside `test-results/`, which Playwright clears at the start of a run the server may outlive.
 * Both targets share the name: the bundle on 4200 is served from the host whichever stack is behind
 * the proxy.
 */
const STATE_FILE = path.resolve(HERE, "..", ".e2e-ui-server.json");
const PREVIEW_LOG = path.resolve(HERE, "..", ".e2e-ui-server.log");

/**
 * Written into `ui/dist` after the suite's own build. `vite build` empties `dist`, so a bundle built
 * any other way, possibly without `VITE_PRODUCTION_MODE=false`, has no marker and is rebuilt.
 */
const BUILD_MARKER = "dist/.e2e-build";

/**
 * What the bundle is built from, relative to `ui/`: its sources, the `.env` files Vite reads in a
 * production build, the schema sources the element forms are generated from, the help pages
 * `fetch-docs` copies, and the lockfile.
 */
const BUNDLE_INPUTS = [
  "src",
  "public",
  "index.html",
  "vite.config.ts",
  "package.json",
  ".env",
  ".env.local",
  ".env.production",
  ".env.production.local",
  "../schemas/src/main",
  "../help/docs",
  "../package-lock.json",
];

/** The process group of the preview this suite started, and the run that started it. */
interface UiServerState {
  pid: number;
  owner: number;
}

/** The Playwright runner, whose pid `.e2e-runs.json` records; setup and teardown run in its child workers. */
const RUNNER = process.ppid;

function uiDir(): string {
  return path.resolve(repoRoot(), "ui");
}

/** Any HTTP answer counts: the port is taken, whatever serves it. */
async function answers(port: number): Promise<boolean> {
  try {
    await fetch(`http://localhost:${port}/`, { signal: AbortSignal.timeout(2_000) });
    return true;
  } catch {
    return false;
  }
}

/**
 * The variables the suite builds the bundle with, which override the `.env` files.
 *
 * `VITE_PRODUCTION_MODE=false` shows the testing screens. An empty `VITE_AI_SERVICE_URL` makes the
 * AI health check ask the page's own origin, the proxy of whichever target is running. A URL a
 * developer's `.env.local` names, such as `http://localhost:8080`, is refused on the Kubernetes
 * target, and the page guard fails every case on the console error.
 */
const BUILD_ENV: Record<string, string> = {
  VITE_PRODUCTION_MODE: "false",
  VITE_AI_SERVICE_URL: "",
};

const MARKER_TEXT = Object.entries(BUILD_ENV)
  .map(([name, value]) => `${name}=${value}\n`)
  .join("");

/**
 * Whether `ui/dist` is missing, was built outside the suite or with other variables, or is older
 * than its inputs.
 */
async function bundleIsStale(): Promise<boolean> {
  const built = await newestMtimeMs(uiDir(), [BUILD_MARKER]);
  if (built === 0) return true;
  if (fs.readFileSync(path.resolve(uiDir(), BUILD_MARKER), "utf-8") !== MARKER_TEXT) return true;
  return (await newestMtimeMs(uiDir(), BUNDLE_INPUTS)) > built;
}

/** Runs a command to completion and keeps its output, so a failure can quote the end of it. */
function runToCompletion(command: string, args: string[], cwd: string, env: NodeJS.ProcessEnv): Promise<void> {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { cwd, env, stdio: ["ignore", "pipe", "pipe"] });
    const output: string[] = [];
    child.stdout.on("data", (chunk: Buffer) => output.push(chunk.toString()));
    child.stderr.on("data", (chunk: Buffer) => output.push(chunk.toString()));
    child.on("error", reject);
    child.on("close", (code) => {
      if (code === 0) return resolve();
      const tail = output.join("").split("\n").slice(-40).join("\n");
      reject(new Error(`${command} ${args.join(" ")} exited ${code}:\n${tail}`));
    });
  });
}

/**
 * Builds `schemas/dist` and then the bundle, the way the UI workspace does, `fetch-docs` included.
 *
 * `schemas/dist` is gitignored and `npm install` does not create it, and the UI resolves
 * `@netcracker/qip-schemas` to it. Rebuilding it with every bundle takes about 6 seconds and keeps
 * the element forms in step with the schema sources. `BUILD_ENV` is read at build time.
 */
async function buildBundle(): Promise<void> {
  await runToCompletion("npm", ["run", "build", "-w", "@netcracker/qip-schemas"], repoRoot(), process.env);
  await runToCompletion("npm", ["run", "build", "-w", "@netcracker/qip-ui"], repoRoot(), {
    ...process.env,
    ...BUILD_ENV,
  });
  fs.writeFileSync(path.resolve(uiDir(), BUILD_MARKER), MARKER_TEXT);
}

/** Starts `vite preview` in its own process group, so the teardown can stop npm and vite together. */
function startPreview(): number {
  const log = fs.openSync(PREVIEW_LOG, "w");
  const child = spawn(
    "npm",
    ["run", "preview", "-w", "@netcracker/qip-ui", "--", "--port", String(UI_PORT), "--strictPort"],
    { cwd: repoRoot(), detached: true, stdio: ["ignore", log, log] },
  );
  fs.closeSync(log);
  child.unref();
  if (child.pid === undefined) throw new Error("vite preview did not start: no process id");
  return child.pid;
}

/**
 * Whether a process of the group led by `pid` still runs `preview --port 4200`. A stale state file
 * can name a pid that has since been reused by an unrelated group.
 */
function previewGroup(pid: number): boolean {
  const preview = new RegExp(`\\bpreview\\b.*--port ${UI_PORT}\\b`);
  return execFileSync("ps", ["-A", "-o", "pgid=,args="], { encoding: "utf-8" })
    .split("\n")
    .some((line) => {
      const [, pgid, args] = /^\s*(\d+)\s+(.*)$/.exec(line) ?? [];
      return Number(pgid) === pid && preview.test(args);
    });
}

/** Whether any process of the group led by `pid` is still running; npm may exit before vite. */
function groupAlive(pid: number): boolean {
  try {
    process.kill(-pid, 0);
    return true;
  } catch {
    return false;
  }
}

async function waitForPort(pid: number, budgetMs: number): Promise<boolean> {
  const deadline = Date.now() + budgetMs;
  while (Date.now() < deadline) {
    if (await answers(UI_PORT)) return true;
    if (!groupAlive(pid)) return false;
    await sleep(250);
  }
  return false;
}

/**
 * Makes something answer on 4200, and fails naming the port when nothing does.
 *
 * A preview left running by an interrupted run is stopped first, since its state file still names
 * it. A preview whose run is still going fails the setup instead: one run per stack at a time. A
 * server that answers after that is left alone and the bundle is not rebuilt for it. Otherwise
 * the bundle is rebuilt when stale and served by `vite preview`, whose process group is recorded for
 * `stopUiServer`.
 */
export async function ensureUiServer(): Promise<{ pid: number | null; built: boolean }> {
  const found = readStateFile<UiServerState>(STATE_FILE);
  if (found && found.owner !== RUNNER && processAlive(found.owner)) {
    throw new Error(
      `port ${UI_PORT} is served by the vite preview of another e2e run still in progress (runner pid ` +
        `${found.owner}). Wait for it to finish or stop it: one run per stack at a time.`,
    );
  }
  await stopUiServer();
  if (await answers(UI_PORT)) return { pid: null, built: false };
  const built = await bundleIsStale();
  if (built) {
    await buildBundle().catch((cause: unknown) => {
      throw new Error(`nothing answers on port ${UI_PORT}: the UI bundle did not build. ${String(cause)}`);
    });
  }
  const pid = startPreview();
  writeStateFile<UiServerState>(STATE_FILE, { pid, owner: RUNNER });
  if (!(await waitForPort(pid, 60_000))) {
    await stopUiServer();
    const log = fs.readFileSync(PREVIEW_LOG, "utf-8").split("\n").slice(-20).join("\n");
    throw new Error(
      `nothing answers on port ${UI_PORT} after starting vite preview, so nginx on 8080 has no UI to ` +
        `serve. Preview log:\n${log}`,
    );
  }
  return { pid, built };
}

/**
 * Stops the preview the state file records, if this run or a dead one started it, and waits for its
 * process group to exit. A preview of a run still in progress, and a server the suite did not start,
 * stay up.
 */
export async function stopUiServer(): Promise<void> {
  const state = readStateFile<UiServerState>(STATE_FILE);
  if (state && state.owner !== RUNNER && processAlive(state.owner)) return;
  fs.rmSync(STATE_FILE, { force: true });
  if (!state?.pid || !previewGroup(state.pid)) return;
  try {
    process.kill(-state.pid, "SIGTERM");
  } catch {
    return; // The group has already exited.
  }
  const deadline = Date.now() + 10_000;
  while (groupAlive(state.pid) && Date.now() < deadline) {
    await sleep(100);
  }
  if (groupAlive(state.pid)) process.kill(-state.pid, "SIGKILL");
}
