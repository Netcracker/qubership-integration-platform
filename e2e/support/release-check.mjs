/**
 * Runs the Playwright suite, then the VS Code extension's integration tests, and exits non-zero if
 * either failed. The extension leg runs even after a red Playwright run, so one invocation reports
 * both.
 *
 *     npm run release-check
 *     npm run release-check -- --project=schema   # arguments go to `playwright test` only
 */
import { spawnSync } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";

const npm = process.env.npm_execpath;
if (!npm) {
  console.error("release-check: npm_execpath is not set. Run it as `npm run release-check`.");
  process.exit(2);
}

const e2eDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const repoRoot = process.env.QIP_REPO_ROOT ?? path.resolve(e2eDir, "..");

/**
 * @param {string} label
 * @param {string} cwd
 * @param {string[]} args
 * @returns {number}
 */
const run = (label, cwd, args) => {
  console.log(`\n[release-check] ${label}: npm ${args.join(" ")} (in ${cwd})\n`);
  // A leg killed by a signal has no status, and counts as failed.
  return spawnSync(process.execPath, [npm, ...args], { cwd, stdio: "inherit" }).status ?? 1;
};

const playwright = run("playwright", e2eDir, ["test", "--", ...process.argv.slice(2)]);
const extension = run("extension", repoRoot, [
  "run",
  "test:integration",
  "-w",
  "@netcracker/qip-vscode-extension",
]);

console.log(`\n[release-check] playwright exit ${playwright}, extension exit ${extension}`);
process.exit(playwright !== 0 ? playwright : extension);
