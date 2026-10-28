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
3. **Documentation answers — final live recheck is partial.** Commit `28d88a78` adds literal URL and
   method/path guidance. The regression reproduced before the change; five tests and independent review passed.
   Commit `223f6de95` retains documentation context during consultation; its seven focused tests passed.
   On the isolated base candidate, the HTTP Trigger and unknown-parameter answers passed content checks.
   The full REST error section included all eight expected codes, but the answer omitted the `PATCH`
   method/path pair. Four captured reads cover the complete section byte-for-byte, the full user request remained
   present in every model request, and citation provenance passed. The answer has ten of twelve literal pairs;
   one pair is duplicated with and without a leading slash, so normalized coverage is ten of eleven endpoints.
   This remains a release blocker. The earlier off-task answer remains in the record.
4. **Candidate and live acceptance — selected cases completed with findings.** Full/base AI images and a
   documentation sidecar were built from `28d88a78`; a newer isolated candidate was built from
   `223f6de9526fb210020f7da321a60b4d56a711b6`. Private resources occur only in the full image. The full
   candidate and sidecar are healthy at `http://127.0.0.1:53742`; readiness, package, and documentation-manifest
   endpoints returned 200, and the package reports certified `CIP@1.0.0`. Its knowledge mount is read-only,
   no model request was made by readiness verification, and shared container state is unchanged.
   Greetings and Petstore service-call passed
   materialization, native assertions, catalog review, and static script review. The earlier Greetings setup
   failures remain recorded; its successful continuation did not resend the initial prompt.
   See the [verification summary](release-verification.json) for source revision, image IDs, and case outcomes.
   For each remaining case, retain the frozen source revision and local image ID, and record the returned model.
   The summary records supplied package fingerprints and the QA knowledge archive checksum; registry digests
   are unavailable because these images were not published. All seven selected CREATE/EDIT cases completed.
   The uploaded OpenAPI mapping attempt failed before specification import: an
   extra outbound response interaction targeted Caller and remained unresolved. The uploaded orders operation
   itself was resolved. Commit `ace7916cf` adds a positive capture example and regression: the original annotation
   failed the mapping-fact test; 103 corrected tests passed. The native repeat passed import, materialization,
   exact mapping and catalog review with two interactions and no extra outbound Caller. Preserve the first
   failure. Other live scenarios and base documentation were not repeated on this correction revision.
   For materialized cases, read the generated result back from the catalog and check the case assertions.
   Chain execution, if performed, also requires runtime session evidence and logging restoration.
   Multi-turn approval and materialization passed. The selected revision scenario did not change the draft,
   so incorporation of revised requirements remains unverified.
   The static plan-fault case passed native checks for automatic owner-stage reopening, plan rebuilding, and
   materialization. This result covers the configured contract-shape fault; it does not cover provider outages.
   Both EDIT cases passed native checks and catalog readback. Exact script content, graph order, and preservation
   of seeded elements are checked from catalog evidence separately from the native assertion predicates.
5. **Merge readiness — pending final evidence.** Commit `74517a81a` integrates the branch with `main` and
   preserves AI-service version `1.6.1`; `223f6de95` adds the documentation-context fix. The recovery schema
   fix is integrated. A 16-test focused integration selection and seven documentation tests passed.
   The first full Java acceptance run crashed after 1,235 reported tests. Its retry completed 6,604 tests across
   625 reports, with zero assertion failures, two socket-timeout errors, and 11 skips. Both errors are in
   `SerializedClarificationFlowTest`. The 49-test isolated retry passed, but the full acceptance run remains
   blocked by the two errors. Six mixed tests with A2A in their names ran under the normal selector; the A2A
   package test set was excluded. The final documentation live run is partial: one substantive endpoint is
   missing despite complete, verified source reads. User selection is pending for the documentation-completeness
   correction and the full-suite waiter correction. Requirement revision still lacks a genuine changed-requirement
   input. Update this checklist after the selected work and run the required final review before committing.

## Open gate and exclusions

The recovery schema correction from `cb89c3a08` is integrated in `9212f452` after independent review.
The strict response requires `authorQuestionEvidence.ambiguity` and accepts null or typed evidence.
Native serialization and business validation are unchanged. All 77 focused tests pass.
The earlier full Java acceptance run passed: 6,583 tests, zero failures, zero errors, and 11 skips; A2A was excluded.
The earlier run recorded 6,578 tests, one failure, and 11 skips.
The initial focused attempt could not access Docker from the sandbox; its escalated retry passed.
An overly broad full-run selector reached a manual restart probe and failed during discovery.
The corrected selector uses the normal `*Test` and `*IT` classes and excludes the A2A package test set.
That historical failure was the schema case. The first full run on the integrated source crashed; its retry
completed with 6,604 tests, zero assertion failures, two socket-timeout errors, and 11 skips. Keep both runs
and the isolated 49-test retry in the evidence record. The latest candidate includes the recovery correction and
the documentation-context fix, but its live answer omits one substantive endpoint after reading the full section.
Keep the historical off-task answer and the latest partial result in the evidence record.

A2A testing is excluded by user decision. Restart acceptance remains deferred. Do not expand model or recovery
budgets to make acceptance pass. New production findings require the user's selection under the repository rules.
The documentation candidate uses the base image at `http://127.0.0.1:52316`; HTTP Trigger and unknown-parameter
content checks passed. The full-section answer includes eight codes and ten of eleven normalized endpoint pairs.
This is API chat evidence only; UI, deployment, and chain runtime acceptance were not run.
   The 14-exchange documentation capture came from the base image and remains preserved; full-image readiness
   verification did not run another model case.

## Evidence

Keep raw logs, model exchanges, and private package data outside the committed delivery files. The coordinating
checkout retains local evidence under `.dev/release-preparation-20261028/`. The final delivery summary must name
each accepted source revision and outcome, including failures and checks that did not run.
