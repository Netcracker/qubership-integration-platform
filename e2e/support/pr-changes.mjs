/**
 * Which gated jobs of `.github/workflows/e2e-pr.yaml` a pull request needs, from the paths it
 * changes. The `changes` job pipes the diff in and appends the output to `$GITHUB_OUTPUT`:
 *
 *     git diff --name-only --no-renames HEAD^1 HEAD | node support/pr-changes.mjs
 *
 * It prints one `<job>=true` or `<job>=false` line per job in `JOB_PATHS`.
 */
import fs from "node:fs";
import { fileURLToPath } from "node:url";

/**
 * The paths, relative to the repository root, whose change runs each job. `**` matches any run of
 * characters, `/` included, and `*` any run without a `/`. The `vscode` list holds every file the
 * project's specs and setups import, which `specs/harness/pr-changes.spec.ts` checks.
 *
 * @type {Record<string, string[]>}
 */
export const JOB_PATHS = {
  vscode: [
    "vscode-extension/**",
    "ui/**",
    "schemas/**",
    "package*.json",
    "e2e/specs/vscode*/**",
    "e2e/specs/schemas-build/**",
    "e2e/specs/harness/vscode-*.spec.ts",
    "e2e/specs/harness/schemas-build.spec.ts",
    "e2e/pages/VsCodePage.ts",
    "e2e/pages/ChainGraphPage.ts",
    "e2e/pages/TableView.ts",
    "e2e/support/vscode-*",
    "e2e/support/schemas-build.ts",
    "e2e/support/colors.ts",
    "e2e/support/known-defect.ts",
    "e2e/support/pick-option.ts",
    "e2e/support/poll.ts",
    "e2e/support/process-group.ts",
    "e2e/support/report.ts",
    "e2e/support/run.ts",
    "e2e/support/run-history.mjs",
    "e2e/support/run-model.mjs",
    "e2e/support/run-report.mjs",
    "e2e/support/run-summary.mjs",
    "e2e/support/state-file.ts",
    "e2e/env/compose-containers.ts",
    "e2e/env/containers.ts",
    "e2e/env/host.ts",
    "e2e/env/index.ts",
    "e2e/env/target.ts",
    "e2e/registry/vscode-messages.ts",
    "e2e/registry/discriminators.ts",
    "e2e/registry/elements.ts",
    "e2e/registry/operations.ts",
    "e2e/registry/reconcile.mjs",
    "e2e/fixtures/vscode/**",
    "e2e/playwright.config.ts",
    "e2e/package.json",
    "e2e/package-lock.json",
    "e2e/tsconfig.json",
    ".github/workflows/e2e-pr.yaml",
  ],
};

/** @param {string} glob */
export function globRegExp(glob) {
  const source = glob
    .split("**")
    .map((part) =>
      part
        .split("*")
        .map((literal) => literal.replace(/[.+?^${}()|[\]\\]/g, "\\$&"))
        .join("[^/]*"),
    )
    .join(".*");
  return new RegExp(`^${source}$`);
}

/**
 * @param {string[]} files paths relative to the repository root
 * @returns {Record<string, boolean>}
 */
export function jobsFor(files) {
  return Object.fromEntries(
    Object.entries(JOB_PATHS).map(([job, globs]) => {
      const patterns = globs.map(globRegExp);
      return [job, files.some((file) => patterns.some((pattern) => pattern.test(file)))];
    }),
  );
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const files = fs.readFileSync(0, "utf-8").split("\n").filter(Boolean);
  for (const [job, runs] of Object.entries(jobsFor(files))) console.log(`${job}=${runs}`);
}
