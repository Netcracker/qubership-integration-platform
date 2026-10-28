# AI service release preparation

This checklist tracks the remaining work before delivery and integration into `main`.
The starting revision is `c211950ab`. Each change requires scoped checks, an independent review, and a local commit.
Subagents own separate files; the coordinating chat owns commits and the shared runtime.

## Delivery contract

- Supply the project and runtime packages through the existing unified build procedure.
- Consumers place runtime packages in `integration-platform-skills/rocky-skills` and
  `integration-platform-skills/rocky-knowledge`.
- Without runtime packages, the service supports public documentation and disables CREATE and EDIT.
- Partial or invalid runtime packages fail the build without falling back to the base service.
- Keep the optional testing archive separate from runtime packages. Image variant tags are acceptance artifacts.

## Remaining steps

1. **UI checks — complete.** Commit `94d60447e` removed the two unnecessary assertions and the unused hook
   dependency. Scoped lint, 13 tests, types, formatting, and independent review passed.
2. **Package inventory — complete.** Commit `0e676db9a` allows optional source `testing/` while retaining strict
   runtime checks. The failure reproduced, then four certified-package tests and independent review passed.
3. **Documentation answers — live recheck found a failure.** Commit `28d88a78` adds literal URL and
   method/path guidance.
   The regression reproduced before the change; five tests and independent review passed. Three base-image
   consultations completed. HTTP Trigger and unknown-parameter answers passed content checks; the full-section
   request received an off-task CIP-4100 answer. Source provenance and pagination completion remain unverified.
4. **Candidate and live acceptance — selected cases completed with findings.** Full/base AI images and a
   documentation sidecar were built from `28d88a78`. Private resources occur only in the full image.
   Greetings and Petstore service-call passed
   materialization, native assertions, catalog review, and static script review. The earlier Greetings setup
   failures remain recorded; its successful continuation did not resend the initial prompt.
   See the [verification summary](release-verification.json) for source revision, image IDs, and case outcomes.
   For each remaining case, retain the frozen source revision and local image ID, and record the returned model.
   The summary records supplied package fingerprints and the QA knowledge archive checksum; registry digests
   are unavailable because these images were not published. All seven selected CREATE/EDIT cases completed.
   The uploaded OpenAPI mapping attempt failed before specification import: an
   extra outbound response interaction targeted Caller and remained unresolved. The uploaded orders operation
   itself was resolved. A source correction awaits user selection; preserve this failed attempt.
   For materialized cases, read the generated result back from the catalog and check the case assertions.
   Chain execution, if performed, also requires runtime session evidence and logging restoration.
   Multi-turn approval and materialization passed. The selected revision scenario did not change the draft,
   so incorporation of revised requirements remains unverified.
   The static plan-fault case passed native checks for automatic owner-stage reopening, plan rebuilding, and
   materialization. This result covers the configured contract-shape fault; it does not cover provider outages.
   Both EDIT cases passed native checks and catalog readback. Exact script content, graph order, and preservation
   of seeded elements are checked from catalog evidence separately from the native assertion predicates.
5. **Merge readiness — blocked pending fix selection.** The selected campaign and archive inventory are recorded.
   Select corrections for the uploaded-OpenAPI failure and off-task documentation answer before another candidate.
   The recovery schema fix remains deferred. Requirement revision needs a genuine revision input if that coverage
   is required. After approved corrections, rerun affected gates, review the final diff, and refresh main integration.

## Open gate and exclusions

The recovery response schema requires `authorQuestionEvidence.ambiguity` even for an ordinary conflicting-values
question. The existing `RecoveryDecisionSchemasTest` exposes this mismatch. The user deferred the production fix;
keep this test enabled and report the failure. A green Java acceptance claim and an unconditional merge remain blocked.

The latest full Java run before these steps recorded 6,578 tests, one failure, zero errors, and 11 skips.
The sole failure was the deferred schema case. Test fixtures were corrected in `c211950ab`.

A2A testing is excluded by user decision. Restart acceptance remains deferred. Do not expand model or recovery
budgets to make acceptance pass. New production findings require the user's selection under the repository rules.

## Evidence

Keep raw logs, model exchanges, and private package data outside the committed delivery files. The coordinating
checkout retains local evidence under `.dev/release-preparation-20261028/`. The final delivery summary must name
each accepted source revision and outcome, including failures and checks that did not run.
