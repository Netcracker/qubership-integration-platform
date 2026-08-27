package org.qubership.integration.platform.ai.productpipeline.create.design.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.AppendCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Revision;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerNodeExecutionMode;
import org.qubership.integration.platform.ai.plan.ImplementationPlan;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.productpipeline.artifact.ApprovalRecordV2;
import org.qubership.integration.platform.ai.productpipeline.artifact.ArtifactProvenance;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerRunPin;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerValidationBundle;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerValidationPass;
import org.qubership.integration.platform.ai.productpipeline.artifact.GraphAssemblyResult;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationResult;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerDag;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerNode;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.capability.StageRepairEvidence;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerDagExecutionResult;
import org.qubership.integration.platform.ai.productpipeline.create.FailureNarrative;
import org.qubership.integration.platform.ai.productpipeline.create.PlanningPatchLedger;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.CipDesignExecutorJavaAdapter.ExecutionInputs;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.CipDesignExecutorJavaAdapter.ExecutionResult;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingResolution;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignExecutionPlan;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanReport;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.IdsDocument;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignExecutionResult;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.MaterializationRequest;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticFixtures;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.ValidatedExecutionBundle;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;
import org.qubership.integration.platform.ai.productpipeline.materialization.MaterializationPhase;
import org.qubership.integration.platform.ai.productpipeline.materialization.MaterializationResult;
import org.qubership.integration.platform.ai.productpipeline.profile.ApprovalPolicy;
import org.qubership.integration.platform.ai.qipknowledge.validation.CompilerPlanValidator;
import org.qubership.integration.platform.ai.qipknowledge.validation.PlanGraphValidationInput;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationIssue;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationResult;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationSeverity;

class CipDesignExecutorJavaAdapterTest {

  private static final Instant FIXED = Instant.parse("2026-07-30T12:30:00Z");
  private static final String RUN_ID = "run-adapter-1";
  private static final String CONVERSATION_ID = "conv-adapter-1";

  private ProductPipelineArtifactStore artifactStore;
  private ApprovedCompilerExecutionRunner runner;
  private ExecutorCatalogBindingAdapter bindingAdapter;
  private CompilerPlanValidator planValidator;
  private CipDesignExecutorJavaAdapter adapter;

  private Reference idsRef;
  private Reference revisionRef;
  private Reference reportRef;
  private Reference planRef;
  private Reference implementationRef;
  private Reference manifestRef;
  private Reference approvalRef;

  private DesignExecutionPlan approvedPlan;
  private ChainSemanticRevision revision;
  private RunManifest manifest;
  private List<CatalogBindingResolution> bindings;
  private DesignPlanReport report;
  private ApprovalRecordV2 approval;

  @BeforeEach
  void setUp() {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    CompilationArtifacts artifacts =
        new CompilationArtifacts(
            new InMemoryArtifactBlobStore(), mapper, Clock.fixed(FIXED, ZoneOffset.UTC));
    artifactStore = new ProductPipelineArtifactStore(artifacts);
    runner = mock(ApprovedCompilerExecutionRunner.class);
    bindingAdapter = mock(ExecutorCatalogBindingAdapter.class);
    planValidator = mock(CompilerPlanValidator.class);
    when(planValidator.validate(any(PlanGraphValidationInput.class)))
        .thenReturn(new ValidationResult(true, List.of(), "ok"));
    adapter = new CipDesignExecutorJavaAdapter(runner, bindingAdapter, artifactStore, planValidator);

    revision = sampleRevision();
    approvedPlan = samplePlan(List.of("cip-trigger-generator"), "catalog-hash", "addon-hash-trigger");
    manifest = sampleManifest("catalog-hash", "skill-hash-trigger", "addon-hash-trigger");
    bindings = List.of(sampleBinding());
    report = new DesignPlanReport("1", "# plan\n");

    idsRef = append(Kind.IDS_DOCUMENT, "1", sampleIds());
    revisionRef =
        append(Kind.CHAIN_SEMANTIC_REVISION, ChainSemanticRevision.CURRENT_SCHEMA_VERSION, revision);
    reportRef = append(Kind.DESIGN_PLAN_REPORT, "1", report);
    planRef = append(Kind.DESIGN_EXECUTION_PLAN, "1", approvedPlan);
    implementationRef = append(Kind.IMPLEMENTATION_PLAN, "1", new ImplementationPlan("plan text"));
    manifestRef = append(Kind.RUN_MANIFEST, "1", manifest);
    approval =
        new ApprovalRecordV2(
            implementationRef,
            implementationRef.contentHash(),
            List.of(idsRef, revisionRef, reportRef, planRef, implementationRef),
            "tester",
            "approved",
            FIXED,
            ApprovalPolicy.CATALOG_FIRST_V1,
            ApprovalPolicy.CATALOG_FIRST_V1_HASH,
            null,
            null,
            null,
            null,
            null,
            null);
    approvalRef = append(Kind.APPROVAL_RECORD, "2", approval);

    when(bindingAdapter.resolve(eq(CONVERSATION_ID), eq(revision), anyList(), any()))
        .thenReturn(List.of(new BindingResolutionResult.Resolved(bindings.getFirst())));
    when(runner.execute(eq(approvedPlan), eq(revision), eq(bindings), eq(manifest), any()))
        .thenReturn(successfulEngineResult(List.of("cip-trigger-generator")));
  }

  @Test
  void hashMismatchDoesNotInvokeRunner() {
    Reference staleReport = append(Kind.DESIGN_PLAN_REPORT, "1", new DesignPlanReport("1", "# other\n"));
    ExecutionResult result = adapter.executeAfterApproval(baseInputs().withReportRef(staleReport));

    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, result.outcomeClass());
    assertTrue(result.message().toLowerCase().contains("report"));
    verifyNoInteractions(runner);
  }

  @Test
  void projectionHashMismatchDoesNotInvokeRunner() {
    DesignExecutionPlan otherPlan =
        samplePlan(List.of("cip-trigger-generator"), "catalog-hash", "addon-hash-trigger");
    Reference stalePlan = append(Kind.DESIGN_EXECUTION_PLAN, "1", otherPlan);
    ExecutionResult result =
        adapter.executeAfterApproval(baseInputs().withPlan(otherPlan, stalePlan));

    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, result.outcomeClass());
    assertTrue(result.message().toLowerCase().contains("projection")
        || result.message().toLowerCase().contains("execution plan"));
    verifyNoInteractions(runner);
  }

  @Test
  void catalogHashMismatchDoesNotInvokeRunner() {
    DesignExecutionPlan badPlan =
        samplePlan(List.of("cip-trigger-generator"), "other-catalog", "addon-hash-trigger");
    ExecutionResult result =
        adapter.executeAfterApproval(baseInputs().withPlan(badPlan, planRef));

    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, result.outcomeClass());
    assertTrue(result.message().toLowerCase().contains("catalog"));
    verifyNoInteractions(runner);
  }

  @Test
  void addonHashMismatchDoesNotInvokeRunner() {
    DesignExecutionPlan badPlan =
        samplePlan(List.of("cip-trigger-generator"), "catalog-hash", "other-addon");
    ExecutionResult result =
        adapter.executeAfterApproval(baseInputs().withPlan(badPlan, planRef));

    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, result.outcomeClass());
    assertTrue(result.message().toLowerCase().contains("addon"));
    verifyNoInteractions(runner);
  }

  @Test
  void skillClosureMismatchDoesNotInvokeRunner() {
    DesignExecutionPlan badPlan =
        samplePlan(List.of("cip-script-generator"), "catalog-hash", "addon-hash-trigger");
    ExecutionResult result =
        adapter.executeAfterApproval(baseInputs().withPlan(badPlan, planRef));

    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, result.outcomeClass());
    assertTrue(
        result.message().toLowerCase().contains("skill")
            || result.message().toLowerCase().contains("closure"));
    verifyNoInteractions(runner);
  }

  @Test
  void stepOrderMismatchDoesNotInvokeRunner() {
    DesignExecutionPlan badPlan =
        new DesignExecutionPlan(
            "1",
            "revision-orders",
            "cip-design-planner",
            "chain-semantic-revision/revision-orders",
            "design-input-hash",
            "2024.4",
            ApprovalPolicy.CATALOG_FIRST_V1,
            List.of(
                new DesignExecutionPlan.Step(
                    "step-2-cip-trigger-generator",
                    2,
                    "Second first",
                    DesignExecutionPlan.OwnerKind.SKILL,
                    List.of("cip-trigger-generator"),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of("GRAPH_PATCH_ARTIFACT")),
                new DesignExecutionPlan.Step(
                    "step-1-cip-trigger-generator",
                    1,
                    "First second",
                    DesignExecutionPlan.OwnerKind.SKILL,
                    List.of("cip-trigger-generator"),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of("GRAPH_PATCH_ARTIFACT"))),
            "design-plan-report",
            "report-content-hash",
            Map.of("cip-trigger-generator", "skill-hash-trigger"),
            Map.of("cip-trigger-generator", "addon-hash-trigger"),
            "catalog-hash",
            ApprovalPolicy.CATALOG_FIRST_V1_HASH);
    ExecutionResult result =
        adapter.executeAfterApproval(baseInputs().withPlan(badPlan, planRef));

    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, result.outcomeClass());
    assertTrue(result.message().toLowerCase().contains("order")
        || result.message().toLowerCase().contains("ordinal"));
    verifyNoInteractions(runner);
  }

  @Test
  void mappingIntentMismatchDoesNotInvokeRunner() {
    ExecutionResult result =
        adapter.executeAfterApproval(
            baseInputs().withRevision(SemanticFixtures.linearOrdersWithMapping(), revisionRef));

    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, result.outcomeClass());
    assertTrue(result.message().toLowerCase().contains("mapping"));
    verifyNoInteractions(runner);
  }

  @Test
  void engineSkillOutsideClosureIsRejected() {
    when(runner.execute(eq(approvedPlan), eq(revision), eq(bindings), eq(manifest), any()))
        .thenReturn(successfulEngineResult(List.of("cip-script-generator")));

    ExecutionResult result = adapter.executeAfterApproval(baseInputs());

    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, result.outcomeClass());
    assertTrue(result.message().toLowerCase().contains("outside"));
  }

  @Test
  void mandatoryDependencyInsideScopedClosureIsAccepted() {
    DesignExecutionPlan planWithDependencyOwner =
        samplePlan(List.of("cip-trigger-generator"), "catalog-hash", "addon-hash-trigger");
    RunManifest manifestWithDependency =
        sampleManifestWithDependency(
            "catalog-hash",
            "skill-hash-trigger",
            "addon-hash-trigger",
            "cip-naming-generator",
            "skill-hash-naming",
            "addon-hash-naming");
    Reference depPlanRef = append(Kind.DESIGN_EXECUTION_PLAN, "1", planWithDependencyOwner);
    Reference depManifestRef = append(Kind.RUN_MANIFEST, "1", manifestWithDependency);
    ApprovalRecordV2 depApproval =
        new ApprovalRecordV2(
            implementationRef,
            implementationRef.contentHash(),
            List.of(idsRef, revisionRef, reportRef, depPlanRef, implementationRef),
            "tester",
            "approved",
            FIXED,
            ApprovalPolicy.CATALOG_FIRST_V1,
            ApprovalPolicy.CATALOG_FIRST_V1_HASH,
            null,
            null,
            null,
            null,
            null,
            null);
    Reference depApprovalRef = append(Kind.APPROVAL_RECORD, "2", depApproval);

    when(runner.execute(eq(planWithDependencyOwner), eq(revision), eq(bindings), eq(manifestWithDependency), any()))
        .thenReturn(
            successfulEngineResult(List.of("cip-naming-generator", "cip-trigger-generator")));

    ExecutionInputs inputs =
        new ExecutionInputs(
            RUN_ID,
            CONVERSATION_ID,
            depApprovalRef,
            depApproval,
            report,
            reportRef,
            planWithDependencyOwner,
            depPlanRef,
            revision,
            revisionRef,
            sampleIds(),
            idsRef,
            new ImplementationPlan("plan text"),
            implementationRef,
            manifestWithDependency,
            depManifestRef,
            List.of(),
            null,
            null,
            null);

    ExecutionResult result = adapter.executeAfterApproval(inputs);

    assertEquals(StageOutcomeClass.CANDIDATE, result.outcomeClass());
    assertEquals(DesignExecutionPhase.WAITING_FOR_MATERIALIZATION, result.checkpoint().phase());
  }

  @Test
  void skillOutsideScopedClosureStillRejectedWhenDependenciesPresent() {
    RunManifest manifestWithDependency =
        sampleManifestWithDependency(
            "catalog-hash",
            "skill-hash-trigger",
            "addon-hash-trigger",
            "cip-naming-generator",
            "skill-hash-naming",
            "addon-hash-naming");
    Reference depManifestRef = append(Kind.RUN_MANIFEST, "1", manifestWithDependency);
    when(runner.execute(eq(approvedPlan), eq(revision), eq(bindings), eq(manifestWithDependency), any()))
        .thenReturn(successfulEngineResult(List.of("cip-script-generator")));

    ExecutionInputs inputs =
        new ExecutionInputs(
            RUN_ID,
            CONVERSATION_ID,
            approvalRef,
            approval,
            report,
            reportRef,
            approvedPlan,
            planRef,
            revision,
            revisionRef,
            sampleIds(),
            idsRef,
            new ImplementationPlan("plan text"),
            implementationRef,
            manifestWithDependency,
            depManifestRef,
            List.of(),
            null,
            null,
            null);

    ExecutionResult result = adapter.executeAfterApproval(inputs);

    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, result.outcomeClass());
    assertTrue(result.message().toLowerCase().contains("outside"));
  }

  @Test
  void approvedExecutionInvokesRunnerAndCheckpointsWaitingForMaterialization() {
    ExecutionResult result = adapter.executeAfterApproval(baseInputs());

    assertEquals(StageOutcomeClass.CANDIDATE, result.outcomeClass());
    verify(runner).execute(eq(approvedPlan), eq(revision), eq(bindings), eq(manifest), any());
    assertEquals(DesignExecutionPhase.WAITING_FOR_MATERIALIZATION, result.checkpoint().phase());
  }

  @Test
  void phase5ValidationUsesStoreBackedNestedRefs() {
    ExecutionResult result = adapter.executeAfterApproval(baseInputs());

    assertEquals(StageOutcomeClass.CANDIDATE, result.outcomeClass());
    ValidatedExecutionBundle validated =
        result.candidates().stream()
            .filter(candidate -> candidate.kind() == Kind.VALIDATED_EXECUTION_BUNDLE)
            .map(candidate -> (ValidatedExecutionBundle) candidate.payload())
            .findFirst()
            .orElseThrow();

    assertTrue(artifactStore.get(RUN_ID, validated.planValidationRef()).isPresent());
    assertTrue(artifactStore.get(RUN_ID, validated.graphValidationRef()).isPresent());
    assertTrue(artifactStore.get(RUN_ID, validated.compilerValidationRef()).isPresent());
    assertTrue(artifactStore.get(RUN_ID, validated.executorValidationRef()).isPresent());
    assertTrue(artifactStore.get(RUN_ID, validated.graphRef()).isPresent());
    assertTrue(artifactStore.get(RUN_ID, validated.orderedGraphPatchesRef()).isPresent());
    assertNotEquals("plan-validation", validated.planValidationRef().artifactId());
    assertNotEquals("graph-validation", validated.graphValidationRef().artifactId());
    assertNotEquals(sha256Hex("plan-ok"), validated.planValidationRef().contentHash());
    assertNotEquals(sha256Hex("graph-ok"), validated.graphValidationRef().contentHash());

    MaterializationRequest request =
        result.candidates().stream()
            .filter(candidate -> candidate.kind() == Kind.MATERIALIZATION_REQUEST)
            .map(candidate -> (MaterializationRequest) candidate.payload())
            .findFirst()
            .orElseThrow();
    assertEquals(
        request.validatedExecutionBundleRef().contentHash(),
        artifactStore
            .get(RUN_ID, request.validatedExecutionBundleRef())
            .orElseThrow()
            .contentHash());
    // Produce-path re-append keeps contentHash; artifactId may differ from the store-backed ref.
    Reference reproduced = append(Kind.VALIDATED_EXECUTION_BUNDLE, "1", validated);
    assertNotEquals(request.validatedExecutionBundleRef().artifactId(), reproduced.artifactId());
    assertEquals(request.validatedExecutionBundleRef().contentHash(), reproduced.contentHash());
  }

  @Test
  void phase5ValidationFailureDoesNotEmitValidatedBundle() {
    when(planValidator.validate(any(PlanGraphValidationInput.class)))
        .thenReturn(
            new ValidationResult(
                false,
                List.of(),
                "plan validation failed"));

    ExecutionResult result = adapter.executeAfterApproval(baseInputs());

    assertEquals(StageOutcomeClass.VALIDATION_FAILURE, result.outcomeClass());
    assertTrue(
        result.candidates().stream()
            .noneMatch(candidate -> candidate.kind() == Kind.VALIDATED_EXECUTION_BUNDLE));
    assertTrue(
        result.candidates().stream()
            .noneMatch(candidate -> candidate.kind() == Kind.MATERIALIZATION_REQUEST));
  }

  @Test
  void phase5ValidationFailureKeepsTheGraphItRejected() {
    when(planValidator.validate(any(PlanGraphValidationInput.class)))
        .thenReturn(new ValidationResult(false, List.of(), "plan validation failed"));

    ExecutionResult result = adapter.executeAfterApproval(baseInputs());

    assertEquals(StageOutcomeClass.VALIDATION_FAILURE, result.outcomeClass());
    assertTrue(
        result.candidates().stream()
            .anyMatch(candidate -> candidate.kind() == Kind.CHAIN_PLAN_GRAPH),
        "a retry cannot correct a step it is not shown");
  }

  @Test
  void ineligibleCompilerBundlePutsMergedFindingsOnFailureResult() {
    when(runner.execute(eq(approvedPlan), eq(revision), eq(bindings), eq(manifest), any()))
        .thenReturn(ineligibleCompilerEngineResult());

    ExecutionResult result = adapter.executeAfterApproval(baseInputs());

    assertEquals(StageOutcomeClass.VALIDATION_FAILURE, result.outcomeClass());
    PlanValidationResult validation =
        result.candidates().stream()
            .filter(candidate -> candidate.kind() == Kind.PLAN_VALIDATION_RESULT)
            .map(candidate -> (PlanValidationResult) candidate.payload())
            .findFirst()
            .orElseThrow();
    assertFalse(validation.approvalEligible());
    assertTrue(
        validation.findings().stream()
            .anyMatch(
                finding ->
                    finding.message() != null && finding.message().contains("http-trigger")));
    String findingsText = FailureNarrative.findingsText(result.candidates());
    assertFalse(findingsText.isBlank());
    assertTrue(findingsText.contains("http-trigger"));
  }

  @Test
  void completePhase6AdvancesCheckpointFromWaitingToComplete() {
    ExecutionResult phase5 = adapter.executeAfterApproval(baseInputs());
    assertEquals(DesignExecutionPhase.WAITING_FOR_MATERIALIZATION, phase5.checkpoint().phase());

    MaterializationRequest request =
        phase5.candidates().stream()
            .filter(candidate -> candidate.kind() == Kind.MATERIALIZATION_REQUEST)
            .map(candidate -> (MaterializationRequest) candidate.payload())
            .findFirst()
            .orElseThrow();
    Reference snapshotRef =
        append(
            Kind.CATALOG_CHAIN_SNAPSHOT,
            "1",
            Map.of("chainId", "catalog-chain-1"));
    Reference reconcileRef =
        append(
            Kind.RECONCILE_RESULT,
            "1",
            new org.qubership.integration.platform.ai.skill.orchestration.ReconcileResult(
                true, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "ok"));
    MaterializationResult materializationResult =
        new MaterializationResult(
            1,
            "catalog-chain-1",
            new org.qubership.integration.platform.ai.integration.catalog.materialize.MaterializationMap(
                "catalog-chain-1", Map.of("trigger", "el-1")),
            request.graphDigest(),
            MaterializationPhase.COMPLETE);

    ExecutionResult phase6 =
        adapter.completePhase6(RUN_ID, request, materializationResult, snapshotRef, reconcileRef);

    assertEquals(StageOutcomeClass.SUCCEEDED, phase6.outcomeClass());
    assertEquals(DesignExecutionPhase.COMPLETE, phase6.checkpoint().phase());
    assertTrue(
        phase6.candidates().stream()
            .anyMatch(candidate -> candidate.kind() == Kind.DESIGN_EXECUTION_RESULT));
    DesignExecutionCheckpoint stored =
        artifactStore
            .latest(RUN_ID, Kind.DESIGN_EXECUTION_CHECKPOINT)
            .map(revision -> artifactStore.payload(revision, DesignExecutionCheckpoint.class))
            .orElseThrow();
    assertEquals(DesignExecutionPhase.COMPLETE, stored.phase());
  }

  @Test
  void completePhase6RejectsWhenCheckpointIsNotWaiting() {
    MaterializationRequest request =
        new MaterializationRequest(
            "1",
            approvalRef,
            reportRef,
            planRef,
            "graph-digest",
            sha256Hex("[]"),
            new Reference(Kind.VALIDATED_EXECUTION_BUNDLE, "bundle", "hash"));
    MaterializationResult materializationResult =
        new MaterializationResult(
            1,
            "catalog-chain-1",
            new org.qubership.integration.platform.ai.integration.catalog.materialize.MaterializationMap(
                "catalog-chain-1", Map.of()),
            "graph-digest",
            MaterializationPhase.COMPLETE);
    Reference snapshotRef = append(Kind.CATALOG_CHAIN_SNAPSHOT, "1", Map.of("chainId", "c1"));
    Reference reconcileRef =
        append(
            Kind.RECONCILE_RESULT,
            "1",
            new org.qubership.integration.platform.ai.skill.orchestration.ReconcileResult(
                true, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "ok"));

    ExecutionResult phase6 =
        adapter.completePhase6(RUN_ID, request, materializationResult, snapshotRef, reconcileRef);

    assertEquals(StageOutcomeClass.VALIDATION_FAILURE, phase6.outcomeClass());
    assertTrue(
        artifactStore.latest(RUN_ID, Kind.DESIGN_EXECUTION_CHECKPOINT).isEmpty()
            || artifactStore
                .latest(RUN_ID, Kind.DESIGN_EXECUTION_CHECKPOINT)
                .map(revision -> artifactStore.payload(revision, DesignExecutionCheckpoint.class))
                .map(DesignExecutionCheckpoint::phase)
                .filter(phase -> phase == DesignExecutionPhase.COMPLETE)
                .isEmpty());
  }

  @Test
  void repairTurnRoutesHaltEvidenceAndPriorGraphToTheRunner() {
    StageRepairEvidence repairEvidence =
        new StageRepairEvidence(
            "VALIDATION_FAILURE", "design-execution", "http-trigger-1: schema violation", "", "use RBAC");
    ChainPlanGraph priorGraph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("chain-1", "Chain"),
            List.of(new ChainPlanNode("trigger", "http-trigger", "Trigger", null, null, List.of())),
            List.of());
    when(runner.execute(
            eq(approvedPlan),
            eq(revision),
            eq(bindings),
            eq(manifest),
            eq("attempt-1"),
            eq(repairEvidence),
            eq(priorGraph),
            any()))
        .thenReturn(successfulEngineResult(List.of("cip-trigger-generator")));

    ExecutionResult result =
        adapter.executeAfterApproval(
            baseInputs(repairEvidence, priorGraph), "attempt-1", (skillId, status) -> {});

    assertEquals(StageOutcomeClass.CANDIDATE, result.outcomeClass());
    verify(runner)
        .execute(
            eq(approvedPlan),
            eq(revision),
            eq(bindings),
            eq(manifest),
            eq("attempt-1"),
            eq(repairEvidence),
            eq(priorGraph),
            any());
  }

  @Test
  void firstTurnNeverRoutesThroughTheRepairEvidenceOverload() {
    ExecutionResult result = adapter.executeAfterApproval(baseInputs());

    assertEquals(StageOutcomeClass.CANDIDATE, result.outcomeClass());
    verify(runner, never())
        .execute(any(), any(), anyList(), any(), any(), any(StageRepairEvidence.class), any(), any());
  }

  private ExecutionInputs baseInputs() {
    return baseInputs(null, null);
  }

  private ExecutionInputs baseInputs(StageRepairEvidence repairEvidence, ChainPlanGraph priorGraph) {
    return new ExecutionInputs(
        RUN_ID,
        CONVERSATION_ID,
        approvalRef,
        approval,
        report,
        reportRef,
        approvedPlan,
        planRef,
        revision,
        revisionRef,
        sampleIds(),
        idsRef,
        new ImplementationPlan("plan text"),
        implementationRef,
        manifest,
        manifestRef,
        List.of(),
        null,
        repairEvidence,
        priorGraph);
  }

  private Reference append(Kind kind, String schemaVersion, Object payload) {
    Revision revision =
        artifactStore.append(
            new AppendCommand(
                RUN_ID,
                kind,
                schemaVersion,
                "test-producer",
                "1",
                payload,
                List.of(),
                null,
                new ArtifactProvenance(
                    RUN_ID,
                    "design-execution",
                    "create-chain",
                    "2",
                    "profile-sha",
                    "design-execution",
                    "1",
                    "closure")));
    return revision.reference();
  }

  private static CompilerDagExecutionResult successfulEngineResult(List<String> executed) {
    return engineResult(
        executed,
        new CompilerValidationBundle(
            1,
            "graph-digest",
            List.of(new CompilerValidationPass("graph", new ValidationResult(true, List.of(), "ok")))));
  }

  private static CompilerDagExecutionResult ineligibleCompilerEngineResult() {
    var issue =
        new ValidationIssue(
            "element-1",
            ValidationSeverity.BLOCKER,
            "Element properties violate schema for 'http-trigger'",
            "cip-element-validator",
            List.of("http-trigger-1"),
            List.of(),
            "Fix node properties according to schema");
    return engineResult(
        List.of("cip-trigger-generator"),
        new CompilerValidationBundle(
            1,
            "graph-digest",
            List.of(
                new CompilerValidationPass(
                    "cip-element-validator",
                    new ValidationResult(false, List.of(issue), "element validation failed")))));
  }

  private static CompilerDagExecutionResult engineResult(
      List<String> executed, CompilerValidationBundle bundle) {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("chain-1", "Chain"),
            List.of(new ChainPlanNode("trigger", "http-trigger", "Trigger", null, null, List.of())),
            List.of());
    return new CompilerDagExecutionResult(
        StageOutcomeClass.SUCCEEDED,
        "ok",
        executed,
        new PlanningPatchLedger(List.of(), List.of()),
        graph,
        new GraphAssemblyResult(1, graph, "graph-digest", List.of(), List.of(), List.of()),
        bundle);
  }

  private static CatalogBindingResolution sampleBinding() {
    return new CatalogBindingResolution(
        "call-1",
        CatalogBindingResolution.Source.EXISTING_CATALOG,
        "sys-1",
        "sg-1",
        "spec-1",
        "op-1",
        null,
        "2024.4",
        "catalog:sys-1");
  }

  private static DesignExecutionPlan samplePlan(
      List<String> owningSkills, String catalogHash, String addonHash) {
    List<DesignExecutionPlan.Step> steps = new ArrayList<>();
    int ordinal = 1;
    for (String skillId : owningSkills) {
      steps.add(
          new DesignExecutionPlan.Step(
              "step-" + ordinal + "-" + skillId,
              ordinal,
              "Step " + ordinal,
              DesignExecutionPlan.OwnerKind.SKILL,
              List.of(skillId),
              List.of(),
              List.of(),
              List.of(),
              List.of(),
              List.of(),
              List.of("GRAPH_PATCH_ARTIFACT")));
      ordinal++;
    }
    Map<String, String> skillHashes = Map.of("cip-trigger-generator", "skill-hash-trigger");
    Map<String, String> addonHashes = Map.of("cip-trigger-generator", addonHash);
    return new DesignExecutionPlan(
        "1",
        "revision-orders",
        "cip-design-planner",
        "chain-semantic-revision/revision-orders",
        "design-input-hash",
        "2024.4",
        ApprovalPolicy.CATALOG_FIRST_V1,
        steps,
        "design-plan-report",
        "report-content-hash",
        skillHashes,
        addonHashes,
        catalogHash,
        ApprovalPolicy.CATALOG_FIRST_V1_HASH);
  }

  private static ChainSemanticRevision sampleRevision() {
    return SemanticFixtures.linearOrders();
  }

  private static IdsDocument sampleIds() {
    return new IdsDocument(
        "1",
        IdsDocument.Mode.PROVIDED,
        "brief-1",
        "brief-hash",
        "flow-hash",
        "renderer-1",
        "# IDS\n");
  }

  private static RunManifest sampleManifest(
      String catalogHash, String skillHash, String addonHash) {
    ResolvedCompilerNode node =
        new ResolvedCompilerNode(
            "cip-trigger-generator",
            "Generation",
            null,
            List.of(),
            List.of("GRAPH_PATCH_ARTIFACT"),
            List.of(),
            "captureGraphPatch",
            List.of(),
            List.of(),
            true,
            List.of(),
            0,
            0,
            true,
            CompilerNodeExecutionMode.LLM_SKILL,
            null);
    CompilerRunPin pin =
        new CompilerRunPin(
            "compiler",
            "1",
            "pkg-digest",
            1,
            "1",
            catalogHash,
            new ResolvedCompilerDag(List.of(node), List.of(), "dag-digest"),
            List.of("cip-trigger-generator"),
            Map.of("cip-trigger-generator", skillHash),
            Map.of("cip-trigger-generator", addonHash),
            List.of(),
            null,
            null,
            null,
            null,
            null,
            null);
    return new RunManifest(
        RUN_ID,
        null,
        List.of(),
        "product",
        "create-chain",
        "2",
        "profile-sha",
        "baseline",
        "baseline-digest",
        List.of(),
        "closure",
        new KnowledgePackageRef(
            "knowledge-1",
            "1",
            "1.0.0",
            "checksum",
            "CERTIFIED",
            "sha256:certificate"),
        "24.4",
        List.of(),
        pin);
  }

  private static RunManifest sampleManifestWithDependency(
      String catalogHash,
      String skillHash,
      String addonHash,
      String dependencySkillId,
      String dependencySkillHash,
      String dependencyAddonHash) {
    ResolvedCompilerNode dependency =
        new ResolvedCompilerNode(
            dependencySkillId,
            "Generation",
            null,
            List.of(),
            List.of("GRAPH_PATCH_ARTIFACT"),
            List.of(),
            "captureGraphPatch",
            List.of(),
            List.of(),
            true,
            List.of(),
            0,
            0,
            true,
            CompilerNodeExecutionMode.LLM_SKILL,
            null);
    ResolvedCompilerNode owner =
        new ResolvedCompilerNode(
            "cip-trigger-generator",
            "Generation",
            null,
            List.of(),
            List.of("GRAPH_PATCH_ARTIFACT"),
            List.of(dependencySkillId),
            "captureGraphPatch",
            List.of(),
            List.of(),
            true,
            List.of(),
            0,
            0,
            true,
            CompilerNodeExecutionMode.LLM_SKILL,
            null);
    CompilerRunPin pin =
        new CompilerRunPin(
            "compiler",
            "1",
            "pkg-digest",
            1,
            "1",
            catalogHash,
            new ResolvedCompilerDag(List.of(dependency, owner), List.of(), "dag-digest"),
            List.of(dependencySkillId, "cip-trigger-generator"),
            Map.of(
                "cip-trigger-generator", skillHash,
                dependencySkillId, dependencySkillHash),
            Map.of(
                "cip-trigger-generator", addonHash,
                dependencySkillId, dependencyAddonHash),
            List.of(),
            null,
            null,
            null,
            null,
            null,
            null);
    return new RunManifest(
        RUN_ID,
        null,
        List.of(),
        "product",
        "create-chain",
        "2",
        "profile-sha",
        "baseline",
        "baseline-digest",
        List.of(),
        "closure",
        new KnowledgePackageRef(
            "knowledge-1",
            "1",
            "1.0.0",
            "checksum",
            "CERTIFIED",
            "sha256:certificate"),
        "24.4",
        List.of(),
        pin);
  }

  private static String sha256Hex(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }
}
