# Binding and mapping campaign commands

Run commands from the repository root unless stated otherwise.

## Focused mutation tests

```bash
mvn -pl ai-service \
  -Dtest=BindingMappingContractInvestigationTest,ChainSemanticMappingPlacementInvestigationTest,\
ScopedBindingMappingCompileTest \
  test
```

## Expanded deterministic suite

```bash
mvn -pl ai-service \
  -Dtest=BindingMappingContractInvestigationTest,ChainSemanticMappingPlacementInvestigationTest,\
ScopedBindingMappingCompileTest,\
ResolvedServiceCallBindingTest,ServiceCallBindingResolverTest,CreateRunBindingStoreTest,\
CatalogBindingMatcherTest,DefaultExecutorCatalogBindingAdapterTest,\
DefaultChainSemanticGraphCompilerTest,DefaultChainSemanticRevisionValidatorTest,\
ChainSemanticCaptureAdapterTest,BranchingSemanticRegionTest,ScopedSemanticRegionTest,\
SemanticMaterializationParityTest,BranchAndMultipleEntryMappingCompileTest,\
IndependentMappingBoundaryCompileTest,MappingCaptureValidatorTest,MappingContractGateTest,\
MappingExecutionSiteValidatorTest,MappingGenerationPipelineTest,MappingGeneratorContextTest,\
MappingMechanismSelectorTest,MappingParityValidatorTest,MappingSiteReconciliationTest,\
ScriptMappingCompileTest,SecureGroovyMappingCompilerTest,MappingBoundarySchemaResolverTest,\
RequirementBriefCoverageValidatorTest,ExecutorEvalFixtureTest \
  test
```

## Offline executor-eval validation

```bash
ai-service/e2e/executor-eval/test-offline.sh
```

## Immediate capture recovery

```bash
mvn -o -q -pl ai-service \
  -Dtest=DesignInputRecoveryTest,DesignInputCapabilityTest,ChainSemanticCaptureToolTest,\
ProductPipelineStageExecutorTest,RecoveryExecutorTest \
  -Djacoco.skip=true -Dcheckstyle.skip test
```

This suite includes the real capture and semantic validator, persisted retry evidence, and restart.

## Planner occurrence and topology contract

```bash
mvn -o -q -pl ai-service \
  -Dtest=DesignPlanProjectorTest,DesignPlanningCapabilityTest,\
CipDesignPlannerReportParserTest,DefaultDesignProcessSkillRunnerTest,\
RequirementBriefProjectorTest,ChainSemanticCaptureAdapterTest,\
DefaultChainSemanticRevisionValidatorTest,DefaultChainSemanticGraphCompilerTest \
  -Djacoco.skip=true -Dcheckstyle.skip test
```

Cover repeated catalog operations with distinct `serviceCallId` values, repeated references to one
occurrence, multiple producing steps, participant swaps, missing occurrence ids, invented error
scopes, and all three service-call failure modes.

## Generated agent boundary

```bash
mvn -pl ai-service -Dtest=ChainSemanticDesignAgentTest,ChainSemanticCaptureSchemaTest test
```

These Quarkus tests need an isolated test database or Docker for Dev Services. The model is mocked.
They check immediate tool return through the generated agent and the real capture argument mapper.

## Deterministic recovery gate

```bash
ai-service/e2e/product-pipeline/run-recovery-deterministic.sh
```

This gate covers recovery routing, per-cause budgets, restart durability, downstream
invalidation, catalog-write safety, and convergence to `CHAIN_MATERIALIZED` without calling an
LLM.

## Manual live recovery wave

Live recovery sends scenario prompts and generated pipeline artifacts to the configured external
model endpoint. Obtain explicit egress approval first. Run each representative scenario once:

```bash
ai-service/e2e/product-pipeline/run-quality-gate.sh \
  --scenario product-create-chain-recovery-design-input \
  --runs 1 \
  --knowledge-package integration-platform-skills/.apm/skills/cip-runtime-context-loader/assets/knowledge-export \
  --report-dir /tmp/rocky-recovery-live-l03 \
  --base-url http://localhost:8094

ai-service/e2e/product-pipeline/run-quality-gate.sh \
  --scenario product-create-chain-recovery-materialization-execution \
  --runs 1 \
  --knowledge-package integration-platform-skills/.apm/skills/cip-runtime-context-loader/assets/knowledge-export \
  --report-dir /tmp/rocky-recovery-live-l04 \
  --base-url http://localhost:8094

ai-service/e2e/product-pipeline/run-quality-gate.sh \
  --scenario product-create-chain-recovery-sequential \
  --runs 1 \
  --knowledge-package integration-platform-skills/.apm/skills/cip-runtime-context-loader/assets/knowledge-export \
  --report-dir /tmp/rocky-recovery-live-l05 \
  --base-url http://localhost:8094
```

Use only `claude-sonnet-5` or `gpt-5.6-luna`. These manual scenarios are excluded from the default
quality gate and must be selected explicitly.

## Low-token live sample

Configure the local service for `claude-sonnet-5` or `gpt-5.6-luna`, rebuild it after production
changes, verify its health, and run:

```bash
ai-service/e2e/executor-eval/run-executor-eval.sh \
  --runs 1 \
  --case repeated-operation-mappings \
  --report-dir /tmp/binding-mapping-eval-$(date +%Y%m%d)
```

Require `verdict=PASS`, `plannerCalls=0`, no missing mapping intent ids, and no binding mismatches.
Confirm the selected model is available before running. Use Sonnet when Luna is unavailable.
Record model-not-found and repeatable transport failures as environment evidence, and return the
service to its ordinary configuration after a temporary model override.

## Uploaded OpenAPI product path

With the application knowledge package already mounted and the product stack healthy, run:

```bash
ai-service/e2e/product-pipeline/run-product-scenario.sh \
  --scenario product-create-chain-uploaded-openapi-mapping \
  --rep 1 \
  --base-url http://localhost:8094 \
  --evaluator-url http://localhost:8100 \
  --report /tmp/rocky-uploaded-openapi/report.json
```

The scenario uploads its fixture and answers the typed import card automatically. Use two repetitions for the
campaign evidence. Require `CHAIN_MATERIALIZED`, `reconcileMatches=true`, non-blank imported catalog IDs,
`POST /orders`, and the `client`/`total` request mapping.

## Campaign evidence

- Executor fixtures and runner: `ai-service/e2e/executor-eval/`
- Investigation tests:
  `ai-service/src/test/java/org/qubership/integration/platform/ai/harness/BindingMappingContractInvestigationTest.java`
- Mapping placement mutations:
  `ai-service/src/test/java/org/qubership/integration/platform/ai/productpipeline/create/design/input/`
  `ChainSemanticMappingPlacementInvestigationTest.java`
- Reports: `docs/no-ticket/binding-mapping-eval/`

## Local pre-release sample

After rebuilding and starting `qip-ai-service`, run:

```bash
ai-service/e2e/executor-eval/run-pre-release-sample.sh
```

The command checks service health, runs `repeated-operation-mappings` once, rejects models other
than `claude-sonnet-5` or `gpt-5.6-luna`, and writes the report under `/tmp`. Override `BASE_URL`
or `REPORT_DIR` when needed.
