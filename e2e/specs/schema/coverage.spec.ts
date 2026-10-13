/**
 * The gap detector: the coverage registry against the schemas, and against the run.
 *
 * Two directions and two readings. The schema side asks whether the registry still describes the
 * platform — a new element or a new axis value has to appear as a row before anyone can claim it
 * is covered, and a row naming a value the schemas dropped is rot. The run side asks whether
 * `covered` means anything — a row says so only while a passing test declares it.
 *
 * No stack. Both readings are pure functions over tracked files.
 */
import { test, expect } from "@playwright/test";
import {
  ELEMENT_SCHEMA_DIR,
  collectElementSchemaFiles,
  elementNameOf,
  extractAllDiscriminators,
} from "../../registry/discriminators.js";
import {
  coverageKey,
  declarationsFromReport,
  elementRegistry,
  entryKey,
  reachedFromReport,
  reconcile,
  reconcileOperations,
  runFilters,
  untaggedSpecs,
  validateRegistry,
  type JsonReport,
  type OperationClaim,
  type RegistryEntry,
} from "../../registry/elements.js";

/**
 * The mutation seam: run the whole gap detector against a directory holding one element the
 * registry has never heard of, and nothing writes into the schemas tree to arrange it.
 *
 * The path is resolved against Playwright's working directory, which is `e2e/`, so it is written
 * without one — an `e2e/` prefix here throws ENOENT before a single case runs:
 *
 *     cd e2e && E2E_ELEMENT_SCHEMA_DIR=fixtures/schema-mutation npx playwright test --project=schema
 *
 * The case at the bottom of this file does the same thing permanently, over the same fixture, so
 * the detector cannot be deleted or defanged between two people running that command by hand.
 */
const schemaDir = process.env.E2E_ELEMENT_SCHEMA_DIR ?? ELEMENT_SCHEMA_DIR;

const registryKeys = new Set(elementRegistry.map(entryKey));

test("the registry is populated and its own shape holds", { tag: ["@infra", "@tier1"] }, () => {
  // A gap detector that iterates nothing passes for the worst possible reason.
  expect(elementRegistry.length).toBeGreaterThan(400);
  expect(validateRegistry(elementRegistry)).toEqual([]);
});

test("every element the schemas declare has a registry row", { tag: ["@infra", "@tier1"] }, () => {
  const missing = collectElementSchemaFiles(schemaDir)
    .map(elementNameOf)
    .filter((element) => !registryKeys.has(coverageKey(element)));

  expect(missing, "element families with no registry row").toEqual([]);
});

test("every axis value the extractor finds has a registry row", { tag: ["@infra", "@tier1"] }, async () => {
  const axes = await extractAllDiscriminators(schemaDir);
  expect(axes.length).toBeGreaterThan(0);

  const missing: string[] = [];
  for (const found of axes) {
    for (const value of found.values) {
      const key = coverageKey(found.element, found.axisPath, value);
      if (!registryKeys.has(key)) missing.push(key);
    }
  }

  expect(missing, "axis values with no registry row").toEqual([]);
});

test("no registry row names an element or axis value the schemas no longer declare", { tag: ["@infra", "@tier1"] }, async () => {
  const declared = new Set<string>(
    collectElementSchemaFiles(schemaDir).map((file) => coverageKey(elementNameOf(file))),
  );
  for (const found of await extractAllDiscriminators(schemaDir)) {
    for (const value of found.values) {
      declared.add(coverageKey(found.element, found.axisPath, value));
    }
  }

  const stale = elementRegistry.map(entryKey).filter((key) => !declared.has(key));

  expect(stale, "registry rows the schemas no longer support").toEqual([]);
});

test("the seed carries a reason on every gap", { tag: ["@infra", "@tier1"] }, () => {
  for (const entry of elementRegistry) {
    if (entry.status !== "not-covered") continue;
    expect(entry.reason?.trim(), `${entryKey(entry)} has no reason`).toBeTruthy();
  }
});

test("a not-covered entry with no reason is rejected", { tag: ["@infra", "@tier1"] }, () => {
  const orphan: RegistryEntry = {
    family: "condition",
    kind: "element",
    tier: 1,
    status: "not-covered",
    tags: ["@engine"],
  };

  expect(validateRegistry([orphan])).toEqual([
    "condition: status is not-covered and no reason is given",
  ]);
  // A blank string is the same omission written differently.
  expect(validateRegistry([{ ...orphan, reason: "   " }])).toHaveLength(1);
  expect(validateRegistry([{ ...orphan, reason: "awaiting the axis sweep" }])).toEqual([]);
});

test("the shape check catches a malformed row rather than only a missing reason", { tag: ["@infra", "@tier1"] }, () => {
  const base: RegistryEntry = {
    family: "condition",
    kind: "element",
    tier: 1,
    status: "covered",
    tags: ["@engine"],
  };

  expect(validateRegistry([base, base])).toContain("duplicate row: condition");
  expect(
    validateRegistry([{ ...base, kind: "axis", axisPath: "target" }]),
  ).toContain("condition::target=undefined: an axis row needs both an axisPath and a value");
  expect(validateRegistry([{ ...base, axisPath: "target", value: "BODY" }])).toContain(
    'condition::target="BODY": an element row carries an axisPath or a value',
  );
  expect(
    validateRegistry([
      { ...base, kind: "axis", axisPath: "idempotency/enabled", axis: "idempotency", value: true },
    ]),
  ).toContain(
    'condition::idempotency/enabled=true: axis "idempotency" is not the last segment of the path',
  );
});

// ---------------------------------------------------------------------------
// Reconciliation
// ---------------------------------------------------------------------------

/**
 * A JSON report holding one test with the given outcome and declarations.
 *
 * Tagged, because reconciliation now refuses an untagged spec and every case below is about
 * something else. Playwright reports tags without their leading `@`.
 */
function reportOf(status: string, ...keys: string[]): JsonReport {
  return taggedReportOf(["engine", "tier1"], status, ...keys);
}

function taggedReportOf(tags: string[], status: string, ...keys: string[]): JsonReport {
  return {
    suites: [
      {
        suites: [
          {
            specs: [
              {
                title: "a spec",
                file: "specs/runtime/condition.spec.ts",
                tags,
                tests: [
                  {
                    status,
                    annotations: keys.map((key) => ({ type: "covers", description: key })),
                  },
                ],
              },
            ],
          },
        ],
      },
    ],
  };
}

const coveredRow: RegistryEntry = {
  family: "condition",
  kind: "element",
  tier: 1,
  status: "covered",
  tags: ["@engine"],
};

test("a covered row backed by a passing declaration reconciles", { tag: ["@infra", "@tier1"] }, () => {
  expect(reconcile(reportOf("expected", "condition"), [coveredRow])).toEqual([]);
});

test("a covered row with no passing test behind it fails the reconciliation", { tag: ["@infra", "@tier1"] }, () => {
  // This is the whole point of the mechanism: without it, `covered` outlives the spec that earned
  // it — deleted, renamed, skipped, or edited down to asserting a 200.
  expect(reconcile(reportOf("expected", "otherwise"), [coveredRow])).toContain(
    "condition: marked covered, but no passing test declares it",
  );
  // A failing or skipped test declares nothing.
  for (const status of ["unexpected", "skipped", "flaky"]) {
    expect(
      reconcile(reportOf(status, "condition"), [coveredRow]),
      `a ${status} test must not prove coverage`,
    ).toContain("condition: marked covered, but no passing test declares it");
  }
});

test("a declaration naming a row that does not exist fails the reconciliation", { tag: ["@infra", "@tier1"] }, () => {
  expect(reconcile(reportOf("expected", "condition", "no-such-element"), [coveredRow])).toEqual([
    "specs/runtime/condition.spec.ts › a spec declares no-such-element, which is not a registry row",
  ]);
});

test("a report with no tests in it fails rather than passing over nothing", { tag: ["@infra", "@tier1"] }, () => {
  expect(reconcile({ suites: [] }, [])).toEqual([
    "the report holds no tests: nothing was reconciled",
  ]);
});

test("declarations are read from every nesting level of the report", { tag: ["@infra", "@tier1"] }, () => {
  const found = declarationsFromReport(reportOf("expected", "condition", 'http-trigger::accessControlType="RBAC"'));
  expect(found.map((d) => d.key)).toEqual([
    "condition",
    'http-trigger::accessControlType="RBAC"',
  ]);
  expect(found.every((d) => d.passed)).toBe(true);
});

test("the key encodes the value, so false and \"false\" are different rows", { tag: ["@infra", "@tier1"] }, () => {
  expect(coverageKey("http-trigger", "receiveCorrelationId", false)).not.toBe(
    coverageKey("http-trigger", "receiveCorrelationId", "false"),
  );
  expect(coverageKey("condition")).toBe("condition");
});

test("a spec carrying no component tag fails the reconciliation", { tag: ["@infra", "@tier1"] }, () => {
  // The tag is what `--grep @engine` and the case catalog read, and nothing else does — so an
  // untagged spec is invisible to both and nothing says so. This is where it is said.
  expect(untaggedSpecs(taggedReportOf(["tier1"], "expected"))).toEqual([
    "specs/runtime/condition.spec.ts › a spec: no component tag (one of @catalog, @engine, @sessions, @testing-service, @ui, @extension, @infra)",
  ]);
  expect(untaggedSpecs(taggedReportOf(["engine"], "expected"))).toEqual([
    "specs/runtime/condition.spec.ts › a spec: no tier tag (@tier1 or @tier2)",
  ]);
  expect(untaggedSpecs(taggedReportOf(["engine", "tier2"], "expected"))).toEqual([]);
});

test("an untagged spec fails the whole reconciliation, not only the tag check", { tag: ["@infra", "@tier1"] }, () => {
  expect(reconcile(taggedReportOf([], "expected", "condition"), [coveredRow])).toEqual([
    "specs/runtime/condition.spec.ts › a spec: no component tag (one of @catalog, @engine, @sessions, @testing-service, @ui, @extension, @infra)",
    "specs/runtime/condition.spec.ts › a spec: no tier tag (@tier1 or @tier2)",
    // A spec with no tags cannot carry the row's own, so the declaration is reported too. Two
    // readings of one omission, and both are worth printing: the first says the spec is invisible
    // to `--grep` and to the case catalog, the second that the row it claims is unproven.
    "specs/runtime/condition.spec.ts › a spec declares condition, which is tagged @engine; " +
      "the spec carries no tags",
  ]);
});

test("a test.fail case proves nothing, however Playwright reports it", { tag: ["@infra", "@tier1"] }, () => {
  // A `test.fail()` case that failed is reported `expected` — the run went the way the file said it
  // would — and its body stopped at the first assertion that went red. Two such cases exist in the
  // suite, and reading `status` alone would let a `covers()` after that point earn a registry row on
  // an assertion nobody made.
  const failed = reportOf("expected", "condition");
  failed.suites![0].suites![0].specs![0].tests![0].expectedStatus = "failed";

  expect(declarationsFromReport(failed).map((d) => d.passed)).toEqual([false]);
  expect(reconcile(failed, [coveredRow])).toContain(
    "condition: marked covered, but no passing test declares it",
  );
  // The same reading feeds the operation half: a call a `test.fail()` case made proves no row.
  expect(reachedFromReport(reachedReportOf("expected", "catalog GET /v1/chains"))).toEqual(
    new Set(["catalog GET /v1/chains"]),
  );
  const reachedByFailure = reachedReportOf("expected", "catalog GET /v1/chains");
  reachedByFailure.suites![0].suites![0].specs![0].tests![0].expectedStatus = "failed";
  expect(reachedFromReport(reachedByFailure).size).toBe(0);
});

test("a row is proved by a spec carrying its component tag, not by any spec at all", { tag: ["@infra", "@tier1"] }, () => {
  // `tags` says which component has to be exercised. Without this reading the field is written and
  // never read, and an `@engine` row can be earned by a `@catalog` spec that never went near the
  // engine.
  expect(reconcile(taggedReportOf(["catalog", "tier1"], "expected", "condition"), [coveredRow]))
    .toEqual([
      "specs/runtime/condition.spec.ts › a spec declares condition, which is tagged @engine; " +
        "the spec carries @catalog, @tier1",
    ]);
  expect(reconcile(taggedReportOf(["engine", "tier1"], "expected", "condition"), [coveredRow]))
    .toEqual([]);
});

// ---------------------------------------------------------------------------
// The whole-run preconditions
// ---------------------------------------------------------------------------

/** A report of a run invoked with the given arguments, and nothing else in it. */
function invokedAs(...argv: string[]): JsonReport {
  const report = reportOf("expected", "condition");
  report.config = { argv: ["/usr/bin/node", "node_modules/.bin/playwright", ...argv] };
  return report;
}

test("a whole run is recognised as one, whatever else is on the command line", { tag: ["@infra", "@tier1"] }, () => {
  // The precondition `reconcile()` reads before anything else, and the one the report builders
  // above never exercised: with no `config` key the argument vector is empty and every run reads as
  // unfiltered, so the parser guarding it was dead code.
  expect(runFilters(invokedAs("test"))).toEqual([]);
  // Options that change how a run executes without changing which tests it selects.
  expect(runFilters(invokedAs("test", "--workers=4", "--headed", "--reporter", "list"))).toEqual([]);
  expect(runFilters(invokedAs("test", "--trace", "on", "--repeat-each", "2"))).toEqual([]);
  // `npx playwright show-report` and anything else that is not a run.
  expect(runFilters(invokedAs("show-report"))).toEqual([]);
});

test("a narrowed run is refused rather than half reconciled", { tag: ["@infra", "@tier1"] }, () => {
  // `--grep` or `--project` excludes rows the registry still claims, and reporting each of them as
  // unproven buries the one real problem under a wall of noise.
  expect(runFilters(invokedAs("test", "--project=schema"))).toEqual(["--project=schema"]);
  expect(runFilters(invokedAs("test", "--project", "schema"))).toEqual(["--project", "schema"]);
  expect(runFilters(invokedAs("test", "-g", "condition"))).toEqual(["-g", "condition"]);
  // A bare positional is a file-name filter.
  expect(runFilters(invokedAs("test", "specs/api/chains.spec.ts"))).toEqual([
    "specs/api/chains.spec.ts",
  ]);
  // A value option keeps its value out of the filter list, and the filter after it in.
  expect(runFilters(invokedAs("test", "--workers", "4", "--project", "api"))).toEqual([
    "--project",
    "api",
  ]);
  // An unrecognised option reads as narrowing: an allowlist costs a spurious refusal where a
  // denylist would cost a silent half-reconciliation the day Playwright adds a filter.
  expect(runFilters(invokedAs("test", "--only-changed"))).toEqual(["--only-changed"]);

  expect(reconcile(invokedAs("test", "--project=api"), [coveredRow])).toEqual([
    "the run was narrowed by `--project=api`: reconciliation reads a whole run or none",
  ]);
});

/** A report invoked plainly, but whose config resolved the given project names — `E2E_BROKERS=0`'s trace. */
function withProjects(...names: string[]): JsonReport {
  const report = invokedAs("test");
  report.config!.projects = names.map((name) => ({ name }));
  return report;
}

test("E2E_BROKERS=0 narrows the run even though it never reaches argv", { tag: ["@infra", "@tier1"] }, () => {
  // The env var never reaches `argv`, so only the resolved `config.projects` list can tell a run
  // that dropped the brokers-family projects apart from one that ran them. Without this,
  // `E2E_BROKERS=0` would report every broker registry row as "marked covered, but no passing test
  // declares it" instead of refusing the reconciliation.
  expect(
    runFilters(withProjects("schema", "api", "seed", "seed-teardown", "tooling", "runtime", "env", "global")),
  ).toEqual(["E2E_BROKERS=0"]);
  expect(
    reconcile(
      withProjects("schema", "api", "seed", "seed-teardown", "tooling", "runtime", "env", "global"),
      [coveredRow],
    ),
  ).toEqual(["the run was narrowed by `E2E_BROKERS=0`: reconciliation reads a whole run or none"]);

  // A report naming every brokers-family project is a whole run, not a narrowed one.
  expect(
    runFilters(
      withProjects(
        "schema", "api", "seed", "seed-teardown", "tooling", "runtime", "env",
        "brokers-seed", "brokers-seed-teardown", "brokers", "brokers-restart", "global",
      ),
    ),
  ).toEqual([]);

  // A hand-built report with no `config.projects` at all cannot tell either way, so it reads as
  // unfiltered — every test above `invokedAs` builds already relies on this.
  expect(runFilters(invokedAs("test"))).toEqual([]);
});

// ---------------------------------------------------------------------------
// The operation registry against the run
// ---------------------------------------------------------------------------

/** A report of one passing test that recorded the given operation keys. */
function reachedReportOf(status: string, ...keys: string[]): JsonReport {
  return {
    suites: [
      {
        suites: [
          {
            specs: [
              {
                title: "a spec",
                file: "specs/api/chains.spec.ts",
                tags: ["catalog", "tier1"],
                tests: [
                  {
                    status,
                    annotations: keys.map((key) => ({ type: "reached", description: key })),
                  },
                ],
              },
            ],
          },
        ],
      },
    ],
  };
}

const claims: OperationClaim[] = [
  { key: "catalog GET /v1/chains", status: "covered" },
  { key: "catalog GET /v1/folders", status: "reached" },
  { key: "catalog DELETE /v1/chains/{chainId}", status: "not-reached" },
];

test("the operation registry is not reconciled when there is nothing to reconcile it against", { tag: ["@infra", "@tier1"] }, () => {
  // The default. A caller reconciling only the element registry asks nothing of the transports, and
  // every case above this section takes that path — which is why nothing below it had ever run.
  expect(reconcileOperations(reachedReportOf("expected"), [])).toEqual([]);
});

test("a run that recorded no call at all fails once, naming the transports", { tag: ["@infra", "@tier1"] }, () => {
  // One accurate failure rather than one per `covered` row. "The transports record nothing" and
  // "every row is wrong" are indistinguishable from the rows alone, and only the first is real.
  expect(reconcileOperations(reachedReportOf("expected"), claims)).toEqual([
    "no API call was recorded: neither the transports in support/ — catalog.ts, sessions.ts, " +
      "engine.ts and testing-service.ts — nor recordingRequest around the request fixture " +
      "called noteReached, so no operation row can be checked against the run",
  ]);
});

test("a covered operation no passing test reached fails the reconciliation", { tag: ["@infra", "@tier1"] }, () => {
  expect(reconcileOperations(reachedReportOf("expected", "catalog GET /v1/folders"), claims))
    .toEqual(["catalog GET /v1/chains: marked covered, but no passing test reached it"]);
});

test("a reached operation no passing test recorded fails it as well", { tag: ["@infra", "@tier1"] }, () => {
  // The middle state, which neither direction used to read: `reached` says a spec called the
  // operation, and only the run can say whether one did. The `covered` row is reached here, so the
  // `reached` row is the only one left to report.
  expect(reconcileOperations(reachedReportOf("expected", "catalog GET /v1/chains"), claims)).toEqual([
    "catalog GET /v1/folders: marked reached, but no passing test reached it. A call records one " +
      "through a transport in support/ or through the `request` fixture on a service's own port",
  ]);
});

test("a not-reached operation the run reached fails it too", { tag: ["@infra", "@tier1"] }, () => {
  // The direction that was missing, and the one that had gone wrong: measured against one run, 18
  // rows claimed `not-reached` while that same run's annotations named them. A registry that only
  // checks the optimistic direction understates the suite and files finished work as a gap.
  expect(
    reconcileOperations(
      // The `covered` and `reached` rows are both reached, so the one problem left is this case's.
      reachedReportOf(
        "expected",
        "catalog GET /v1/chains",
        "catalog GET /v1/folders",
        "catalog DELETE /v1/chains/{chainId}",
      ),
      claims,
    ),
  ).toEqual(["catalog DELETE /v1/chains/{chainId}: marked not-reached, but a passing test reached it"]);
});

test("a call recorded against no row at all fails rather than being dropped", { tag: ["@infra", "@tier1"] }, () => {
  // `matchOperationPath` returns a key only for a row it found, so this is a registry that lost a
  // row an annotation still names — the operation-side twin of a `covers()` naming nothing.
  expect(
    reconcileOperations(
      // As above: the two rows the run can satisfy are satisfied, leaving the unknown key alone.
      reachedReportOf(
        "expected",
        "catalog GET /v1/chains",
        "catalog GET /v1/folders",
        "catalog GET /v1/nowhere",
      ),
      claims,
    ),
  ).toEqual(["catalog GET /v1/nowhere was reached, but it is not an operation row"]);
});

// ---------------------------------------------------------------------------
// The detector against a directory it has never seen
// ---------------------------------------------------------------------------

test("the gap detector names an element the registry has never heard of", { tag: ["@infra", "@tier1"] }, async () => {
  // The permanent form of the `E2E_ELEMENT_SCHEMA_DIR` command at the top of this file. The 437-row
  // registry is the largest guard in the suite, and a guard that has only ever been seen going
  // green is indistinguishable from one that cannot go red. `fixtures/schema-mutation/` holds one
  // element that is not a platform element, so nothing writes into `schemas/` to arrange this.
  const mutated = "fixtures/schema-mutation";

  const families = collectElementSchemaFiles(mutated).map(elementNameOf);
  expect(families, "the fixture directory holds the probe and nothing else").toEqual(["gap-probe"]);
  expect(families.filter((family) => !registryKeys.has(coverageKey(family)))).toEqual([
    "gap-probe",
  ]);

  // The axis half of the same detector, which reads the element through the resolver rather than
  // off the file name.
  const axes = await extractAllDiscriminators(mutated);
  const missing = axes.flatMap((found) =>
    found.values
      .map((value) => coverageKey(found.element, found.axisPath, value))
      .filter((key) => !registryKeys.has(key)),
  );
  expect(missing).toEqual(['gap-probe::probeMode="alpha"', 'gap-probe::probeMode="beta"']);
});
