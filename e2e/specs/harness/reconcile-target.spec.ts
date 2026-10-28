/**
 * Reconciliation per target: a registry row that names a target is read only against a run on it,
 * and a run reconciles only when nothing narrowed it.
 *
 * The `k8s` and `seed-micro` projects exist only on Kubernetes, so a Compose run leaves rows
 * unproven that a cluster run proves. The report records its target in `config.metadata`, and
 * these cases build one report per target. The Compose-only element row below is made up: no
 * element row names a target since the brokers run on both.
 */
import { test, expect } from "@playwright/test";
import {
  reconcile,
  reconcileOperations,
  runFilters,
  runsOn,
  summarizeElements,
  type JsonReport,
  type OperationClaim,
  type RegistryEntry,
} from "../../registry/elements.js";
import { BROKER_PROJECTS, K8S_PROJECTS, MICRO_BROKER_PROJECTS } from "./projects.js";

const COMMON_PROJECTS = ["schema", "harness", "api", "seed", "seed-teardown", "tooling", "runtime", "env", "global", "suite-teardown"];

const bothRow: RegistryEntry = { family: "condition", kind: "element", status: "covered", tags: ["@engine"] };
const composeRow: RegistryEntry = {
  family: "compose-only-element",
  kind: "element",
  status: "covered",
  tags: ["@engine"],
  target: "compose",
};

const operations: OperationClaim[] = [
  { key: "catalog GET /v1/chains", status: "covered" },
  { key: "catalog POST /v1/cr", status: "covered", target: "k8s" },
];

/** A whole run on `target`: one passing test making the given declarations and calls. */
function runOn(
  target: "compose" | "k8s" | undefined,
  covers: string[],
  reached: string[],
  projects = [
    ...COMMON_PROJECTS,
    ...(target === "k8s" ? K8S_PROJECTS : []),
    ...BROKER_PROJECTS,
    ...(target === "k8s" ? MICRO_BROKER_PROJECTS : []),
  ],
): JsonReport {
  return {
    config: {
      argv: ["/usr/bin/node", "node_modules/.bin/playwright", "test"],
      projects: projects.map((name) => ({ name })),
      ...(target ? { metadata: { target } } : {}),
    },
    suites: [
      {
        specs: [
          {
            title: "a spec",
            file: "specs/runtime/condition.spec.ts",
            tags: ["engine"],
            tests: [
              {
                status: "expected",
                annotations: [
                  ...covers.map((key) => ({ type: "covers", description: key })),
                  ...reached.map((key) => ({ type: "reached", description: key })),
                ],
              },
            ],
          },
        ],
      },
    ],
  };
}

/** A report of a run invoked with the given arguments, with no resolved project list. */
function invokedAs(...argv: string[]): JsonReport {
  const report = runOn("compose", ["condition"], []);
  report.config = { argv: ["/usr/bin/node", "node_modules/.bin/playwright", ...argv] };
  return report;
}

test.describe("reconcile per target", () => {
  test("a Compose-only row fails only a Compose run without it", () => {
    const k8sRun = runOn("k8s", ["condition"], ["catalog GET /v1/chains", "catalog POST /v1/cr"]);
    expect(reconcile(k8sRun, [bothRow, composeRow], operations)).toEqual([]);

    const composeRun = runOn("compose", ["condition"], ["catalog GET /v1/chains"]);
    expect(reconcile(composeRun, [bothRow, composeRow], operations)).toEqual([
      "compose-only-element: marked covered, but no passing test declares it",
    ]);
    expect(reconcile(runOn("compose", ["condition", "compose-only-element"], ["catalog GET /v1/chains"]), [bothRow, composeRow], operations)).toEqual([]);
  });

  test("a k8s-only operation fails only a k8s run without it", () => {
    expect(reconcileOperations(runOn("compose", [], ["catalog GET /v1/chains"]), operations)).toEqual([]);
    expect(reconcileOperations(runOn("k8s", [], ["catalog GET /v1/chains"]), operations)).toEqual([
      "catalog POST /v1/cr: marked covered, but no passing test reached it",
    ]);
  });

  test("a row proven on the other target is reported, not counted", () => {
    const k8sRun = runOn("k8s", ["condition", "compose-only-element"], ["catalog GET /v1/chains", "catalog POST /v1/cr"]);
    expect(reconcile(k8sRun, [bothRow, composeRow], operations)).toEqual([
      "specs/runtime/condition.spec.ts › a spec declares compose-only-element, a row of the compose target, in a run on k8s",
    ]);

    const composeRun = runOn("compose", ["condition", "compose-only-element"], ["catalog GET /v1/chains", "catalog POST /v1/cr"]);
    expect(reconcileOperations(composeRun, operations)).toEqual([
      "catalog POST /v1/cr was reached on compose, but its row names the k8s target",
    ]);
  });

  test("behavior and scoped rows are counted per target", () => {
    const behaviorRow: RegistryEntry = { family: "condition", kind: "behavior", behavior: "nested", status: "covered", tags: ["@engine"] };
    const k8sBehavior: RegistryEntry = { ...behaviorRow, behavior: "nested-micro", target: "k8s" };
    const outOfScope: RegistryEntry = {
      family: "condition",
      kind: "axis",
      axisPath: "mode",
      value: "a",
      status: "not-covered",
      reason: "no stack offers it",
      scope: "out-of-scope",
      tags: ["@engine"],
    };
    const shared: RegistryEntry = { ...outOfScope, value: "b", scope: "shared", reason: "proven on another family", target: "k8s" };
    const rows = [bothRow, behaviorRow, k8sBehavior, outOfScope, shared];
    const k8sRun = (...labels: string[]) =>
      runOn("k8s", ["condition", ...labels.map((label) => `condition::behavior="${label}"`)], ["catalog GET /v1/chains", "catalog POST /v1/cr"]);

    expect(reconcile(k8sRun("nested", "nested-micro"), rows, operations)).toEqual([]);
    expect(reconcile(k8sRun("nested"), rows, operations)).toEqual([
      'condition::behavior="nested-micro": marked covered, but no passing test declares it',
    ]);
    expect(reconcile(runOn("compose", ["condition", 'condition::behavior="nested"'], ["catalog GET /v1/chains"]), rows, operations)).toEqual([]);

    const counted = (target: "compose" | "k8s") => summarizeElements(rows.filter((entry) => runsOn(entry.target, target)));
    expect(counted("k8s")).toEqual({ covered: 3, inScope: 3, outOfScope: 1, shared: 1 });
    expect(counted("compose")).toEqual({ covered: 2, inScope: 2, outOfScope: 1, shared: 0 });
  });

  test.describe("run filters", () => {
    test("an execution option leaves the run whole", () => {
      // A report with no project list cannot show a dropped project, so it reads as whole.
      expect(runFilters(invokedAs("test"))).toEqual([]);
      // Options that change how a run executes without changing which tests it selects.
      expect(runFilters(invokedAs("test", "--workers=4", "--headed", "--reporter", "list"))).toEqual([]);
      expect(runFilters(invokedAs("test", "--trace", "on", "--repeat-each", "2"))).toEqual([]);
      // `npx playwright show-report` and anything else that is not a run.
      expect(runFilters(invokedAs("show-report"))).toEqual([]);
    });

    // `--grep` or `--project` leaves out rows the registry still claims, and reporting each of them
    // as unproven buries the one real problem.
    test("a command-line filter narrows the run, which is refused", () => {
      expect(runFilters(invokedAs("test", "--project=schema"))).toEqual(["--project=schema"]);
      expect(runFilters(invokedAs("test", "--project", "schema"))).toEqual(["--project", "schema"]);
      expect(runFilters(invokedAs("test", "-g", "condition"))).toEqual(["-g", "condition"]);
      // A bare positional is a file-name filter.
      expect(runFilters(invokedAs("test", "specs/api/chains.spec.ts"))).toEqual(["specs/api/chains.spec.ts"]);
      // A value option keeps its value out of the filter list, and the filter after it in.
      expect(runFilters(invokedAs("test", "--workers", "4", "--project", "api"))).toEqual(["--project", "api"]);
      // An unrecognised option reads as narrowing: an allowlist costs a spurious refusal where a
      // denylist would cost a silent half-reconciliation the day Playwright adds a filter.
      expect(runFilters(invokedAs("test", "--only-changed"))).toEqual(["--only-changed"]);

      expect(reconcile(invokedAs("test", "--project=api"), [bothRow])).toEqual([
        "the run was narrowed by `--project=api`: reconciliation reads a whole run or none",
      ]);
    });

    // `E2E_BROKERS=0` never reaches argv, so only the resolved `config.projects` list shows it.
    test("missing broker projects narrow the run on either target", () => {
      expect(runFilters(runOn("k8s", [], []))).toEqual([]);
      expect(runFilters(runOn("compose", [], []))).toEqual([]);
      expect(runFilters(runOn("k8s", [], [], [...COMMON_PROJECTS, ...K8S_PROJECTS]))).toEqual(["E2E_BROKERS=0"]);
      expect(runFilters(runOn("compose", [], [], COMMON_PROJECTS))).toEqual(["E2E_BROKERS=0"]);
      expect(reconcile(runOn("compose", ["condition"], [], COMMON_PROJECTS), [bothRow])).toEqual([
        "the run was narrowed by `E2E_BROKERS=0`: reconciliation reads a whole run or none",
      ]);
    });
  });
});
