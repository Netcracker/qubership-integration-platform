/**
 * Reconciles the coverage registry against the run that just finished, and writes the case catalog.
 *
 * Run it after `npx playwright test` — `npm run reconcile`. It reads the JSON reporter's output,
 * collects the `covers()` declarations of the tests that passed, and diffs that set against the
 * registry. It exits non-zero on any disagreement, so a `covered` row whose spec was deleted stops
 * the release check rather than surviving it.
 *
 * The case catalog is written from the same reading, because the two need exactly the same input:
 * the JSON report and every `covers()` declaration in it. `test-results/cases.md` is the document a
 * person reads before a release; the HTML report is for whoever is debugging one case. It is
 * written even when the reconciliation fails, since a failing run is when the catalog is read.
 *
 * Plain JavaScript importing TypeScript on purpose: Node strips the types, and the registry
 * imports nothing at runtime so it loads outside Playwright.
 */
import fs from "node:fs";
import path from "node:path";
import { COMPONENT_TAGS, elementRegistry, reconcile, reportTarget, runsOn } from "./elements.ts";
import { operationClaims } from "./operations.ts";
import { STACK_FILE, writeCaseCatalog } from "../support/report.ts";

// Derived from the run header's own path rather than spelled again here. `playwright.config.ts`
// writes the report beside it, and three files agreeing by coincidence is how one of them goes
// stale.
const reportPath = process.argv[2]
  ? path.resolve(process.argv[2])
  : path.join(path.dirname(STACK_FILE), "report.json");

if (!fs.existsSync(reportPath)) {
  console.error(`no report at ${reportPath} — run the suite first`);
  process.exit(1);
}

const report = JSON.parse(fs.readFileSync(reportPath, "utf-8"));

// The catalog reads `test-results/stack.json`, which an interrupted globalSetup leaves half
// written. That is a document to lose, not a reason to skip the reconciliation the release gates
// on, so the failure is reported and the run continues.
try {
  console.log(`case catalog written to ${writeCaseCatalog(report, COMPONENT_TAGS)}`);
} catch (cause) {
  console.error(`no case catalog: ${cause instanceof Error ? cause.message : String(cause)}`);
}

const problems = reconcile(report, elementRegistry, operationClaims());
// Each row is read against a run on its own target, and the report records which target this was.
const target = reportTarget(report);

if (problems.length > 0) {
  console.error(`coverage reconciliation of a ${target} run failed (${problems.length}):`);
  for (const problem of problems) console.error(`  - ${problem}`);
  process.exit(1);
}

const rows = elementRegistry.filter((entry) => runsOn(entry.target, target));
const covered = rows.filter((entry) => entry.status === "covered").length;
console.log(
  `coverage reconciliation of a ${target} run passed: ${covered} of ${rows.length} registry rows ` +
    `on this target covered`,
);
