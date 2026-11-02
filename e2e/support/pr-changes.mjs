/**
 * Which gated jobs of `.github/workflows/e2e-pr.yaml` a pull request needs, from the paths it
 * changes. The `changes` job pipes the diff in and appends the output to `$GITHUB_OUTPUT`:
 *
 *     git diff --name-only --no-renames HEAD^1 HEAD | node support/pr-changes.mjs
 *
 * It prints one `<job>=true` or `<job>=false` line per job in `JOB_PATHS` and `KIND_JOBS`.
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
    "e2e/support/absent.ts",
    "e2e/support/build-lock.ts",
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
    "e2e/support/zip.ts",
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
    "e2e/fixtures/templating.ts",
    "e2e/playwright.config.ts",
    "e2e/package.json",
    "e2e/package-lock.json",
    "e2e/tsconfig.json",
    ".github/workflows/e2e-pr.yaml",
  ],
};

/** The jobs that call `_e2e-kind.yaml`, each with the lane of its name in `support/lanes.mjs`. */
export const KIND_JOBS = ["classic", "micro", "ui"];

/**
 * The kind jobs a changed path runs. The first rule with a matching path decides, and the last
 * matches every path. `specs/harness/pr-changes.spec.ts` checks that a rule naming fewer jobs than
 * all three leaves out no job whose specs and setups import the file. A file a job reads without
 * importing it needs a rule of its own, as the first two show.
 *
 * @type {{ paths: string[]; jobs: string[] }[]}
 */
export const KIND_RULES = [
  // `specs/api/extension-output.spec.ts` reads the extension's goldens and its test workspace.
  { paths: ["vscode-extension/src/web/test/golden/**", "vscode-extension/src/web/test/workspace/**"], jobs: ["classic"] },
  // The UI bundle copies the help pages, and `specs/ui/doc.spec.ts` reads their headings. They are
  // Markdown, so this rule comes before the documentation rule.
  { paths: ["help/docs/**"], jobs: ["ui"] },
  {
    paths: [
      "docs/**",
      "help/**",
      "*.md",
      "**/*.md",
      "ai-service/**",
      "checkstyle/**",
      "integration-build-maven-plugin/**",
      ".agents/**",
      ".apm/**",
      ".claude/**",
      ".cursor/**",
    ],
    jobs: [],
  },
  { paths: [".github/workflows/e2e-*.yaml", ".github/workflows/_e2e-kind.yaml"], jobs: KIND_JOBS },
  { paths: [".github/workflows/**"], jobs: [] },
  { paths: ["ui/**", "package-lock.json"], jobs: ["ui"] },
  { paths: ["vscode-extension/**"], jobs: [] },
  { paths: ["micro-engine/**"], jobs: ["micro"] },
  { paths: ["e2e/specs/ui/**", "e2e/pages/**"], jobs: ["ui"] },
  { paths: ["e2e/specs/vscode*/**"], jobs: [] },
  { paths: ["e2e/specs/k8s/**"], jobs: ["micro"] },
  // Its teardown runs in `classic` too, through `suite-teardown`, the teardown of `brokers-seed`.
  { paths: ["e2e/specs/seed-micro/**"], jobs: ["classic", "micro"] },
  // The `stack-free` job runs these specs on every pull request.
  { paths: ["e2e/specs/harness/**", "e2e/specs/schema/**"], jobs: [] },
  { paths: ["**"], jobs: KIND_JOBS },
];

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
  const kindJobs = new Set(files.flatMap(kindRule));
  return {
    ...Object.fromEntries(Object.entries(JOB_PATHS).map(([job, globs]) => [job, files.some(matching(globs))])),
    ...Object.fromEntries(KIND_JOBS.map((job) => [job, kindJobs.has(job)])),
  };
}

/** @param {string[]} globs */
function matching(globs) {
  const patterns = globs.map(globRegExp);
  return (/** @type {string} */ file) => patterns.some((pattern) => pattern.test(file));
}

/**
 * The jobs of the first rule in `KIND_RULES` that matches `file`.
 *
 * @param {string} file
 */
function kindRule(file) {
  return KIND_RULES.find((rule) => matching(rule.paths)(file))?.jobs ?? [];
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const files = fs.readFileSync(0, "utf-8").split("\n").filter(Boolean);
  for (const [job, runs] of Object.entries(jobsFor(files))) console.log(`${job}=${runs}`);
}
