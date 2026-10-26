# Recovery boundaries

The 27473d76 incident rejected a generated topology for a missing region and an unsupported join.
Runtime converted regeneration into brief revision, while green tests expected that conversion.
Treat this as a recovery contract failure even if earlier campaigns passed.

## Required assertions

- Start with an approved brief. Reject the first generated candidate through the real capture and
  semantic validator. Model the next response, but do not replace validation or recovery with success.
- Exercise the generated AI-service tool loop as well as the capability runner. Count model calls
  after accepted and rejected capture; annotation values alone do not prove a streaming limit.
  Capture must return to the runtime before another model request can begin.
- Test missing capture, malformed capture, structural rejection, and several findings together.
  Include missing scoped region plus generic join, and a mapping placement rejection.
- Exercise accepted regeneration, absent or invalid recovery decisions, and a derivation question.
  Preserve a genuine brief question or revision decision; rejected topology alone does not prove
  missing user requirements.
- Keep brief content, mapping bodies, catalog identities, and approval unchanged during derivation
  repair. The second generator prompt must receive the full findings, including findings beyond
  the log preview limit.
- Regenerate at the artifact producer. Check immediate rejection at design input and planning,
  delayed upstream rejection, and manual execution regeneration as separate boundaries.
- Persist the attempt before retry. Recreate runtime and stores before continuing. Repeated failure
  must exhaust the same budget after restart; changed error text must still respect the run ceiling.
- When attempts are exhausted, preserve a report without claiming the brief is defective. Retry
  clicks must not bypass the budget or select an earlier producer by position alone.
- Guard regeneration both when rendering a card and when accepting it after a catalog write.
- Treat validator acceptance and preservation of requested error handling as separate assertions.
  A repair that deletes required catch behavior or mappings is not a successful repair.
- Compare the typed planning contract with the approved semantic revision before rendering an
  approval candidate. Cover every entry point, service-call occurrence, mapping intent, region,
  behavior-owned script node, and unresolved catalog binding. Missing, unknown, wrong-kind,
  wrong-owner, and multiply produced targets are planning defects.
- Treat a catalog operation and a service-call occurrence as different identities. Two occurrences
  may target the same catalog operation. Any number of steps may reference one occurrence; only a
  second `PRODUCER` for the same `(targetKind, targetId)` is a duplicate.
- Validate explicit step dependencies for unknown IDs, self-dependencies, cycles, and missing pinned
  compiler predecessors. Reordering independent display steps must not change execution semantics.
- Render approval Markdown from the accepted contract and verify contract, report, projection, and
  semantic revision digests before execution. Model-authored summary text cannot select an owner or
  inject machine facts into the report.
- Carry typed findings into recovery without converting their identity to prose. Give planning one
  local correction and one separate clean rebuild for an unchanged approved-input epoch. The clean
  rebuild uses a fresh model session and no rejected-plan prose. A repeated defect opens the manual
  rebuild-or-stop gate; a catalog write disables automatic rebuild immediately.
- Missing failure intent belongs to requirement capture. An invented error scope belongs to design
  input or planning, depending on the first artifact that introduces it.
- During restart tests, inspect workflow transaction completion as well as artifact journal progress.
  A later approval card does not clear an earlier transaction timeout or rollback.

## Live evidence

Use a new test conversation and the configured Sonnet or Luna model after rebuilding the service.
Record the exact stop boundary; a run that stops at plan approval does not establish materialization.
Use an injected first-attempt rejection or an observed natural rejection to verify recovery, then
inspect the repaired topology and approved mappings. Record planner calls as observed: `plannerCalls=0` applies to saved-plan executor
samples, not to a product run that must produce a plan.

For invisible tools, correlate actual server invocations with saved SSE `step` events and UI parent
ids. Empty persisted timelines do not prove absent live activity. Keep this diagnosis separate from
recovery routing until the loss boundary is reproduced.
