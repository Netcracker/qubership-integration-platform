/**
 * Chain-rule validation: `diagnostic-controller`, whole — search, get by id, and run.
 *
 * Global by construction, and `e2e/AGENTS.md` rule 2 is what puts the file here. A run deletes and
 * rewrites `validation_chains_alerts` for **every** chain on the stack, one rule at a time
 * (`DiagnosticService.runValidationsAsync`), and the last-run status is a single row per rule in
 * `validation_status` that `POST /validations` and `GET /validations/{id}` both read. Two workers
 * running validations at once would each be reading the other's rewrite, which the service stops
 * with a lock — see the third bullet.
 *
 * The rule under test is `scripting-found-in-chain`, chosen because it is the one built-in rule a
 * spec can satisfy on demand and undo again: its query is
 * `SELECT * FROM catalog.elements WHERE chain_id IS NOT NULL AND type = 'script' …`, so a `script`
 * element in a chain is enough and nothing has to be snapshotted, deployed, or timed.
 *
 * Five shapes, each measured against the stack or read off the code:
 *
 * - **The listing carries counts, a search carries entities.** With neither a `searchString` nor
 *   `filters`, `getFilteredValidations` builds each row from `alertsCount` alone and every
 *   `chainEntities` comes back empty. The entities appear only when a filter selected them.
 * - **`PATCH` answers 202 and the work runs on a `CompletableFuture`.** An assertion made straight
 *   after the call reads the *previous* run, so every case here polls `startedWhen` forward rather
 *   than sleeping.
 * - **A run is exclusive.** `DiagnosticService.validationUpdateTryLock` takes a `ConfigParameter`
 *   row with one `INSERT … ON CONFLICT DO UPDATE … WHERE` and counts it taken only when that
 *   statement changed a row, so a `PATCH` that arrives while a run holds it is refused with **409**
 *   `Validation(s) already in progress`. A lock older than 15 minutes is taken over.
 * - **An unknown validation id is dropped, not refused.** `PATCH ?validationIds=no-such` answers
 *   202 and runs nothing — `filteredIds` is an intersection with the known rules. `GET` of the same
 *   id answers **404**, so the two disagree about what an unknown id is.
 * - **A run rewrites rather than accumulates.** Removing the offending element and running again
 *   takes the alert away, which is what the third leg of the round-trip below asserts.
 *
 * Every assertion about *findings* is filtered to the chain this spec created, because the answers
 * are catalog-wide: the id below reported five alerts across three unrelated chains while it was
 * being measured.
 */
import { test, expect } from "../../support/fixtures.js";
import { tokenized } from "../../support/run.js";
import type { Catalog, DiagnosticValidation, ValidationChainEntity } from "../../support/catalog.js";

/**
 * The rule this spec drives, spelled out rather than looked up by title.
 *
 * The id is `ScriptingFoundInChainValidation`'s constructor argument under the `built-in_` prefix
 * `BuiltinValidation` adds, and it is persisted — `catalog.validation_status.validation_id` and
 * every alert row carry it — so it is a contract and not an implementation detail.
 */
const SCRIPTING_RULE = "built-in_scripting-found-in-chain_0WHKW1A3";

const STATES = ["OK", "NOT_STARTED", "IN_PROGRESS", "FAILED"];

/**
 * Runs one rule and waits for that run, not for a run.
 *
 * `startedWhen` is captured first and the poll requires it to have moved: a rule that was already
 * `OK` from an earlier case would otherwise satisfy a state check instantly and the assertions
 * would read the previous run's alerts. The `PATCH` itself is polled because 409 is a legitimate
 * answer while another run holds the lock.
 */
async function runRule(catalog: Catalog, validationId: string): Promise<DiagnosticValidation> {
  const before = (await catalog.getValidation(validationId)).status.startedWhen;
  await expect
    .poll(async () => (await catalog.runValidations([validationId])).status(), {
      message: `validation ${validationId} never started: the run lock stayed held`,
    })
    .toBe(202);

  let finished: DiagnosticValidation | undefined;
  await expect
    .poll(
      async () => {
        finished = await catalog.getValidation(validationId);
        return finished.status.startedWhen !== before ? finished.status.state : "IN_PROGRESS";
      },
      { message: `validation ${validationId} never finished a fresh run` },
    )
    .toBe("OK");
  return finished!;
}

/** What the rule found in one chain, out of an answer that spans the whole catalog. */
function findingsFor(validation: DiagnosticValidation, chainId: string): ValidationChainEntity[] {
  return (validation.chainEntities ?? []).filter((entity) => entity.chainId === chainId);
}

/** An empty chain of this spec's own, inside the worker folder. Each caller wires what it needs. */
async function scriptingChain(
  catalog: Catalog,
  run: string,
  folderId: string,
  what: string,
): Promise<{ id: string; name: string }> {
  const name = tokenized(run, `diag-${what}`);
  const chain = await catalog.createChain(name, folderId);
  return { id: chain.id, name };
}

test("the rule catalog lists every built-in rule with its last run and no findings", { tag: ["@catalog", "@tier1"] }, async ({ catalog }) => {
  const rules = await catalog.listValidations();
  expect(rules.length, "the catalog serves no diagnostic rules at all").toBeGreaterThan(0);

  // Ids are the join key for alerts and statuses, so a duplicate would silently merge two rules.
  expect(new Set(rules.map((rule) => rule.id)).size).toBe(rules.length);

  for (const rule of rules) {
    expect(rule.id, `rule ${rule.title} has no id`).toBeTruthy();
    expect(rule.title, `rule ${rule.id} has no title`).toBeTruthy();
    expect(["CHAIN", "CHAIN_ELEMENT"]).toContain(rule.entityType);
    expect(["BUILT_IN", "PLUGIN"]).toContain(rule.implementationType);
    expect(["WARNING", "ERROR"]).toContain(rule.severity);
    expect(STATES).toContain(rule.status.state);
    // The listing is the cheap form: counts, and no entities behind them.
    expect(rule.chainEntities ?? [], `the listing carried entities for ${rule.id}`).toEqual([]);
  }

  const scripting = rules.find((rule) => rule.id === SCRIPTING_RULE);
  expect(scripting, `${SCRIPTING_RULE} is missing from the rule catalog`).toBeDefined();
  expect(scripting).toMatchObject({
    title: "Scripting found in the chain",
    entityType: "CHAIN_ELEMENT",
    implementationType: "BUILT_IN",
    severity: "WARNING",
  });
});

test("a run finds the chain's script, and a rerun stops finding it once the element is gone", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const chain = await scriptingChain(catalog, run, folder.id, "roundtrip");

  // A chain with no script is not a finding, however many other chains are.
  expect(findingsFor(await runRule(catalog, SCRIPTING_RULE), chain.id)).toEqual([]);

  const script = await catalog.createElement(chain.id, "script");
  const found = await runRule(catalog, SCRIPTING_RULE);
  const mine = findingsFor(found, chain.id);
  expect(mine, "the run did not flag the script this case added").toHaveLength(1);
  expect(mine[0]).toMatchObject({
    chainId: chain.id,
    chainName: chain.name,
    elementId: script.id,
    elementType: "script",
  });
  // The catalog-wide count is at least this case's own finding: the rule reports every chain.
  expect(found.alertsCount).toBeGreaterThanOrEqual(1);

  // And the alert is rebuilt rather than appended to, so removing the element removes the finding.
  await catalog.deleteElement(chain.id, script.id);
  expect(findingsFor(await runRule(catalog, SCRIPTING_RULE), chain.id)).toEqual([]);
});

test("a search and a filter scope the findings to the chain they name", { tag: ["@catalog", "@tier2"] }, async ({ catalog, folder, run }) => {
  const chain = await scriptingChain(catalog, run, folder.id, "search");
  const script = await catalog.createElement(chain.id, "script");
  await runRule(catalog, SCRIPTING_RULE);

  // A search string is expanded into CHAIN_NAME / ELEMENT_NAME / ELEMENT_TYPE `CONTAINS`, so the
  // run token in the chain's name is enough to reduce a catalog-wide answer to this chain.
  const searched = await catalog.listValidations({ searchString: chain.name });
  expect(searched.map((rule) => rule.id)).toEqual([SCRIPTING_RULE]);
  expect(searched[0]?.chainEntities).toHaveLength(1);
  expect(searched[0]?.chainEntities?.[0]).toMatchObject({ chainId: chain.id, elementId: script.id });
  // Scoped to one chain, the count is that chain's, not the catalog's.
  expect(searched[0]?.alertsCount).toBe(1);

  // The explicit filter takes the same predicate and answers the same thing.
  const filtered = await catalog.listValidations({
    filters: [{ column: "CHAIN_NAME", condition: "CONTAINS", value: chain.name }],
  });
  expect(filtered.map((rule) => rule.id)).toEqual([SCRIPTING_RULE]);
  expect(filtered[0]?.chainEntities?.map((entity) => entity.elementId)).toEqual([script.id]);

  // A string nothing carries answers with an empty list rather than with everything.
  expect(await catalog.listValidations({ searchString: tokenized(run, "diag-nothing-matches") })).toEqual([]);

  // Severity is applied after the alert query, over the rules the query returned.
  const warnings = await catalog.listValidations({
    filters: [{ column: "VALIDATION_SEVERITY", condition: "IN", value: "WARNING" }],
  });
  expect(warnings.map((rule) => rule.id)).toContain(SCRIPTING_RULE);
  for (const rule of warnings) expect(rule.severity).toBe("WARNING");

  // Severity is applied after the alert query, so the ERROR half can only ever answer the
  // ERROR-severity rules that already carry alerts — on a stack whose chains trip none that is an
  // empty list, and a loop over it proves nothing because it does not run. The claim that holds
  // whatever the stack contains is the partition: `ValidationSeverity` has two members, so
  // `NOT_IN ERROR` answers exactly what `IN WARNING` does, and the rule this case put alerts on is
  // in one of the two and not the other.
  const errors = await catalog.listValidations({
    filters: [{ column: "VALIDATION_SEVERITY", condition: "IN", value: "ERROR" }],
  });
  const notErrors = await catalog.listValidations({
    filters: [{ column: "VALIDATION_SEVERITY", condition: "NOT_IN", value: "ERROR" }],
  });
  expect(errors.map((rule) => rule.id)).not.toContain(SCRIPTING_RULE);
  expect(
    notErrors.map((rule) => rule.id).sort(),
    "NOT_IN ERROR is the complement of IN ERROR over the rules carrying alerts",
  ).toEqual(warnings.map((rule) => rule.id).sort());
  for (const rule of errors) expect(rule.severity).toBe("ERROR");
});

test("the reader and the runner disagree about an unknown validation id", { tag: ["@catalog", "@tier2"] }, async ({ catalog }) => {
  const unknown = await catalog.raw("get", "/v1/catalog/diagnostic/validations/no-such-validation");
  expect(unknown.status()).toBe(404);
  expect(await unknown.text()).toContain("no-such-validation");

  // The same id through the runner is dropped instead: `filteredIds` intersects with the known
  // rules, so nothing runs and the call still answers 202. Two shapes of "unknown id", one
  // controller.
  // A rule this file never runs, so its last-run row is the one thing on the stack that moves only
  // if the runner expanded an id it was supposed to drop.
  const witness = (await catalog.listValidations({}))
    .map((rule) => rule.id)
    .find((id) => id !== SCRIPTING_RULE)!;
  const witnessBefore = (await catalog.getValidation(witness)).status.startedWhen;
  expect((await catalog.runValidations(["no-such-validation"])).status()).toBe(202);

  // The negative needs a barrier. The work runs on a `CompletableFuture`, so a status read taken
  // straight after the 202 answers "nothing ran" just as readily for a run that had not written its
  // row yet. A real run of `SCRIPTING_RULE` is the barrier: `runRule` returns only once the catalog
  // has written that rule's status row, and a `PATCH` that had expanded to every rule would have
  // written the witness's by then.
  await runRule(catalog, SCRIPTING_RULE);
  expect(
    (await catalog.getValidation(witness)).status.startedWhen,
    "a validation id nothing answers to ran a rule that was never asked for",
  ).toBe(witnessBefore);

  // The 409 a second caller gets is not asserted: whether a call lands while the first run still
  // holds the lock depends on how long that run takes.
});
