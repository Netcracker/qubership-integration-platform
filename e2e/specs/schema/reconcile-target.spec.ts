/**
 * Reconciliation per target: a registry row that names a target is read only against a run on it.
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
  reportTarget,
  runFilters,
  runsOn,
  summarizeElements,
  type JsonReport,
  type OperationClaim,
  type RegistryEntry,
} from "../../registry/elements.js";
import { BROKER_PROJECTS, K8S_PROJECTS, MICRO_BROKER_PROJECTS } from "./projects.js";

const COMMON_PROJECTS = ["schema", "api", "seed", "seed-teardown", "tooling", "runtime", "env", "global", "suite-teardown"];

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

test("a report names its target, and a report with none is a Compose run", { tag: ["@infra", "@tier1"] }, () => {
  expect(reportTarget(runOn("k8s", [], []))).toBe("k8s");
  expect(reportTarget(runOn("compose", [], []))).toBe("compose");
  expect(reportTarget(runOn(undefined, [], []))).toBe("compose");
});

test("the broker projects missing from a run narrow it on either target", { tag: ["@infra", "@tier1"] }, () => {
  expect(runFilters(runOn("k8s", [], []))).toEqual([]);
  expect(runFilters(runOn("compose", [], []))).toEqual([]);
  expect(runFilters(runOn("k8s", [], [], [...COMMON_PROJECTS, ...K8S_PROJECTS]))).toEqual(["E2E_BROKERS=0"]);
  expect(runFilters(runOn("compose", [], [], COMMON_PROJECTS))).toEqual(["E2E_BROKERS=0"]);
});

test("a Compose-only row does not fail a k8s run, and fails a Compose run that does not prove it", { tag: ["@infra", "@tier1"] }, () => {
  const k8sRun = runOn("k8s", ["condition"], ["catalog GET /v1/chains", "catalog POST /v1/cr"]);
  expect(reconcile(k8sRun, [bothRow, composeRow], operations)).toEqual([]);

  const composeRun = runOn("compose", ["condition"], ["catalog GET /v1/chains"]);
  expect(reconcile(composeRun, [bothRow, composeRow], operations)).toEqual([
    "compose-only-element: marked covered, but no passing test declares it",
  ]);
  expect(reconcile(runOn("compose", ["condition", "compose-only-element"], ["catalog GET /v1/chains"]), [bothRow, composeRow], operations)).toEqual([]);
});

test("a k8s-only row does not fail a Compose run, and fails a k8s run that does not reach it", { tag: ["@infra", "@tier1"] }, () => {
  expect(reconcileOperations(runOn("compose", [], ["catalog GET /v1/chains"]), operations)).toEqual([]);
  expect(reconcileOperations(runOn("k8s", [], ["catalog GET /v1/chains"]), operations)).toEqual([
    "catalog POST /v1/cr: marked covered, but no passing test reached it",
  ]);
});

test("a row proven on the other target's run is reported, not taken as proof", { tag: ["@infra", "@tier1"] }, () => {
  const k8sRun = runOn("k8s", ["condition", "compose-only-element"], ["catalog GET /v1/chains", "catalog POST /v1/cr"]);
  expect(reconcile(k8sRun, [bothRow, composeRow], operations)).toEqual([
    "specs/runtime/condition.spec.ts › a spec declares compose-only-element, a row of the compose target, in a run on k8s",
  ]);

  const composeRun = runOn("compose", ["condition", "compose-only-element"], ["catalog GET /v1/chains", "catalog POST /v1/cr"]);
  expect(reconcileOperations(composeRun, operations)).toEqual([
    "catalog POST /v1/cr was reached on compose, but its row names the k8s target",
  ]);
});

test("behaviour and scoped rows reconcile, and each target counts its own", { tag: ["@infra", "@tier1"] }, () => {
  const behaviourRow: RegistryEntry = { family: "condition", kind: "behaviour", behaviour: "nested", status: "covered", tags: ["@engine"] };
  const k8sBehaviour: RegistryEntry = { ...behaviourRow, behaviour: "nested-micro", target: "k8s" };
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
  const rows = [bothRow, behaviourRow, k8sBehaviour, outOfScope, shared];
  const k8sRun = (...labels: string[]) =>
    runOn("k8s", ["condition", ...labels.map((label) => `condition::behaviour="${label}"`)], ["catalog GET /v1/chains", "catalog POST /v1/cr"]);

  expect(reconcile(k8sRun("nested", "nested-micro"), rows, operations)).toEqual([]);
  expect(reconcile(k8sRun("nested"), rows, operations)).toEqual([
    'condition::behaviour="nested-micro": marked covered, but no passing test declares it',
  ]);
  expect(reconcile(runOn("compose", ["condition", 'condition::behaviour="nested"'], ["catalog GET /v1/chains"]), rows, operations)).toEqual([]);

  const counted = (target: "compose" | "k8s") => summarizeElements(rows.filter((entry) => runsOn(entry.target, target)));
  expect(counted("k8s")).toEqual({ covered: 3, inScope: 3, outOfScope: 1, shared: 1 });
  expect(counted("compose")).toEqual({ covered: 2, inScope: 2, outOfScope: 1, shared: 0 });
});
