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
  staleRows,
  summarizeElements,
  untaggedSpecs,
  validateRegistry,
  type JsonReport,
  type OperationClaim,
  type RegistryEntry,
} from "../../registry/elements.js";
import { coversBehaviour } from "../../registry/covers.js";

/**
 * The mutation seam: run the whole gap detector against a directory holding one element the
 * registry has never heard of, and nothing writes into the schemas tree to arrange it.
 *
 * The path is resolved against Playwright's working directory, which is `e2e/`, so it is written
 * without one — an `e2e/` prefix here throws ENOENT before a single case runs:
 *
 *     cd e2e && E2E_ELEMENT_SCHEMA_DIR=fixtures/schema-mutation npx playwright test --project=harness
 *
 * The last case under "against the schemas" does the same thing permanently, over the same fixture, so
 * the detector cannot be deleted or defanged between two people running that command by hand.
 */
const schemaDir = process.env.E2E_ELEMENT_SCHEMA_DIR ?? ELEMENT_SCHEMA_DIR;

const registryKeys = new Set(elementRegistry.map(entryKey));

test.describe("coverage registry", { tag: ["@harness"] }, () => {
  /**
   * A JSON report holding one test with the given outcome and declarations.
   *
   * Tagged, because reconciliation now refuses an untagged spec and every case below is about
   * something else. Playwright reports tags without their leading `@`.
   */
  function reportOf(status: string, ...keys: string[]): JsonReport {
    return taggedReportOf(["engine"], status, ...keys);
  }

  function taggedReportOf(tags: string[], status: string, ...keys: string[]): JsonReport {
    return {
      suites: [
        {
          title: "specs/runtime/condition.spec.ts",
          suites: [
            {
              title: "condition",
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
                  tags: ["catalog"],
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

  const coveredRow: RegistryEntry = {
    family: "condition",
    kind: "element",
    status: "covered",
    tags: ["@engine"],
  };

  const behaviourRow: RegistryEntry = {
    family: "service-call",
    kind: "behaviour",
    behaviour: "retry",
    status: "not-covered",
    reason: "no case yet",
    tags: ["@engine"],
  };

  const elementOf = (entry: RegistryEntry): RegistryEntry => ({ ...entry, kind: "element", behaviour: undefined });

  test.describe("against the schemas", () => {
    test("every element the schemas declare has a row", () => {
      const missing = collectElementSchemaFiles(schemaDir)
        .map(elementNameOf)
        .filter((element) => !registryKeys.has(coverageKey(element)));

      expect(missing, "element families with no registry row").toEqual([]);
    });

    test("every axis value the extractor finds has a row", async () => {
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

    test("no row names a value the schemas no longer declare", async () => {
      const declared = new Set<string>(
        collectElementSchemaFiles(schemaDir).map((file) => coverageKey(elementNameOf(file))),
      );
      for (const found of await extractAllDiscriminators(schemaDir)) {
        for (const value of found.values) {
          declared.add(coverageKey(found.element, found.axisPath, value));
        }
      }

      const stale = staleRows(elementRegistry, declared);

      expect(stale, "registry rows the schemas no longer support").toEqual([]);
    });

    test("names an element the registry has no row for", async () => {
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
  });

  test.describe("row shape", () => {
    test("every row passes the shape check", () => {
      expect(validateRegistry(elementRegistry)).toEqual([]);
    });

    test("every not-covered row carries a reason", () => {
      for (const entry of elementRegistry) {
        if (entry.status !== "not-covered") continue;
        expect(entry.reason?.trim(), `${entryKey(entry)} has no reason`).toBeTruthy();
      }
    });

    test("rejects a not-covered row with no reason", () => {
      const orphan: RegistryEntry = {
        family: "condition",
        kind: "element",
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

    test("rejects a duplicate or malformed row", () => {
      const base: RegistryEntry = {
        family: "condition",
        kind: "element",
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
  });

  test.describe("behaviour and scope rows", () => {
    test("a behaviour row is stale only when its family is", () => {
      const declared = new Set([coverageKey("service-call"), coverageKey("service-call", "retryCount", 2)]);
      expect(staleRows([behaviourRow], declared)).toEqual([]);
      expect(staleRows([{ ...behaviourRow, family: "gone-element" }], declared)).toEqual([
        'gone-element::behaviour="retry"',
      ]);
      // Axis and element rows stay keyed on what the schemas declare.
      expect(staleRows([elementOf(behaviourRow)], declared)).toEqual([]);
      expect(staleRows([{ ...elementOf(behaviourRow), kind: "axis", axisPath: "retryCount", value: 3 }], declared)).toEqual([
        "service-call::retryCount=3",
      ]);
    });

    test("coversBehaviour declares the key of its row", () => {
      const annotations = test.info().annotations;
      coversBehaviour("service-call", "retry");
      // Taken off again, so this case declares no row in a reconciled run.
      expect(annotations.pop()).toEqual({ type: "covers", description: entryKey(behaviourRow) });
      expect(entryKey(behaviourRow)).not.toBe(entryKey(elementOf(behaviourRow)));
    });

    test("a behaviour row with an axis or no label is rejected", () => {
      expect(validateRegistry([behaviourRow])).toEqual([]);
      expect(validateRegistry([{ ...behaviourRow, axisPath: "retryCount" }])).toEqual([
        'service-call::behaviour="retry": a behaviour row carries an axisPath or a value',
      ]);
      expect(validateRegistry([{ ...behaviourRow, value: 2 }])).toEqual([
        'service-call::behaviour="retry": a behaviour row carries an axisPath or a value',
      ]);
      expect(validateRegistry([{ ...behaviourRow, behaviour: " " }])).toEqual([
        'service-call::behaviour=" ": a behaviour row needs a behaviour label',
      ]);
    });

    test("a scope needs a not-covered axis or element row", () => {
      const scoped: RegistryEntry = { ...elementOf(behaviourRow), scope: "out-of-scope" };
      expect(validateRegistry([scoped])).toEqual([]);
      expect(validateRegistry([{ ...scoped, reason: undefined }])).toEqual([
        "service-call: status is not-covered and no reason is given",
      ]);
      expect(validateRegistry([{ ...scoped, status: "covered", reason: undefined }])).toEqual([
        "service-call: a covered row carries a scope",
      ]);
      expect(validateRegistry([{ ...behaviourRow, scope: "shared" }])).toEqual([
        'service-call::behaviour="retry": a behaviour row carries a scope',
      ]);
    });

    test("scoped rows leave the denominator and are counted apart", () => {
      const row = (family: string, extra: Partial<RegistryEntry> = {}): RegistryEntry => ({
        family,
        kind: "element",
        status: "not-covered",
        reason: "a reason",
        tags: ["@engine"],
        ...extra,
      });
      const rows = [
        row("a", { status: "covered", reason: undefined }),
        row("b"),
        row("c", { scope: "out-of-scope" }),
        row("d", { scope: "out-of-scope" }),
        row("e", { scope: "shared" }),
        { ...behaviourRow, status: "covered" as const, reason: undefined },
      ];
      expect(summarizeElements(rows)).toEqual({ covered: 2, inScope: 3, outOfScope: 2, shared: 1 });
    });
  });

  test.describe("reconcile", () => {
    test("a covered row backed by a passing declaration reconciles", () => {
      expect(reconcile(reportOf("expected", "condition"), [coveredRow])).toEqual([]);
    });

    test("a covered row with no passing test behind it fails", () => {
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

    test("a declaration naming no row fails", () => {
      expect(reconcile(reportOf("expected", "condition", "no-such-element"), [coveredRow])).toEqual([
        "specs/runtime/condition.spec.ts › condition › a spec declares no-such-element, which is not a registry row",
      ]);
    });

    test("a report with no tests fails", () => {
      expect(reconcile({ suites: [] }, [])).toEqual([
        "the report holds no tests: nothing was reconciled",
      ]);
    });

    test("reads declarations at every nesting level", () => {
      const found = declarationsFromReport(reportOf("expected", "condition", 'http-trigger::accessControlType="RBAC"'));
      expect(found.map((d) => d.key)).toEqual([
        "condition",
        'http-trigger::accessControlType="RBAC"',
      ]);
      expect(found.every((d) => d.passed)).toBe(true);
    });

    test("messages name the describe path of a case", () => {
      const spec = (title: string) => ({ title, file: "runtime/split.spec.ts", tags: [], tests: [{ status: "expected", annotations: [{ type: "covers", description: "split" }] }] });
      const report: JsonReport = {
        suites: [{ title: "runtime/split.spec.ts", specs: [spec("flat")], suites: [{ title: "split", suites: [{ title: "branches", specs: [spec("nested")] }] }] }],
      };

      // The file suite's title stays out of the path, so the file is not named twice.
      expect(untaggedSpecs(report).map((each) => each.split(":")[0])).toEqual([
        "runtime/split.spec.ts › flat",
        "runtime/split.spec.ts › split › branches › nested",
      ]);
      expect(declarationsFromReport(report).map((each) => each.test)).toEqual([
        "runtime/split.spec.ts › flat",
        "runtime/split.spec.ts › split › branches › nested",
      ]);
    });

    test("a spec with no component tag fails", () => {
      // The tag is what `--grep @engine` and the case catalog read, and nothing else does — so an
      // untagged spec is invisible to both and nothing says so. This is where it is said.
      expect(untaggedSpecs(taggedReportOf(["slow"], "expected"))).toEqual([
        "specs/runtime/condition.spec.ts › condition › a spec: no component tag (one of @catalog, @engine, @sessions, @testing-service, @ui, @infra, @schema, @harness)",
      ]);
      expect(untaggedSpecs(taggedReportOf(["engine"], "expected"))).toEqual([]);
      expect(untaggedSpecs(taggedReportOf(["harness"], "expected"))).toEqual([]);
    });

    test("an untagged spec also fails the row it declares", () => {
      expect(reconcile(taggedReportOf([], "expected", "condition"), [coveredRow])).toEqual([
        "specs/runtime/condition.spec.ts › condition › a spec: no component tag (one of @catalog, @engine, @sessions, @testing-service, @ui, @infra, @schema, @harness)",
        // A spec with no tags cannot carry the row's own, so the declaration is reported too. Two
        // readings of one omission, and both are worth printing: the first says the spec is invisible
        // to `--grep` and to the case catalog, the second that the row it claims is unproven.
        "specs/runtime/condition.spec.ts › condition › a spec declares condition, which is tagged @engine; " +
          "the spec carries no tags",
      ]);
    });

    test("a test.fail case proves nothing, whatever its status", () => {
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

    test("a struck case reaches its calls and proves no row", () => {
      // `strikesAsKnown` writes the annotation beside the `fail` one `test.fail()` adds. The case stopped
      // at the step the defect names, so a race the pin carries does not turn the reconcile step red.
      const struck = reachedReportOf("expected", "catalog GET /v1/chains");
      const struckTest = struck.suites![0].suites![0].specs![0].tests![0];
      struckTest.expectedStatus = "failed";
      struckTest.annotations!.push({ type: "struck", description: "a race" }, { type: "fail", description: "a race" });
      expect(reachedFromReport(struck)).toEqual(new Set(["catalog GET /v1/chains"]));

      // A struck case that then failed for real is no evidence.
      struckTest.status = "unexpected";
      expect(reachedFromReport(struck).size).toBe(0);

      const declared = reportOf("expected", "condition");
      const declaring = declared.suites![0].suites![0].specs![0].tests![0];
      declaring.expectedStatus = "failed";
      declaring.annotations = [...(declaring.annotations ?? []), { type: "struck", description: "a race" }];
      expect(declarationsFromReport(declared).map((d) => d.passed)).toEqual([false]);
    });

    test("a row is proved only by a spec with its component tag", () => {
      // `tags` says which component has to be exercised. Without this reading the field is written and
      // never read, and an `@engine` row can be earned by a `@catalog` spec that never went near the
      // engine.
      expect(reconcile(taggedReportOf(["catalog"], "expected", "condition"), [coveredRow]))
        .toEqual([
          "specs/runtime/condition.spec.ts › condition › a spec declares condition, which is tagged @engine; " +
            "the spec carries @catalog",
        ]);
      expect(reconcile(taggedReportOf(["engine"], "expected", "condition"), [coveredRow]))
        .toEqual([]);
    });
  });

  test.describe("operations against the run", () => {
    const claims: OperationClaim[] = [
      { key: "catalog GET /v1/chains", status: "covered" },
      { key: "catalog GET /v1/folders", status: "reached" },
      { key: "catalog DELETE /v1/chains/{chainId}", status: "not-reached" },
    ];

    test("no operation rows leave nothing to reconcile", () => {
      // The default: a caller reconciling only the element registry asks nothing of the transports.
      expect(reconcileOperations(reachedReportOf("expected"), [])).toEqual([]);
    });

    test("a run that recorded no call fails once, naming transports", () => {
      // One accurate failure rather than one per `covered` row. "The transports record nothing" and
      // "every row is wrong" are indistinguishable from the rows alone, and only the first is real.
      expect(reconcileOperations(reachedReportOf("expected"), claims)).toEqual([
        "no API call was recorded: neither the transports in support/ — catalog.ts, sessions.ts, " +
          "engine.ts and testing-service.ts — nor recordingRequest around the request fixture " +
          "called noteReached, so no operation row can be checked against the run",
      ]);
    });

    test("a covered operation no passing test reached fails", () => {
      expect(reconcileOperations(reachedReportOf("expected", "catalog GET /v1/folders"), claims))
        .toEqual(["catalog GET /v1/chains: marked covered, but no passing test reached it"]);
    });

    test("a reached operation no passing test recorded fails", () => {
      // The middle state, which neither direction used to read: `reached` says a spec called the
      // operation, and only the run can say whether one did. The `covered` row is reached here, so the
      // `reached` row is the only one left to report.
      expect(reconcileOperations(reachedReportOf("expected", "catalog GET /v1/chains"), claims)).toEqual([
        "catalog GET /v1/folders: marked reached, but no passing test reached it. A call records one " +
          "through a transport in support/ or through the `request` fixture on a service's own port",
      ]);
    });

    test("a not-reached operation the run reached fails", () => {
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

    test("a call recorded against no row fails", () => {
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
  });
});
