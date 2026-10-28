/**
 * Which gated jobs of `.github/workflows/e2e-pr.yaml` a pull request needs, from the paths it
 * changes. The `changes` job pipes the diff in and appends the output to `$GITHUB_OUTPUT`:
 *
 *     git diff --name-only HEAD^1 HEAD | node support/pr-changes.mjs
 *
 * It prints one `<job>=true` or `<job>=false` line per job in `JOB_PATHS`.
 */
import fs from "node:fs";
import { fileURLToPath } from "node:url";

/**
 * The paths, relative to the repository root, whose change runs each job. `**` matches any run of
 * characters, `/` included, and `*` any run without a `/`.
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
    "e2e/support/vscode-*",
    "e2e/support/schemas-build.ts",
    "e2e/registry/vscode-messages.ts",
    "e2e/fixtures/vscode/**",
    "e2e/playwright.config.ts",
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
