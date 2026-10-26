---
name: rocky-binding-mapping
description: >-
  Run and continue the Rocky binding, mapping, and recovery test campaigns. Use when the user
  invokes this skill or asks to resume these campaigns, inspect progress, generate edge cases,
  run low-token tests, or prepare the next convergence wave.
---

# Rocky convergence campaigns

Build confidence in binding and mapping behavior with deterministic tests first and a small live
sample only where model behavior matters. Keep the campaign resumable across conversations.

## Start or resume

1. Read [references/progress.md](references/progress.md) before choosing work. Treat it as the
   campaign source of truth.
2. Read [references/commands.md](references/commands.md) only when running or extending tests.
3. Inspect the current worktree before editing. Do not assume campaign files are the only local
   changes, and do not commit or stage unless the user asks.
4. Continue the highest-priority incomplete item that fits the user's requested scope.

## Convergence loop

1. Express a new edge case as a deterministic fixture or mutation whenever the behavior is below
   the LLM boundary.
2. Run the smallest focused test that can disprove the expected behavior.
3. When focused tests pass, run the binding, mapping, topology, and materialization regression set.
4. Use live evaluation only for generator behavior that deterministic compilation cannot prove.
   Start with one repetition. For executor evals that reuse saved planner output, verify
   `plannerCalls=0`; full capture and planning runs may invoke the planner.
5. Use only `claude-sonnet-5` or `gpt-5.6-luna` for live tests. Confirm that the configured
   provider exposes the selected model; use Sonnet when Luna is unavailable. Increase repetitions
   only after the first sample passes or a concrete variance needs investigation.
6. For each new failure, classify it as test setup, harness, deterministic production code, or
   model behavior. Reproduce and establish the cause before changing production code. Apply the
   CIP artifact approval gates when fixing an existing chain; service runtime defects follow the
   repository software workflow and the user’s existing authorization.
7. After any state change, update [references/progress.md](references/progress.md) with fresh
   evidence, the next action, and any unresolved defect. Never mark a stage covered from an old
   run.

## Test design rules

- Preserve approved mapping bodies in `RequirementBrief`; semantic revisions carry sites and
  identity, not an alternate mapping-body authority.
- Treat the accepted `DesignPlanContract` as the only machine-readable planning authority. The
  rendered report is an approval view. Never recover owners, targets, roles, or dependencies from
  summaries, verbs, report order, neighboring steps, participant names, or operation descriptions.
- Validate every required `(targetKind, targetId)` from the approved semantic revision. Cover entry
  points, service-call occurrences, mapping intents, all region kinds, behavior-owned script nodes,
  and unresolved catalog bindings. Require exactly one `PRODUCER`; allow any number of `REFERENCE`
  claims. Two calls to one catalog operation remain distinct when their occurrence IDs differ.
- Match each producer to an exact owner ID. Use the selected mapping generator, the region-specific
  generator, and exact supported APIHub operation IDs. Express ordering only with validated
  `dependsOnStepIds`; never infer a follow-up from words such as "resolve", "connect", or "wire".
- Bind approval and execution to the contract digest, rendered report, execution projection, and
  semantic revision. Reject any missing or stale link before a generator or catalog adapter runs.
- Preserve the approved service-call failure mode. `INLINE_RESPONSE` stays on the normal mapping
  path, `PROPAGATE` stops the chain, and `ERROR_SCOPE` requires an accepted error-scope region.
  Planner wording alone cannot create error handling. An `INLINE_RESPONSE` occurrence must have a
  successor response interaction; do not copy that mode onto the terminal delivery call.
- Check mapping placement against the approved source-to-target transition, including ambiguity
  and reconvergence.
- Cover malformed values through permutations or compact mutation loops instead of many copied
  fixtures.
- For immediate capture rejection and delayed failures, assert the detector, repair owner,
  unchanged approved inputs, invalidated downstream state, durable attempt budget, restart
  behavior, and terminal status after repair.
- For recovery work, read [references/recovery-boundaries.md](references/recovery-boundaries.md).
  Exercise the real capture, validator, stage executor, and lifecycle together. A mock failure
  outcome or a happy-path live run alone cannot establish that rejected generation recovers.
- Keep local correction and clean rebuild as separate durable budgets. After the same planning
  defect survives local correction, allow one automatic clean model turn with the approved brief,
  semantic revision, catalog pin, and user decisions unchanged. A repeated defect then waits at the
  manual rebuild-or-stop gate. Never auto-rebuild after materialization or a catalog snapshot.
- Key planning recovery by typed finding code, target kind, target ID, and step ID. Human wording is
  diagnostic text and must not allocate a new attempt or select a recovery action.
- Reuse existing semantic fixtures and Maven tests before adding a new harness or dependency.
- Keep live cases representative: one case per materially different model decision boundary.

## Evidence and stopping conditions

Record test counts, failures, errors, skipped tests, mutation combinations, live model, duration,
planner calls, and raw report paths. A deterministic wave is covered only when its focused and
expanded suites are green. A live wave remains in progress until every selected representative
case has the agreed repetition count and no unexplained variance.

If a new defect is found, reopen the affected coverage claim and record its signature and evidence.
Continue diagnosis and authorized fixes. Apply approval gates only within their stated scope.
Distinguish deterministic coverage, live model evidence, and unresolved defects in the final report.
