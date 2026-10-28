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
 * `--only=vscode-messages` checks the message registry alone, against a run of `--project=vscode`,
 * and writes neither the catalog nor `coverage.json`.
 *
 * Plain JavaScript importing TypeScript on purpose: Node strips the types, and the registry
 * imports nothing at runtime so it loads outside Playwright.
 */
import fs from "node:fs";
import path from "node:path";
import { COMPONENTS, elementRegistry, reconcile, reconcileArgs, reportTarget, runFilters, runsOn, summarizeElements } from "./elements.ts";
import { operationClaims, operationRegistry, summarizeOperations } from "./operations.ts";
import { messageRegistry, reconcileMessages, summarizeMessages } from "./vscode-messages.ts";
import { STACK_FILE, writeCaseCatalog } from "../support/report.ts";
import { COVERAGE_FILE } from "../support/run-summary.mjs";

const args = reconcileArgs(process.argv.slice(2));
if (args.error) {
  console.error(args.error);
  process.exit(1);
}
const only = args.only;
// Derived from the run header's own path rather than spelled again here. `playwright.config.ts`
// writes the report beside it, and three files agreeing by coincidence is how one of them goes
// stale.
const reportPath = args.report
  ? path.resolve(args.report)
  : path.join(path.dirname(STACK_FILE), "report.json");

if (!fs.existsSync(reportPath)) {
  console.error(`no report at ${reportPath} — run the suite first`);
  process.exit(1);
}

const report = JSON.parse(fs.readFileSync(reportPath, "utf-8"));

if (only === "vscode-messages") {
  const problems = reconcileMessages(report, messageRegistry, { only: true });
  if (problems.length > 0) {
    console.error(`message reconciliation failed (${problems.length}):`);
    for (const problem of problems) console.error(`  - ${problem}`);
    process.exit(1);
  }
  const { covered, handled, unhandled } = summarizeMessages(messageRegistry);
  console.log(`message reconciliation passed: ${covered} of ${handled} handled message types covered (${unhandled} unhandled)`);
  process.exit(0);
}

// The catalog reads `test-results/stack.json`, which an interrupted globalSetup leaves half
// written. That is a document to lose, not a reason to skip the reconciliation the release gates
// on, so the failure is reported and the run continues.
try {
  console.log(`case catalog written to ${writeCaseCatalog(report, COMPONENTS)}`);
} catch (cause) {
  console.error(`no case catalog: ${cause instanceof Error ? cause.message : String(cause)}`);
}

const problems = reconcile(report, elementRegistry, operationClaims());
// `reconcile()` already refuses a narrowed run, once.
if (runFilters(report).length === 0) problems.push(...reconcileMessages(report, messageRegistry));
// Each row is read against a run on its own target, and the report records which target this was.
const target = reportTarget(report);
const { covered, inScope, outOfScope, shared } = summarizeElements(
  elementRegistry.filter((entry) => runsOn(entry.target, target)),
);

// The registry's claims on this target, for the CI step summary, which says whether they held.
fs.writeFileSync(
  path.join(path.dirname(reportPath), COVERAGE_FILE),
  JSON.stringify({
    target,
    elements: { covered, inScope, outOfScope, shared },
    operations: summarizeOperations(operationRegistry.filter((entry) => runsOn(entry.target, target))),
    messages: summarizeMessages(messageRegistry),
    problems: problems.length,
  }, null, 2) + "\n",
);

if (problems.length > 0) {
  console.error(`coverage reconciliation of a ${target} run failed (${problems.length}):`);
  for (const problem of problems) console.error(`  - ${problem}`);
  process.exit(1);
}

console.log(
  `coverage reconciliation of a ${target} run passed: ${covered} of ${inScope} registry rows ` +
    `in scope on this target covered`,
);
