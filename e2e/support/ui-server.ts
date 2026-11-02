/**
 * The production bundle on port 4200, where nginx sends every request outside `/api/`.
 *
 * The `ui-server` setup project calls `ensureUiServer`, and its teardown project calls
 * `stopUiServer`. A server already answering on the port is used as found and never stopped, unless
 * the state file records it as a preview this suite started: it may be a developer's dev server,
 * which serves a different artifact than the bundle.
 */
import { spawn } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { newestMtimeMs, repoRoot, runToCompletion } from "../env/host.js";
import { groupAlive, RecordedServer } from "./process-group.js";
import { readUntil } from "./poll.js";

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
export const BUNDLE_INPUTS = [
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

const server = new RecordedServer(STATE_FILE, new RegExp(`\\bpreview\\b.*--port ${UI_PORT}\\b`));

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
 * `VITE_PRODUCTION_MODE=false` shows the testing screens. `VITE_AI_SERVICE_ENABLED=false` turns off
 * the AI health check the UI sends to `/q/health` on every page. Neither target runs the AI service:
 * the Compose proxy answers the check with 503, and in CI run 39423237286 the Kubernetes proxy timed
 * out with 504. The browser logs either as a console error, and the page guard fails the case.
 */
const BUILD_ENV: Record<string, string> = {
  VITE_PRODUCTION_MODE: "false",
  VITE_AI_SERVICE_ENABLED: "false",
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

/**
 * The bundle build, `fetch-docs` included. `--ignore-scripts` skips the UI's `prebuild`, which would
 * build the schemas a second time after the `schemas-build` project.
 */
const UI_BUILD_ARGS = ["run", "build", "--ignore-scripts", "-w", "@netcracker/qip-ui"];

/** Builds the bundle with `BUILD_ENV`, which Vite reads at build time, and marks it as the suite's. */
async function buildBundle(): Promise<void> {
  await runToCompletion("npm", UI_BUILD_ARGS, repoRoot(), {
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

async function waitForPort(pid: number, budgetMs: number): Promise<boolean> {
  const last = await readUntil(
    async () => ({ answered: await answers(UI_PORT), alive: groupAlive(pid) }),
    ({ answered, alive }) => answered || !alive,
    budgetMs,
    250,
  );
  return last.answered;
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
  const owner = server.otherLiveOwner();
  if (owner !== null) {
    throw new Error(
      `port ${UI_PORT} is served by the vite preview of another e2e run still in progress (runner pid ` +
        `${owner}). Wait for it to finish or stop it: one run per stack at a time.`,
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
  server.record(pid);
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
  await server.stop();
}
