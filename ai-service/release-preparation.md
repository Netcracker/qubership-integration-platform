# AI service release preparation

This checklist tracks the remaining work before delivery and integration into `main`.
The starting revision is `c211950ab`. Each change requires scoped checks, an independent review, and a local commit.
Subagents own separate files; the coordinating chat owns commits and the shared runtime.

The checkpoint at the end of this file is the current release state; earlier checkpoints remain historical.
The approved documentation and recovery fixes are complete. A source-reconciliation change is implemented and
reviewed, but its Greetings repeat failed before analysis or materialization. Release remains blocked pending
source-evidence recovery selection.

## Delivery contract

- Supply the project and runtime packages through the existing unified build procedure.
- Consumers place runtime packages in `integration-platform-skills/rocky-skills` and
  `integration-platform-skills/rocky-knowledge`.
- Without runtime packages, the service supports public documentation and disables CREATE and EDIT.
- Partial or invalid runtime packages fail the build without falling back to the base service.
- Keep the optional testing archive separate from runtime packages. Image variant tags are acceptance artifacts.

## Historical checkpoint through the first integrated candidate

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

## Historical gates and exclusions

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

## Prior follow-up — approved fixes and final smoke halt (superseded)

At this prior checkpoint, the user had approved both remaining fixes. The documentation correction was reviewed:
eight of eight error codes and
11 of 11 normalized endpoint pairs are present, with all three once-only cases, four complete source reads, and
14 paired `gpt-6-luna` exchanges. Literal fidelity remains 11 of 12 entries because the slash-variant duplicate
is not reproduced as a second literal entry; do not report an unqualified pass. The 14-exchange evidence and
earlier partial result remain preserved.

The recovery correction is reviewed: two flow-executor context files preserve the shared CCL submission bridge
and managed-executor lifecycle. The established root cause is Delayer reusing stale prior-Quarkus-profile CCL
tenant configuration; CDI failure was swallowed, leaving the wait unresolved. The boundary regression produced
three expected red cases and one green case, then all 28 integration cases passed. No CDI or internal halt was
observed; ordinary sidecar recovery remains intact. A separate tenant-only experiment was reverted and is not
evidence of a tenant-lookup fix.

Commit `d50e6e4f663293cf507dfae4f85e074e19bdbe2b` is the current candidate. Full, base, and sidecar images built
successfully at AI-service version `1.6.1`; private package fingerprints match the source inputs. The full
candidate is ready, serves the documentation manifest with 93 entries out of 864, and reports certified
`CIP@1.0.0`. Shared containers remain unchanged. The full Java acceptance run passed 6,607 tests in 626 fresh
reports, with zero failures, zero errors, and 11 skips; the run exited successfully. Six incidental A2A-named
cases ran, but the A2A package set did not. The final two-case native smoke failed on Greetings before chain
materialization: the draft added a Groovy author fact absent from the source, and it omitted brief-facts
recovery-halt clarification card 51. The actual smoke sent 24 paired
`gpt-6-luna` exchanges. The initial sandbox attempt was not sent and made zero calls. OpenAPI was not run because
the smoke hit the stop contract; native validation, catalog, runtime, UI, and evaluator did not pass or run.
The user must select whether to capture a third fix for the two identified requirements. Final base-image
verification passed at `http://127.0.0.1:55536`: AI and sidecar report UP, the package is certified, the
knowledge mount is read-only, shared container state is unchanged, and no model calls were made. The base image
ID begins `ecddac498`; the sidecar ID begins `5dc2df`. The full image was restored and passed readiness at
`http://127.0.0.1:55587`: AI and sidecar report UP, package certification passes, the knowledge mount is
read-only, the manifest serves 93 of 864 entries, shared container state is unchanged, and no model calls were
made. Its image ID begins `774559`; the sidecar ID begins `5dc2df`. A final scan checked 4,805 files for the
OpenAI key pattern and found no matches; the key value was not printed. The 139-file testing archive retains its
prior SHA-256. Restart integration testing remains deferred by user priority and is required before push.

The documentation correction and recovery waiter correction are resolved as approved. Documentation coverage
is 11 of 11 normalized endpoint pairs, while literal fidelity remains 11 of 12 because the duplicate slash
variant is not repeated; do not report an unqualified documentation pass. The tenant-only experiment remains
reverted and is not evidence of a tenant-lookup fix.


## Current checkpoint — source reconciliation blocked

Source reconciliation and its regressions are implemented on `1b9bf03f56f3ba602bc8b16522e0b1e97c035200`.
The 17-file change passed independent review and 446 scoped tests. It adds typed source-fact references and
source pins that reopen the existing owner stage; exact-quote restoration proposals for reported fields; and
approval/control pins on the winning source turn. Replay remains null-safe and synchronous, with preflight before
compare-and-set. No synthetic `USER_INPUT` is created. Connected stage controls avoid repeated or wrapper loops,
preserve A when B follows sequentially, and do not grant an ineligible continuation. No new process or budget was
introduced.

The built full, base, and sidecar candidate images are ready from this source revision. The full image is ready;
package certification passes, private package fingerprints match, the knowledge mount is read-only, the
documentation manifest reports 93 documents and 864 sections, and shared container state is unchanged. Image ID prefixes are
`75c888` (full), `67a9a25` (base), and `b2d927` (sidecar). Candidate setup made zero model requests before
the live repeat.

The single frozen Greetings repeat **failed before analysis or materialization**. Its draft cited a nonexistent
source-turn UUID (`…8b205` rather than the actual `…9b205`), so every fact returned `SOURCE_EVIDENCE_REQUIRED`.
The correct ID was present in the complete pending-source fragment and model request context. The tool error said
“Correct the call and try once more” but supplied no replacement ID. A second capture repeated the rejected draft. Both captures also added Groovy without the prompt specifying a
language. The run settled at `clarify:6` in `WAITING_FOR_INPUT`, revision 6, with only `RUN_MANIFEST` committed. Source review,
owner reopen, reconciled candidate, review, approval, brief, plan, and materialization were not reached. New source
linkage was therefore **not exercised**. The original prompt, native input, retry limits, and budgets were
unchanged; no hints, retries, or weakened checks were added. The source-turn mismatch explains this attempt’s
discovery halt, but does not establish live behavior of the newly implemented source-reconciliation flow. The draft’s
unsupported Groovy choice and the earlier missing brief-facts clarification remain proven requirement failures;
this repeat did not reach analysis to test their recovery. This does not show that source guidance or context was
absent, and it does not establish a source-reconciliation code defect. Independent capture review passed with the stated evidence limits. The preparation contract changed after GO metadata was added.
Reversing the six documented execution-metadata changes reconstructed bytes matching the original preparation
SHA-256 (`76b9505466019b82394495daee8f5dfb6c58c49da01f479c057bfd7de8a3e58e`). This is a reconstruction, not an
original pre-run backup. The original preparation manifest remains unchanged, and the independent closure review
passed across 38 hashed evidence files. The proof confirms frozen inputs and the request-integrity hash, but does
not prove exact full model-request replay. No model calls were made for this proof correction. OpenAPI, UI,
catalog, runtime, and semantic judge were not run. The request logger contains previews, not full exact-message
replay; the complete pending-source fragment matches the submitted prompt, but no raw durable source-store export
is available. See
`reconciled-greetings-repeat/report.md` and its result, decision, captures, and evidence manifest.

The registered-provider regression failed before the behavior fix, after its transport-shape addition:
expected native fact owner `script_behavior`, but `originRecordId` was empty. The one-case red run is retained in
`source-reconciliation/provider-red.log`; the affected suites later passed 446 cases without failures or skips.

The fresh full-module run on this source revision passed: 6,675 tests in 626 fresh reports, zero failures, zero
errors, and 11 skips; Maven exited 0 after 315.421 seconds. The dedicated `.ai.a2a` test suites were excluded.
Six A2A-named cases in other suites executed:
`browserCreateChainWorksWhenA2aDisabled`, `aiA2aDataSource_usesLogicalDbNameAndTenantClassifier`,
`productionBrowserAndA2aShareTheFacadeSeam`, `browserAndA2aShareTheS3BackedFacade`,
`a2aRetryStaysPinnedToTheOriginalOffer`, and
`browserAndA2aAdaptersInvokeSameFacadeWithDifferentSerialization`. Local Temurin 21.0.6 was the
C1 workaround. The C2 SIGSEGV artifact is preserved with the run evidence. The report archive SHA-256 is
`8c9bf9d7673d30b77a94f563090f197e5a1d23ae597660afc46bd31059805f6a`; independent XML and archive verification
confirmed the totals. Base image `67a9a25bad7a` passed health at `http://127.0.0.1:58182`, then the full image
`75c888e2bd77` was restored and passed health at `http://127.0.0.1:58211`. Sidecar `b2d9271ca7e9` is healthy with
certified, read-only knowledge and a manifest of 93 documents and 864 sections. Shared containers are unchanged,
and these lifecycle checks made no model requests. The nine-pair capture is preserved under
`candidate-luna/build/archive-revision-1b9bf03f-before-lifecycle/collected-full-candidate-captures/`; its capture
SHA-256 is `805328c4c5ee4f47fad3f29fb1da97dc5de2de80d7e4c558e19df49ab0913ab1`. The earlier 6,607-test
green run remains historical. The release status is **BLOCKED_PENDING_SOURCE_ID_HINT_SELECTION**. No
additional source or model changes are authorized pending the user’s selection on a minimal hint containing the
already available source ID. Strict source-evidence checks remain unchanged; `CURRENT_SOURCE_ID` is a proposed
feedback label, not the observed tool error. Restart integration testing remains
deferred, the testing archive is unchanged, and no push was performed. Preserve the previous failures, image
provenance, and accepted fix evidence as historical records.

## Evidence

The fresh exact OpenAI-key-pattern scan examined 4,903 UTF-8 evidence and helper files and found zero matches;
the value was not printed. The scan excluded frozen source, generated knowledge, large files, and binary files, so
it is not a general guarantee that every secret is absent. Keep raw logs, model exchanges, and private package data
outside the committed delivery files. The coordinating
checkout retains local evidence under `.dev/release-preparation-20261028/`. The final delivery summary must name
each accepted source revision and outcome, including failures and checks that did not run.
