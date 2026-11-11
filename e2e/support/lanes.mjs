/**
 * The lanes of the kind jobs in `.github/workflows/e2e-pr.yaml`: the projects each pull request job
 * selects. A project in no lane runs only nightly. `playwright.config.ts` and `env/target-setup.ts`
 * copy each project's lanes into its `metadata.lanes`; the setups a lane needs come in through
 * `dependencies`.
 *
 * Run as a script, it prints the `--project=` flags of one lane, and nothing for an empty or missing
 * lane, which selects the whole suite:
 *
 *     npx playwright test $(node support/lanes.mjs classic)
 */
import { fileURLToPath } from "node:url";

/** @type {Record<string, string[]>} */
export const PROJECT_LANES = {
  api: ["classic"],
  runtime: ["classic"],
  brokers: ["classic"],
  k8s: ["micro"],
  "runtime-micro": ["micro"],
  "runtime-micro-redeploy": ["micro"],
  "brokers-micro": ["micro"],
  ui: ["ui"],
};

/** Every lane some project is in. */
export function lanes() {
  return [...new Set(Object.values(PROJECT_LANES).flat())];
}

/**
 * The `--project=` flags of `lane`, none for an empty lane, and an error naming an unknown one.
 *
 * @param {string} lane
 * @returns {string[]}
 */
export function laneFlags(lane) {
  if (!lane) return [];
  if (!lanes().includes(lane)) {
    throw new Error(`Unknown lane "${lane}". The lanes are ${lanes().join(", ")}.`);
  }
  return Object.entries(PROJECT_LANES)
    .filter(([, each]) => each.includes(lane))
    .map(([project]) => `--project=${project}`);
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  try {
    const flags = laneFlags(process.argv[2] ?? "");
    if (flags.length) console.log(flags.join(" "));
  } catch (error) {
    console.error(`lanes: ${/** @type {Error} */ (error).message}`);
    process.exit(1);
  }
}
