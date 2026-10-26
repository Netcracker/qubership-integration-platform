# Binding and mapping campaign progress

Updated: 2026-09-14

## Current state

| Stage | Status | Evidence | Next decision |
|---|---|---|---|
| Executor test harness | Covered | 4 offline fixtures; offline preflight PASS | Extend only for a new boundary |
| Binding contract | Covered | 3 occurrences: 1 valid and 5 invalid owner permutations | Monitor new shapes |
| Mapping contract | Covered | 3 intents: 1 valid and 5 invalid site permutations | Monitor new shapes |
| Topology and materialization | Covered | 287 tests; 0 failures; 0 errors; 2 skipped | Monitor new shapes |
| Live generation | Guarded before planning | Sonnet rerun rejected six invalid semantic revisions; no plan or catalog write | Re-run after failure-mode classifier fix |
| Continuous convergence | Covered | PR `mvn verify`; local pre-release sample PASS | Run before release |
| Recovery routing | Producer repair confirmed live | Five durable design-input repairs; approved brief unchanged; 150 recovery tests | Resolve the separate restart transaction warning |
| Replace subgraph | Covered | Regression test and 2 Sonnet catalog runs PASS | Monitor new edit shapes |
| Uploaded OpenAPI path | Covered | 2 Sonnet runs imported, bound, mapped, and materialized | Monitor variance |
| Mixed uploaded-spec types | Covered | INTERNAL and EXTERNAL specs with duplicate operation ids bind by catalog path | Monitor reuse collisions |
| Semantic quality gate | Covered | Functional rejection test plus live scores and verdict | Monitor |
| Requirement flow completeness | Covered | T-028 and seven follow-up defects are covered | Run a small Sonnet wave |
| Planner occurrence identity | Deterministic fix covered | Distinct repeated operations and repeated references pass; duplicate producers fail | Repeat Sonnet plan run |
| Failure-mode topology | Deterministic fix covered | Typed propagate, inline response, and error-scope checks | Repeat Sonnet plan run |
| Typed planning contract | Deterministic fix covered | Full module: 4,009 tests, 0 failures, 0 errors, 4 skipped | Run bounded Sonnet smoke |
| Planning clean rebuild | Deterministic fix covered | Separate durable budget and post-catalog guard pass in the full suite | Run bounded Sonnet smoke |

## Covered behavior

- Repeated catalog operation with distinct service-call occurrences.
- Repeated resolve, generate, and connection references for one occurrence, with duplicate
  detection limited to multiple producing steps.
- Missing, duplicate, swapped, extra, and non-service binding targets.
- Request and response mappings, orphan intents, duplicate sites, unknown ids, and shared
  transform ownership.
- Typed-flow and legacy mapping-reference validation.
- Mapping site parity with the approved flow transition.
- Linear, condition, split, reconvergence, loop, retry, error-scope, multiple-entry, and catalog
  materialization paths.
- Two mappings and two distinct service-call bindings combined inside condition, retry, and
  `try-catch-finally-2` topologies.
- Three mapped service-call occurrences with all six binding-owner permutations.
- Three approved flow transitions with all six mapping-site permutations.
- Delayed failures routed from execution to requirement analysis, design input, and planning.
- Pre-write materialization failures reopen execution; post-write failures park safely.
- Cause-specific budgets allow two different owners to recover in one run.
- Restart after reopen preserves prior approvals, invalidation, repair evidence, and the new
  approval gate.
- A repaired upstream artifact can continue to `CHAIN_MATERIALIZED` with catalog evidence.
- Replace-subgraph preserves an incident edge when the patch rewires that edge away from the removed node.
- Uploaded OpenAPI flows preserve imported catalog identity through service binding and request mapping.
- Identity-shaped service responses can pass through without an unnecessary response mapping script.
- Known catalog operations remain complete across positive facts, approved uploads, repeated occurrences, and explicit
  transition order.
- Native trigger facts must own an inbound flow interaction.
- Explicit mapping assignments must appear on the requested hop, and catalog binding cannot clear unrelated questions.

## Resolved defects

| Defect | Result |
|---|---|
| BM-001 | Service-call binding ownership is validated against the semantic node |
| BM-002 | Mapping references are validated on semantic compilation paths |
| BM-003 | Mapping-carrying edges must belong to the approved flow transition |
| BM-004 | Materialization parity test supplies the brief that owns its mapping body |
| REC-001 | IDS and semantic-revision defects detected during execution reopen design input |
| REC-002 | Pre-write materialization contract defects reopen and rerun design execution |
| REC-003 | Runtime lifecycle applies the non-approval execution-owner reopen instead of rerunning materialization |
| REC-HARNESS-001 | Live verifier retains the detector stage after evidence moves to the reopened owner |
| REC-HARNESS-002 | Recovery scenarios answer optional HTTP response clarifications deterministically |
| REC-INFRA-001 | Local evaluator uses host port 8100 without conflicting with AKHQ on 8099 |
| REC-INFRA-002 | Evaluator uses the provider's supported JSON schema and omits deprecated temperature |
| REC-SEM-001 | Design plan preserves approved endpoint and behavior facts from the brief |
| REC-HARNESS-003 | Quality-gate verdict fails when `semantic.failed` is true |
| REC-HARNESS-004 | Runner contract covers all six active compare-and-patch scenarios |
| PATCH-001 | Removal closure keeps an explicitly updated incident edge during replace-subgraph |
| UPLOAD-HARNESS-001 | Uploaded OpenAPI assertions accept an identity-shaped response pass-through |
| FLOW-COMP-001 | Explicit multiplicity and separate service-call facts require matching bound flow occurrences |
| FLOW-COMP-002 | Positive facts participate in known-operation completeness checks |
| FLOW-COMP-003 | Approved uploads no longer exempt remembered catalog operations from completeness checks |
| FLOW-COMP-004 | Explicitly sequenced catalog operations must follow the same flow transition order |
| FLOW-COMP-005 | Native trigger facts must reference an inbound interaction in the approved flow |
| MAP-COMP-001 | Explicit `target = source` and `map source to target` rules must be captured on the named hop |
| IMPORT-COMP-001 | Catalog binding preserves open questions and cannot promote an incomplete draft |
| UPLOAD-BIND-002 | Duplicate operation ids across imported specs are disambiguated by exact catalog path; unresolved cases fail closed |
| REC-PLAN-004 | Planner service-call steps carry occurrence ids; participant and operation text is projected from the approved brief |
| REC-PLAN-005 | Planner error handling must match an accepted error-scope region; outbound calls carry a typed failure mode |
| REC-PLAN-006 | Planning captures a typed contract; report wording no longer owns targets, roles, owners, or dependencies |
| REC-PLAN-007 | Repeated planning failure receives one clean rebuild before the manual rebuild-or-stop gate |

## Model policy and exclusions

| Finding | Evidence | Decision |
|---|---|---|
| BM-LIVE-001 | Current Anthropic endpoint rejects `gpt-5.6-luna` as unknown | Use Sonnet until Luna is available |
| BM-LIVE-002 | Haiku closes the complex request twice; Sonnet passes | Haiku is outside the approved model set |

## Resolved completeness diagnoses

The 2026-09-13 deterministic audit found six omission shapes and one state-transition defect. The initial focused Maven
reproduction failed all seven invariants. The implementation now rejects each incomplete artifact before planning and
preserves pending questions during import binding.

| Finding | Proven behavior | Owning boundary |
|---|---|---|
| FLOW-COMP-001 | A second occurrence of an already-bound catalog operation can be omitted while the draft remains `READY_FOR_PLAN` | Requirement draft capture |
| FLOW-COMP-002 | A positive fact can name a catalog operation absent from both `assembledText` and `RequirementFlow` | Requirement draft capture |
| FLOW-COMP-003 | Any approved upload disables the known-catalog-operation completeness check, including mixed upload and local-catalog flows | Requirement draft capture |
| FLOW-COMP-004 | All catalog operations can be present with transitions in the wrong business order | Requirement draft capture |
| FLOW-COMP-005 | A native-trigger `CAPABILITY` fact whose `sourceFactId` is absent from the flow is accepted and projected as a general requirement | Requirement flow validation |
| MAP-COMP-001 | One captured mapping rule marks the whole transition as mapped even when the approved text requests additional rules on the same hop | Requirement brief coverage |
| IMPORT-COMP-001 | `withBoundInteraction` promotes a draft to `READY_FOR_PLAN` and clears an existing open question when the final catalog binding arrives | Import-to-draft transition |

Root cause: readiness validators proved internal consistency against an incomplete captured artifact. The
completeness gate used only `assembledText`, reduced operation bindings to a set, skipped approved uploads, and did not
compare explicit sequence or mapping evidence. Import binding also derived readiness from bindings alone and discarded
open questions.

## Open defects

- REC-CAPTURE-001: immediate design-input contract rejection was forced into brief revision.
  A second owner fallback and the manual retry handler selected the previous producer.
  Runtime fix passed deterministic and live verification: five producer repairs preserved the approved brief.
- REC-ACTIVITY-001: the historical visibility defect remains unlocated. The new Sonnet SSE replay
  preserves discovery and analysis tools in the production parser/store; browser rendering is unverified.
- REC-PLAN-LIVE-001: deterministic fixes for the participant swap and invented error scope await a
  fresh Sonnet plan. The first rerun stopped safely in `design-input` after six invalid revisions;
  the next sample degraded in requirement capture and was interrupted without a brief or plan.
- REC-DRAFT-LIVE-002: Sonnet classified failure fields returned by the normal `onTaskResult` as an
  explicit error scope. The classification contract now requires `INLINE_RESPONSE` for that shape.
  A follow-up live sample is still required because the first post-fix sample entered an unrelated
  repeated invalid-tool-argument loop and replaced its draft preview with `test`.
- REC-RESTART-001: startup recovery exceeds the workflow transaction timeout and reports rollback.
  The artifact journal advances, but workflow persistence correctness remains unverified.

## Next queue

0. P0: repeat the Sonnet plan run with typed occurrence and corrected inline-response classification.
   Keep conversation `dfa3f2d4-6d6a-4e63-90ef-4eedd5be4543` as unapproved failure evidence.
   Reproduce REC-RESTART-001 with a model call longer than the workflow transaction timeout.

1. P0: run one Sonnet sample for omitted operation recovery, repeated operation recovery, and partial mapping recovery.
2. P1: add new deterministic cases only when live evidence exposes a new wording or topology boundary.
3. P2: keep the release gate on Sonnet until Luna becomes available from the configured provider.

## Latest verification

- Typed planning contract and clean-rebuild fix: 235 focused tests passed before the final same-operation regression.
- Final `ai-service` module suite: 4,009 tests, 0 failures, 0 errors, 4 skipped.
- The typed matrix covers entry points, distinct service-call occurrences, mappings, every region kind,
  behavior-owned scripts, catalog bindings, required support owners, and explicit dependency failures.
- Recovery carries stable typed finding identities, allows one clean model turn after local correction,
  opens the manual gate after a repeated defect, and refuses automatic rebuild after a catalog snapshot.

- 27473d76 pre-fix reproduction: 2 tests failed because generation ran once instead of twice.
- Expanded regression after the runtime fix: 629 tests, 0 failures, 0 errors, 2 skipped.
- Updated recovery gate: 150 tests, 0 failures, 0 errors, 0 skipped. Counts overlap with the expanded suite.
- Real capture/runtime matrix: 10 cases, including four recovery-decision variants, two repeated
  failures after restart, explicit brief questions and revisions, park, and zero repair budget.
- The catalog-write race test covers accepting an existing retry card before and after a write.
- Rejection text is preserved beyond the log preview limit. The design prompt delegates the retry
  budget to the runtime and describes server-owned anchors and scoped routes.
- Offline executor-eval and Maven package: PASS. No model calls or catalog writes. No files staged or committed.
- Original conversation remains `WAITING_FOR_INPUT`, revision 15, `materializedChainId=null`.
- Sonnet live verdict: FAIL for plan consistency; runtime recovery PASS. The run stops at
  `WAITING_FOR_APPROVAL`, design planning, revision 23, with no materialized chain.
  Five durable producer repairs preserve the approved brief. Planner calls: one. Evidence:
  `/tmp/27473d76-sonnet-live/` and `docs/no-ticket/capture-recovery-27473d76/sonnet-live.md`.
- Sonnet rerun `2d0b1009-fcad-44b5-b142-70fd621aa331`: six semantic revisions were rejected by
  real topology, mapping-site, failure-mode, and join checks. It stopped at `recovery-repeated`,
  revision 21, with the approved brief retained and no plan or materialized chain.
- First classifier post-fix sample `576e9f18-06fe-4f21-873c-1819dd1ef574` did not produce valid
  evidence: repeated `NEEDS_INPUT` calls omitted required questions, then sent `test`; the run was
  interrupted in requirement discovery with no committed brief or plan.
- Initial discovery/analysis SSE replay passes through the production parser/store: 30 step events,
  five tool rows, no missing parents. The 18 UI activity tests pass; browser rendering is unverified.
- REC-CAPTURE-002: the streaming Quarkus handler did not enforce the agent round-trip limit.
  Capture now uses synchronous `Result<String>` and native immediate tool return. The generated-agent
  regression fails both cases without that policy and passes both with it. Five schema tests also pass.
- REC-CAPTURE-003: generation lacked the allowed compiler element names and guessed unsupported types.
  The author prompt and unknown-type rejection now list values from the loaded compiler contract.
  The focused adapter, capability, and recovery suite passes 58 tests.
- Live plan review found two operation-to-participant swaps and an error scope absent from topology.
  No plan approval or catalog creation was sent. The original conversation was not sent a command.
- After the parallel user run finished, the service was restored to its original OpenAI Luna
  configuration; readiness is UP. The isolated test database was retained.
- Browser inspection found saved tool activity in another conversation, `OM to Salesforce WFM luna10-06`.
  The original conversation is absent from that browser's chat selector. This does not locate the
  loss in the original run.
- Logs: `/tmp/27473d76-expanded.log`, `/tmp/27473d76-recovery-gate.log`,
  `/tmp/27473d76-recovery-gate-summary.json`, `/tmp/27473d76-offline.log`, and `/tmp/27473d76-package.log`.
- Investigation and deviation matrix: `docs/no-ticket/capture-recovery-27473d76/investigation.md`.

- Duplicate imported operation fix: the focused regression first reproduced both `processTask` interactions binding
  to `sys-sf`. The fix now evaluates all imported candidates and uses the exact catalog path from linked requirement
  evidence. A third interaction without path evidence remains ambiguous and keeps the draft in `NEEDS_INPUT`.
- Post-fix regression: 90 tests, 0 failures, 0 errors, 0 skipped across uploaded-spec import, approval, chat decision,
  and catalog matching.
- Post-fix live verification: conversation `b4d1e50a-d78c-4cc9-ae71-dd2db895b8ba` imported one `INTERNAL` and one
  `EXTERNAL` OpenAPI specification. Both expose `processOrder`; the prompt used names that differ from `info.title`.
  The internal interaction bound to system `ec441c0b-9fa0-4896-94c7-261b10d521a3` and
  `POST /edge/internal/orders`; the external interaction bound to system
  `5158fa58-2395-4277-97a2-277746432b78` and `POST /edge/external/orders`. Evidence:
  `/tmp/rocky-postfix-pipeline.json` and `/tmp/rocky-duplicate-operation-systems.json`.
- Uploaded-spec alias boundary: two Sonnet runs used prompt names that differed from OpenAPI `info.title` values.
  The stricter run omitted canonical titles, operation ids, paths, and any alias warning. Sonnet recovered both
  canonical services and operations from the attachments, and the pipeline emitted the correct two binding hints.
  Evidence: `/tmp/rocky-spec-alias-evidence.json` and `/tmp/rocky-spec-alias-natural-evidence.json`.
- Duplicate imported operation boundary: one Sonnet run imported distinct `INTERNAL` and `EXTERNAL` specifications
  that both expose `processOrder`. The model captured the correct two participants and paths, but both binding hints
  pointed to the internal specification. Evidence: `/tmp/rocky-duplicate-operation-evidence.json`.
- Mixed uploaded-spec types: one live chat run imported two unique OpenAPI documents in one decision. Runtime catalog
  stored `Rocky Internal Orders 20260913T2228` as `INTERNAL` and `Rocky External Partner 20260913T2228` as `EXTERNAL`.
- The same run emitted two exact `CATALOG_BINDING_HINT` artifacts. Each hint preserved its own system, specification
  group, specification, operation, method, and path. Evidence: `/tmp/rocky-dual-spec.17FRzq/evidence-r2.json`.
- The first live attempt reused an existing `Rocky Orders API` system whose type was already `EXTERNAL`. The importer
  preserved the existing type and logged the requested `INTERNAL` mismatch. Use unique titles when testing type creation.
- Focused mixed-type import and binding test: 1 test, 0 failures, 0 errors, 0 skipped.
- Expanded binding, mapping, approval, and uploaded-spec regression: 317 tests, 0 failures, 0 errors, 2 skipped.
- Similar-place audit reproduction: 8 tests, 8 expected failures against the intended completeness invariants.
- Post-fix focused regression: 11 tests, 0 failures, 0 errors, 0 skipped. This includes all seven defects, wrong-hop
  mapping placement, and controls that prevent fact references or mapping headers from being counted as repeated calls.
- Related draft, flow, brief, discovery, upload, and mapping-gap regression: 174 tests, 0 failures, 0 errors, 0 skipped.
- Full `mvn -pl ai-service verify` reached the Testcontainers phase, then stalled while pulling the uncached
  `postgres:18` image. The run was stopped after the image made no progress; no test failure was reported before the
  stall.

- T-028 pre-fix regression reproduced `READY_FOR_PLAN` with an omitted `onTaskResult`; post-fix the same capture is
  stored as `NEEDS_INPUT` and names the missing operation.
- Requirement completeness gate: 58 focused tests passed. Covered omitted terminal, intermediate, and catalog-trigger
  operations; exact method/path references; fully bound flows; operation-like payload fields; negative exclusions; and
  uploaded-spec deferral.
- Related draft, brief, discovery, and upload suite: 166 tests, 0 failures, 0 errors, 0 skipped.
- Post-fix expanded deterministic suite: 287 tests, 0 failures, 0 errors, 2 skipped.
- Post-fix recovery suite: 101 tests, 0 failures, 0 errors, 0 skipped.
- Post-fix offline executor-eval validation: PASS.
- Post-fix live Rocky verification: incr74 and incr75 both reached Browser PASS and Catalog PASS on
  `claude-sonnet-5`.
- incr74 was the non-regression control: its first ready draft already contained `onTaskStart`, `createTask`, and
  `onTaskResult`; the completeness gate did not downgrade it.
- incr75 exercised the fix: the model attempted `READY_FOR_PLAN` while omitting `onTaskResult`; the completeness
  gate downgraded the capture to `NEEDS_INPUT`, named the exact missing catalog operation, and Rocky recaptured a
  complete draft in the same conversation. Final canvas bindings were OM `onTaskStart`, Salesforce WFM
  `createTask`, and OM `onTaskResult`.
- Live evidence: `~/.cursor/skills/rocky-catalog-eval/runs/run-incr74/` and
  `~/.cursor/skills/rocky-catalog-eval/runs/run-incr75/`.

- Focused mutation tests: 22 tests, 0 failures, 0 errors.
- Scoped topology matrix: 2 tests covering 3 valid compilations and 3 rejected binding swaps.
- Expanded deterministic suite: 287 tests, 0 failures, 0 errors, 2 skipped.
- Mutation combinations inside focused tests: 46 total.
- Three-occurrence binding permutations: 1 accepted and 5 rejected.
- Three-intent mapping permutations: 1 accepted and 5 rejected.
- Offline executor-eval validation: PASS.
- Current provider model discovery: Luna unavailable; Haiku and Sonnet available.
- Haiku smoke: 3 of 4 cases PASS; all 4 had `plannerCalls=0`.
- Haiku `repeated-operation-mappings`: two matching failures with
  `java.io.IOException: Connection was closed` before generator output.
- Sonnet control for the same complex case: PASS; 74,858 ms; `plannerCalls=0`.
- Approved Sonnet wave: 12 of 12 PASS across 4 cases and 3 repetitions per case;
  `plannerCalls=0`, no missing mapping ids, and no binding mismatches.
- Sonnet duration ranges: mapping 36,076–43,915 ms; repeated-operation mappings
  45,030–74,858 ms; retry/error handling 77,086–86,373 ms; two service calls
  48,503–49,625 ms.
- Combined Sonnet report: `/tmp/rocky-live-sonnet-20260912-combined-summary.json`.
- Raw reports: `/tmp/rocky-live-wave-20260912-smoke/summary.json`,
  `/tmp/rocky-live-haiku-20260912-smoke/summary.json`,
  `/tmp/rocky-live-haiku-20260912-retry/summary.json`, and
  `/tmp/rocky-live-sonnet-20260912-control/summary.json`.
- Existing PR gate: `.github/workflows/ai-service-build.yaml` delegates to
  `_maven-module-build.yaml`, which runs `mvn verify`; the new deterministic tests are included.
- Local pre-release gate: `ai-service/e2e/executor-eval/run-pre-release-sample.sh` checks service
  health, runs one `repeated-operation-mappings` sample, and rejects models other than Sonnet or
  Luna.
- Local gate syntax and offline harness checks: PASS.
- Local pre-release live run: 1 of 1 PASS on Sonnet; 56,098 ms; `plannerCalls=0`, no missing
  mapping ids, and no binding mismatches. Report:
  `/tmp/rocky-local-pre-release-20260913/summary.json`.
- Post-run Rocky health: `UP`.
- Deterministic recovery matrix: R-01 through R-12 covered; 101 tests, 0 failures, 0 errors, 0
  skipped; no LLM calls.
- L-03 live recovery: 2 of 2 pipelines reached `CHAIN_MATERIALIZED`; execution detected
  `CATALOG_RESOLUTION` and reopened `design-input`. Evidence:
  `/tmp/rocky-recovery-live-l03-20260913-r2-fresh/`.
- L-04 live recovery: 2 of 2 PASS; materialization detected `CONTRACT_SHAPE`, reopened
  `design-execution`, and completed with `reconcileMatches=true`. Evidence:
  `/tmp/rocky-recovery-live-l04-20260913-r4-fixed-runtime/`.
- L-05 live recovery: 2 of 2 PASS; both runs recorded `automatic-reopen:design-input`, followed by
  `automatic-reopen:design-execution`, then `CHAIN_MATERIALIZED` with `reconcileMatches=true`.
  Evidence: `/tmp/rocky-recovery-live-l05-20260913-r2/`.
- Evaluator 502 diagnosis: the provider rejected `response_format.type=json_object`, Pydantic
  numeric constraints, and `temperature` for `claude-sonnet-5`.
- Evaluator fix verification: 45 unit tests passed; a synthetic live `/evaluate` request returned
  valid scores for all four dimensions.
- Post-fix L-04 smoke: runtime recovery PASS with `automatic-reopen:design-execution`,
  `CHAIN_MATERIALIZED`, and `reconcileMatches=true`; semantic completeness was 2.
- The same smoke exposed REC-SEM-001 and REC-HARNESS-003. Root-cause report:
  `docs/no-ticket/recovery-routing/live-smoke-root-cause.md`.
- REC-SEM-001 and REC-HARNESS-003 verification: 18 planning tests, 42 offline gate runs, and 101
  recovery tests passed. Post-fix L-04 returned semantic scores 5/5/5/5 and verdict `PASS`.
  Evidence: `/tmp/rocky-recovery-live-l04-20260913-semantic-fixed/`.
- Full runner-contract suite: PASS, including semantic fail-closed behavior and all six active
  compare-and-patch scenarios.
- L-01 second campaign: one clean Sonnet run reached `CHAIN_MATERIALIZED`, retained chain
  `022dd00b-a171-4ef3-af46-0ba86c330d2f`, and scored 5/5/5/5. Evidence:
  `/tmp/rocky-recovery-live-l01-20260913-r2-pass/`.
- L-02 second campaign: one clean Sonnet run halted at `recovery-repeated` with
  `NAMED_STAGE_OUTSIDE_CANDIDATE_SET` and no materialization. Evidence:
  `/tmp/rocky-recovery-live-l02-20260913-r2-pass/`.
- Replace-subgraph: focused and expanded tests passed; 2 of 2 post-fix Sonnet runs retained chains with two
  scripts and no service call or error wrapper. Chain IDs: `9230d86e-5841-443b-bf01-b8c398895564` and
  `66bcc8e3-c990-4a1d-bf93-39ff070023a5`. Evidence:
  `/tmp/rocky-replace-subgraph-live-20260913-postfix/` and
  `/tmp/rocky-replace-subgraph-live-20260913-postfix-r2/`.
- Uploaded OpenAPI: 2 of 2 Sonnet pipelines reached `CHAIN_MATERIALIZED` with imported catalog identities,
  `POST /orders`, request mapping, and `reconcileMatches=true`. Semantic scores were 5/4/4/5 and 5/5/5/5.
  Evidence: `/tmp/rocky-uploaded-openapi-live-20260913-r3/` and
  `/tmp/rocky-uploaded-openapi-live-20260913-r4/`.
- Combined Java regression: 167 tests, 0 failures, 0 errors, 0 skipped.
- Functional semantic rejection and the full runner-contract suite: PASS.
- Recovery matrix and evidence: `docs/no-ticket/recovery-routing/test-matrix.md` and
  `docs/no-ticket/recovery-*/`.
- Commits and staging: none.
- Capture-to-brief catalog identity regression: a resolved occurrence whose recaptured participant
  or operation differs from its catalog match is normalized from the match before approval.
- Terminal failure-owner regression: `INLINE_RESPONSE` now requires a successor response
  interaction; the terminal delivery call remains `PROPAGATE` unless its own failure is requested.
- Final Sonnet control `15f88792-d87c-485e-8faa-444f6592d180`: reached implementation-plan
  approval at revision 17. `create-task` produced Salesforce WFM `createTask` with
  `INLINE_RESPONSE`; `task-result` produced OM `onTaskResult` with `PROPAGATE`. No chain was
  materialized. Evidence: `/tmp/27473d76-sonnet-live/postfix-06-final.json`.
