# Task 2 report: Route a mapping contract defect to the brief producer

Status: DONE
Commit: `0e3d57998` `fix(create-chain): choose the mapping owner in Java before advisory`

## What I implemented

Restored Java owner and next action for a brief-owned `MAPPING_CONTRACT` halt, before advisory
diagnosis:

- `HaltProducerCauseTable.ownerCategory(MAPPING_CONTRACT)` is `POLICY_OR_BRIEF`, same as
  `MISSING_BRIEF_FACTS`. `ProducerOwnedRecovery` then reopens `requirement-analysis`. A diagnosed
  owner such as `design-planning` does not win.
- Unknown-target repair instruction: `The field is absent from this contract. Correct the target
  in {role}.` It does not say "Add the missing…".
- `ProductPipelineStageExecutor` persists mapping evidence before reopen. `MAPPING_CONTRACT` skips
  `ASK_CLARIFICATION` and `recoverValidationFailure`. If reopen is not possible, the run waits
  guarded. It does not become `UNCLASSIFIED` and does not ask a clarification question.
- `RecoveryEvidence` keeps `schemaVersion` 1 and adds `producerStageId` after `observingStageId`.
  Compact construction defaults a null producer to `""`. The ten-argument constructor stays for
  legacy callers. `@JsonIgnoreProperties(ignoreUnknown = true)` keeps old JSON readable.
- Schema-side identity stays in finding JSON (`rawValidatorJson`). Brief identity is the run-scoped
  approved brief plus `consumedBriefArtifactId` on the finding. Conversation compilation owns
  schema sides.

Left tickets 03 (capture revalidation) and 04 (exhausted Edit requirements) alone.
`offersManualBriefEdit` still requires `isMissingBriefFacts()`. Task 1 transport is unchanged:
pipeline to `MappingContractBlockedException` to adapter `RecoveryCause.mappingContract`.

## What I tested

Focused Maven command (repo root):

```sh
mvn -f ai-service/pom.xml \
  -Dtest=BriefMappingValidatorTest,MappingContractGateTest,MappingGenerationPipelineTest,MappingContractProductionTransportTest,CipDesignExecutorJavaAdapterTest,HaltProducerCauseTableTest,ProductPipelineStageExecutorTest,ProducerOwnedRecoveryTest,RecoveryAttemptLedgerTest,RecoveryDecisionValidatorTest,RecoveryEvidenceTest,MissingCatalogBindingClarificationTest,BindingIdentityMismatchRecoveryTest,MissingBriefFactsExhaustedRecoveryTest,MappingContractBriefProducerRecoveryTest \
  test
```

Result: BUILD SUCCESS.

| Class | Tests |
| --- | --- |
| `BriefMappingValidatorTest` | 21 run, 0 fail |
| `MappingContractGateTest` | 8 run, 0 fail |
| `MappingGenerationPipelineTest` | 13 run, 0 fail, 2 skipped (mapper-2 off) |
| `MappingContractProductionTransportTest` | 4 run, 0 fail |
| `CipDesignExecutorJavaAdapterTest` | 20 run, 0 fail |
| `HaltProducerCauseTableTest` | 8 run, 0 fail |
| `ProductPipelineStageExecutorTest` | 107 run, 0 fail |
| `ProducerOwnedRecoveryTest` | 18 run, 0 fail |
| `RecoveryAttemptLedgerTest` | 12 run, 0 fail |
| `RecoveryDecisionValidatorTest` | 17 run, 0 fail |
| `RecoveryEvidenceTest` | 7 run, 0 fail |
| `MissingCatalogBindingClarificationTest` | 8 run, 0 fail |
| `BindingIdentityMismatchRecoveryTest` | 3 run, 0 fail |
| `MissingBriefFactsExhaustedRecoveryTest` | 9 run, 0 fail |
| `MappingContractBriefProducerRecoveryTest` | 6 run, 0 fail |

Also ran `RecoveryContextProjectorTest` and `OwnerCandidateSetTest` (34 run, 0 fail) after the
`producerStageId` projector change.

No live evals or paid model calls.

## TDD evidence

### RED

Unit tests first, against Task 1's unspecified mapping category:

```
HaltProducerCauseTableTest.mappingContractSelectsTheBriefProducerCategory
  expected: <POLICY_OR_BRIEF> but was: <UNSPECIFIED>

HaltProducerCauseTableTest.mappingContractInstructionSaysTheFieldIsAbsentFromThisContract
  expected true that sentence contains "absent from this contract"; sentence was empty

ProducerOwnedRecoveryTest.mappingContractReopensTheBriefProducer
  expected producer requirement-analysis; diagnosed design-planning won

RecoveryEvidenceTest.mappingDiagnosticRoundTripsObservingProducerSchemaAndJsonPayload
  no producerStageId on the record
```

Orchestrator compile then failed twice before GREEN: `List.of("failure-1")` was passed as
`proposedBriefChanges`, and the first copy lived under `...runtime` where engine constructors are
package-private. The test now lives in `...productpipeline.create`. A first runtime attempt used
`approve()` / `acceptInput()`, which continued the orchestrator loop (`expected stage design-input
but run is at requirement-analysis`). Halt setup now uses `recordApprove` / `recordInput`.

### GREEN

After category, instruction, evidence field, persist-before-reopen, and the mapping skip of
advisory:

- Unit mapping tests: 5 new/rewritten methods pass.
- `MappingContractBriefProducerRecoveryTest`: 6 tests pass.
- Focused suite above: BUILD SUCCESS.

R1 goes through production transport: `MappingGenerationPipeline` inside the real adapter, then
`result.recoveryCause()` into `StageOutcome`. The capability does not mint `RecoveryCause`. R5
injects the same cause for advisory conflict cases (`MISSING`, `ASK_USER`, `PARK`, `REGENERATE`,
`INVALID`). Advisory is never called (`agent.lastRecoveryContextJson == null`).

## Files changed

New:

- `MappingContractBriefProducerRecoveryTest.java`

Extended:

- `HaltProducerCauseTable.java` (category + unknown-target instruction)
- `ProducerOwnedRecovery.java` (no logic change once category is `POLICY_OR_BRIEF`)
- `RecoveryEvidence.java`, `RecoveryContextProjector.java`
- `ProductPipelineStageExecutor.java` (persist, skip advisory, guarded wait)
- `HaltProducerCauseTableTest.java`, `ProducerOwnedRecoveryTest.java`, `RecoveryEvidenceTest.java`

## Self-review

- R1 uses production transport. R5 may inject the cause. A minted-cause capability does not satisfy
  R1.
- Conflicting valid advisory cannot replace `MAPPING_CONTRACT`, the brief producer, or reopen.
- No clarification question. `ASK_USER` is not a public outcome for this cause.
- P1: observing stage, producer, schema-side identity, and JSON payload round-trip. Schema version
  stays 1.
- `requirementAnalysisDoesNotOwnMappingContract` still holds: design-execution emits the cause;
  requirement-analysis owns the repair.
- Did not convert mapping failures to `MISSING_BRIEF_FACTS`.
- Did not admit `MAPPING_CONTRACT` onto `offersManualBriefEdit`.
- Did not add a second halt card.
- Rec03 / full29 remain probes. `$.preserved.executionId` is a fixture path, not a product
  exception.
- New comments and the commit message are US English with no em dashes.

## Issues or concerns

1. `persistMappingContractEvidence` stores `artifactStore.latest(REQUIREMENT_BRIEF)` as
   `approvedBriefRef`. Consumed brief identity for the rejected mapping lives on finding
   `mappingDetails`, not as a second run-scoped schema ref. That matches the spec split (brief on
   the run, schema sides under conversation compilation).
2. When reopen is not possible, the mapping path waits guarded. Ticket 04 still owns the exhausted
   Edit-requirements card. This task does not offer that card for `MAPPING_CONTRACT`.
3. `MappingGenerationPipelineTest` still skips two mapper-2 tests. Unrelated to owner restore.

## Coverage

| ID | Result |
| --- | --- |
| R1 | Pass. Production adapter transport reopens `requirement-analysis` with typed findings and consumed brief identity. Cause stays `MAPPING_CONTRACT`. |
| R5 | Pass. Missing, `ASK_USER`, `PARK`, `REGENERATE_ARTIFACT`, and invalid advisory keep `MAPPING_CONTRACT`, the brief producer, and reopen. |
| No ASK_USER | Pass. Decision is `ReopenProducer`. Advisory JSON is never written. |
| P1 | Pass. Restore keeps observing stage, producer, schema-side identity, and JSON diagnostic payload. |
| B1 | Pass. `MissingCatalogBindingClarificationTest` (8). |
| I1 | Pass. `BindingIdentityMismatchRecoveryTest` (3) plus leftover-hint coverage in `CipDesignExecutorJavaAdapterTest` (20). |
| E1 | Pass. `MissingBriefFactsExhaustedRecoveryTest` (9). |
| Tickets 03 and 04 | Out of scope. Not implemented. |

## Fix pass

Status: DONE

Implemented both review findings:

- `MAPPING_CONTRACT` no longer falls back to `planProducerStageId`. The runtime resolves the
  producer from the consumed brief revision's provenance and passes it to
  `ProducerOwnedRecovery`. If that exact producer is unavailable, only a brief producer from the
  closed candidate set can win; a plan-stage or diagnosed owner cannot.
- `persistMappingContractEvidence` now stores the `consumedBriefArtifactId` and
  `consumedBriefContentHash` from `mappingDetails` as `approvedBriefRef`. It does not read
  `artifactStore.latest(REQUIREMENT_BRIEF)`.

### TDD evidence

RED:

- `MappingContractBriefProducerRecoveryTest`: 7 failures because the plan producer won and the
  runtime returned `WaitForInput` instead of reopening `requirement-analysis`.
- `ProducerOwnedRecoveryTest#mappingContractDoesNotFallBackToThePlanProducer`: expected `PARK`,
  got `REOPEN_UPSTREAM`.
- After the owner fix, the compiled-revision test expected R1's artifact ID but durable evidence
  contained R2's artifact ID.

GREEN:

```sh
mvn -f ai-service/pom.xml \
  -Dtest=HaltProducerCauseTableTest,ProducerOwnedRecoveryTest,RecoveryEvidenceTest,ProductPipelineStageExecutorTest,MappingContractBriefProducerRecoveryTest,MissingCatalogBindingClarificationTest,BindingIdentityMismatchRecoveryTest,MissingBriefFactsExhaustedRecoveryTest \
  test
```

Result: `BUILD SUCCESS`; 168 tests run, 0 failures, 0 errors, 0 skipped.

### Self-review

- The owner regression uses a profile where the closed candidate set exposes a plan producer but
  not the brief producer. The asserted reopened stage is `requirement-analysis`, resolved from R1
  provenance.
- The durable-evidence regression adds a newer R2 after the attempt captured R1. Persisted
  `approvedBriefRef` matches R1's artifact ID and content hash and does not match R2.
- Recovery evidence remains schema version 1. Tickets 03 and 04, exception-prose parsing, and
  `MISSING_BRIEF_FACTS` routing remain unchanged.
- No functional concerns.

## Fix pass 2

Status: DONE

Validated consumed-brief provenance against a brief producer declared by the pipeline profile or
closed candidate set. A non-brief provenance stage such as `design-planning` no longer wins.
Recovery falls back to the profile brief producer, usually `requirement-analysis`.

### TDD evidence

RED:

- `ProducerOwnedRecoveryTest#mappingContractIgnoresNonBriefConsumedProvenance`: expected
  `requirement-analysis`, but got `design-planning`.

GREEN:

```sh
mvn -f ai-service/pom.xml \
  -Dtest=HaltProducerCauseTableTest,ProducerOwnedRecoveryTest,RecoveryEvidenceTest,ProductPipelineStageExecutorTest,MappingContractBriefProducerRecoveryTest,MissingCatalogBindingClarificationTest,BindingIdentityMismatchRecoveryTest,MissingBriefFactsExhaustedRecoveryTest \
  test
```

Result: `BUILD SUCCESS`; 169 tests run, 0 failures, 0 errors, 0 skipped.

### Self-review

- The regression fixture uses consumed provenance `design-planning` while the candidates include
  the `requirement-analysis` brief producer.
- `productionTransportReopensTheBriefProducer` applies the `ReopenProducer` lifecycle decision and
  asserts `currentStageId()` is `requirement-analysis` with `RunStatus.RUNNING`.
- Tickets 03 and 04, `MISSING_BRIEF_FACTS`, schema version, and exception-prose parsing remain
  unchanged.
- No functional concerns.
