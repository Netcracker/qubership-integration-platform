# AI service release preparation

This checklist tracks the remaining work before delivery and integration into `main`.
The starting revision is `c211950ab`. Each change requires scoped checks, an independent review, and a local commit.
Subagents own separate files; the coordinating chat owns commits and the shared runtime.

The checkpoint at the end of this file is the current release state; earlier checkpoints remain historical.
The approved documentation, recovery, and source-ID hint changes are implemented and reviewed. The single explicitly
authorized Greetings run passed native materialization and catalog checks. Local scoped acceptance passed; the deferred
restart integration test remains required before push, and several live acceptance gates remain unrun.

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


## Current checkpoint — local scoped acceptance passed

The source-ID hint change is implemented on application source revision `28b46590c0fc5a513d8973de68192c81d125e5a2`. The
workspace HEAD is `c4983492cc1c9ee256bb95c5ba4b012c32ca3c48`; it contains report metadata, while the tested application
source and candidate images remain unchanged. The hint change covers two production files and adds 11 regression tests.
Its red run failed three of 11 tests with zero errors because unavailable-source-ID feedback omitted current authorized
source IDs and read/capture guidance. The scoped green run passed 163 tests across `RequirementCaptureToolsTest` (149),
`RequirementCaptureToolsSchemaTest` (1), and `RequirementDraftStoreLifecycleTest` (13), with zero failures, errors, or
skips. The hint includes at most the latest 20 eligible source IDs and never source bodies; lookup and strict evidence
validation remain unchanged.

The fresh full-module acceptance run passed 6,686 tests in 626 fresh reports, with zero failures, zero errors, and 11
skips. Maven exited 0 in 337.057 seconds using the local Temurin 21.0.6 workaround. The recorded run archive SHA-256 is
`1f07e721845e2e7f584ba12325a85679b0c1250d6115e2e9a1094ba770b96d31`. Independent XML and archive verification confirmed
the same totals and archive hash. The dedicated A2A package suites were not executed; six incidental A2A-named cases in
other suites ran. The full candidate remained at `http://127.0.0.1:62951`; the base candidate was checked at port
`62924`. The completed Greetings run made no changes to either image.

The automatic approval review initially rejected the Greetings run before process creation or transmission. The user
then explicitly authorized one run that sends the original test request and normal AI-service context to
`https://api.openai.com/v1` using `gpt-6-luna`. Exactly one native run completed with 22 paired model requests and
responses; requested and returned model were both `gpt-6-luna`. The frozen input contract remains unchanged at SHA-256
`52631012b81c17058c21bb02693c071e82d2a56ff4a8b5e04389bbdd7225c2e3`; the submitted prompt differed only by the generated
unique chain name. No extra retries, hints, manual patches, or budget changes were used. Twelve request pairs carry the
conversation ID and ten downstream pairs are labeled `(none)`. Six provider responses are retained as raw JSON bodies
and 16 as terminal SDK SSE `response.completed` payloads, all parsed as completed. Request messages are bounded
previews; exact full-request replay is not established. The earlier rejected request remains a zero-call historical
event, not a second run.

The native scenario passed through `CHAIN_MATERIALIZED`, validation, reconciliation, and catalog readback. Nine accepted
facts remained byte-equivalent; the native trigger-anchor fact was added. The new source-ID hint path was
**NOT_EXERCISED** because the model cited the correct source ID immediately. Typed source-review reopening was also
**NOT_EXERCISED**; no source-review artifact or `MISSING_BRIEF_FACTS` rejection occurred. The ordinary native capture
corrections worked without resetting the nine accepted facts.

The retained chain is `AiCreateSmoke.20261029T064154.r1` (`f76d7439-af23-4131-9c7e-b15384c01f9b`). Catalog readback
shows one internal `GET /greetings` HTTP trigger with access control `NONE`, one script, and one connecting edge. The
script returns `Hello, world!` as `text/plain; charset=UTF-8`; static script review passed. No service-call or
error-handler element exists. The chain remains an unsaved working copy (`unsavedChanges: true`), with no deployment
artifact and no runtime invocation. This is a native materialization and catalog result, not deployed runtime evidence.

Overall status is **LOCAL_SCOPED_ACCEPTANCE_PASSED**, not unconditional merge readiness. The required
`CreateChainServerRestartIT` remains deferred and must pass before push; no push was performed. UI, runtime, evaluator,
and the other six selected live cases were not run on this source revision. OpenAPI and semantic judging were not run.
The prior documentation result remains partial at 11 of 12 literal endpoint entries; that scenario was not repeated. A
fresh scoped scan examined 5,043 UTF-8 evidence and helper files for the exact current OpenAI key pattern and found zero
matches; the value was not printed. The scan included feature evidence and excluded only frozen source snapshots,
pre-merge source, generated knowledge, files over 64 MiB, and binary files. This is not a general guarantee that every
secret is absent. The 139-file testing archive remains unchanged. Evidence for this run is under
`.dev/release-preparation-20261028/source-hint-greetings-repeat/`; the post-run scan is under
`.dev/release-preparation-20261028/source-id-feedback/post-greetings-secret-evidence-scan.json`.
## Evidence

The latest exact OpenAI-key scan examined 5,043 UTF-8 evidence and helper files and found zero matches; the value was
not printed. Evidence is `source-id-feedback/post-greetings-secret-evidence-scan.json`. The scan includes feature
run evidence and excludes frozen source snapshots, pre-merge source, generated knowledge, files over 64 MiB, and
binary files. It is not a general guarantee that every secret is absent. Keep raw logs, model exchanges, and private
package data outside the committed delivery files. The coordinating
checkout retains local evidence under `.dev/release-preparation-20261028/`. The final delivery summary must name
each accepted source revision and outcome, including failures and checks that did not run.
