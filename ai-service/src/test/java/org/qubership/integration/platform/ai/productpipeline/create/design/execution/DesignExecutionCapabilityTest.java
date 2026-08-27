package org.qubership.integration.platform.ai.productpipeline.create.design.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.AppendCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Revision;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
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
import org.qubership.integration.platform.ai.productpipeline.capability.ArtifactCandidate;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.capability.StageRepairEvidence;
import org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerDagExecutionResult;
import org.qubership.integration.platform.ai.productpipeline.create.FailureNarrative;
import org.qubership.integration.platform.ai.productpipeline.create.PlanningSkillArtifactUnavailableException;
import org.qubership.integration.platform.ai.productpipeline.create.PlanningPatchLedger;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingResolution;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignExecutionPlan;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanReport;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.IdsDocument;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticFixtures;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ApprovalPolicy;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationIssue;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationResult;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationSeverity;

class DesignExecutionCapabilityTest {

  private static final Instant FIXED = Instant.parse("2026-07-30T12:00:00Z");
  private static final String RUN_ID = "run-design-exec-1";
  private static final String CONVERSATION_ID = "conv-design-exec-1";

  private ProductPipelineArtifactStore artifactStore;
  private ApprovedCompilerExecutionRunner runner;
  private ExecutorCatalogBindingAdapter bindingAdapter;
  private CipDesignExecutorJavaAdapter adapter;
  private DesignExecutionCapability capability;

  private Reference idsRef;
  private Reference revisionRef;
  private Reference reportRef;
  private Reference planRef;
  private Reference implementationRef;
  private Reference manifestRef;
  private Reference idsApprovalRef;
  private Reference implementationApprovalRef;

  private DesignExecutionPlan approvedPlan;
  private ChainSemanticRevision revision;
  private RunManifest manifest;
  private List<CatalogBindingResolution> bindings;
  private DesignPlanReport report;
  private ImplementationPlan implementationPlan;
  private IdsDocument ids;

  @BeforeEach
  void setUp() {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    CompilationArtifacts artifacts =
        new CompilationArtifacts(
            new InMemoryArtifactBlobStore(), mapper, Clock.fixed(FIXED, ZoneOffset.UTC));
    artifactStore = new ProductPipelineArtifactStore(artifacts);
    runner = mock(ApprovedCompilerExecutionRunner.class);
    bindingAdapter = mock(ExecutorCatalogBindingAdapter.class);

    revision = sampleRevision();
    approvedPlan = samplePlan();
    manifest = sampleManifest();
    bindings = List.of(sampleBinding());
    report = new DesignPlanReport("1", sampleReportMarkdown());
    implementationPlan = new ImplementationPlan("implementation plan text");
    ids = sampleIds();

    idsRef = append(Kind.IDS_DOCUMENT, "1", ids);
    revisionRef =
        append(Kind.CHAIN_SEMANTIC_REVISION, ChainSemanticRevision.CURRENT_SCHEMA_VERSION, revision);
    reportRef = append(Kind.DESIGN_PLAN_REPORT, "1", report);
    planRef = append(Kind.DESIGN_EXECUTION_PLAN, "1", approvedPlan);
    implementationRef = append(Kind.IMPLEMENTATION_PLAN, "1", implementationPlan);
    manifestRef = append(Kind.RUN_MANIFEST, "1", manifest);

    idsApprovalRef =
        append(
            Kind.APPROVAL_RECORD,
            "2",
            new ApprovalRecordV2(
                idsRef,
                idsRef.contentHash(),
                List.of(idsRef, revisionRef),
                "tester",
                "ids approved",
                FIXED,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null));
    implementationApprovalRef =
        append(
            Kind.APPROVAL_RECORD,
            "2",
            new ApprovalRecordV2(
                implementationRef,
                implementationRef.contentHash(),
                List.of(idsRef, revisionRef, reportRef, planRef, implementationRef),
                "tester",
                "implementation approved",
                FIXED,
                ApprovalPolicy.CATALOG_FIRST_V1,
                ApprovalPolicy.CATALOG_FIRST_V1_HASH,
                null,
                null,
                null,
                null,
                null,
                null));

    org.qubership.integration.platform.ai.qipknowledge.validation.CompilerPlanValidator planValidator =
        mock(org.qubership.integration.platform.ai.qipknowledge.validation.CompilerPlanValidator.class);
    when(planValidator.validate(any()))
        .thenReturn(
            new org.qubership.integration.platform.ai.qipknowledge.validation.ValidationResult(
                true, List.of(), "ok"));
    adapter =
        new CipDesignExecutorJavaAdapter(runner, bindingAdapter, artifactStore, planValidator);
    capability = new DesignExecutionCapability(artifactStore, adapter);

    when(bindingAdapter.resolve(eq(CONVERSATION_ID), eq(revision), anyList(), any()))
        .thenReturn(List.of(new BindingResolutionResult.Resolved(bindings.getFirst())));
    when(runner.execute(
            eq(approvedPlan), eq(revision), eq(bindings), eq(manifest), eq("attempt-1"), any()))
        .thenReturn(successfulEngineResult());
  }

  @Test
  void missingImplementationApprovalDoesNotInvokeRunner() {
    StageOutcome outcome =
        execute(List.of(idsRef, revisionRef, reportRef, planRef, implementationRef, manifestRef));

    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, outcome.outcomeClass());
    assertTrue(outcome.message().toLowerCase().contains("approval"));
    verifyNoInteractions(runner);
  }

  @Test
  void idsApprovalAloneIsIgnoredAndDoesNotInvokeRunner() {
    StageOutcome outcome =
        execute(
            List.of(
                idsRef,
                revisionRef,
                reportRef,
                planRef,
                implementationRef,
                manifestRef,
                idsApprovalRef));

    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, outcome.outcomeClass());
    verifyNoInteractions(runner);
  }

  @Test
  void reportHashMismatchDoesNotInvokeRunner() {
    Reference staleReport =
        append(Kind.DESIGN_PLAN_REPORT, "1", new DesignPlanReport("1", sampleReportMarkdown() + "\n# changed"));
    StageOutcome outcome =
        execute(
            List.of(
                idsRef,
                revisionRef,
                staleReport,
                planRef,
                implementationRef,
                manifestRef,
                implementationApprovalRef));

    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, outcome.outcomeClass());
    assertTrue(outcome.message().toLowerCase().contains("report"));
    verifyNoInteractions(runner);
  }

  @Test
  void ambiguousImplementationApprovalsAreContractFailure() {
    Reference duplicateApproval =
        append(
            Kind.APPROVAL_RECORD,
            "2",
            new ApprovalRecordV2(
                implementationRef,
                implementationRef.contentHash(),
                List.of(idsRef, revisionRef, reportRef, planRef, implementationRef),
                "tester",
                "duplicate",
                FIXED,
                ApprovalPolicy.CATALOG_FIRST_V1,
                ApprovalPolicy.CATALOG_FIRST_V1_HASH,
                null,
                null,
                null,
                null,
                null,
                null));

    StageOutcome outcome =
        execute(
            List.of(
                idsRef,
                revisionRef,
                reportRef,
                planRef,
                implementationRef,
                manifestRef,
                implementationApprovalRef,
                duplicateApproval));

    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, outcome.outcomeClass());
    assertTrue(outcome.message().toLowerCase().contains("ambiguous"));
    verifyNoInteractions(runner);
  }

  @Test
  void nullMessageRuntimeExceptionIncludesExceptionClass() {
    when(bindingAdapter.resolve(eq(CONVERSATION_ID), eq(revision), anyList(), any()))
        .thenThrow(new NullPointerException());

    StageOutcome outcome =
        execute(
            List.of(
                idsRef,
                revisionRef,
                reportRef,
                planRef,
                implementationRef,
                manifestRef,
                idsApprovalRef,
                implementationApprovalRef));

    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, outcome.outcomeClass());
    assertTrue(outcome.message().contains("NullPointerException"));
    verifyNoInteractions(runner);
  }

  @Test
  void toolArgumentsFailureIsRetryableTechnicalNotContract() {
    when(bindingAdapter.resolve(eq(CONVERSATION_ID), eq(revision), anyList(), any()))
        .thenThrow(
            new RuntimeException(
                new dev.langchain4j.exception.ToolArgumentsException(
                    "Cannot deserialize value of type `NamingManifest`")));

    StageOutcome outcome =
        execute(
            List.of(
                idsRef,
                revisionRef,
                reportRef,
                planRef,
                implementationRef,
                manifestRef,
                idsApprovalRef,
                implementationApprovalRef));

    assertEquals(StageOutcomeClass.RETRYABLE_TECHNICAL_FAILURE, outcome.outcomeClass());
    assertTrue(outcome.message().contains("NamingManifest"));
    verifyNoInteractions(runner);
  }

  @Test
  void missingChainStructureUsesLastCaptureValidationSummary() {
    CaptureAttemptFeedbackStore feedbackStore = new CaptureAttemptFeedbackStore();
    feedbackStore.recordPlanValidationFailure(
        CONVERSATION_ID,
        "Structure validation failed:\n"
            + "node 'kafka-trigger-1' (kafka-trigger-2) has unknown property key 'topic'.");
    capability = new DesignExecutionCapability(artifactStore, adapter, "", feedbackStore);
    when(runner.execute(
            eq(approvedPlan), eq(revision), eq(bindings), eq(manifest), eq("attempt-1"), any()))
        .thenThrow(
            new PlanningSkillArtifactUnavailableException(
                "cip-structure-generator",
                Set.of("CHAIN_STRUCTURE"),
                new IllegalStateException("status=FAILED")));

    StageOutcome outcome = execute(standardInputRefs());

    assertEquals(StageOutcomeClass.VALIDATION_FAILURE, outcome.outcomeClass());
    assertTrue(outcome.message().contains("unknown property key 'topic'"));
    assertFalse(outcome.message().toLowerCase().contains("retry the planning stage"));
  }

  @Test
  void approvedPlanInvokesRunnerAndCheckpointsWaitingForMaterialization() {
    StageOutcome outcome =
        execute(
            List.of(
                idsRef,
                revisionRef,
                reportRef,
                planRef,
                implementationRef,
                manifestRef,
                idsApprovalRef,
                implementationApprovalRef));

    assertEquals(StageOutcomeClass.SUCCEEDED, outcome.outcomeClass());
    verify(runner)
        .execute(
            eq(approvedPlan), eq(revision), eq(bindings), eq(manifest), eq("attempt-1"), any());
    verify(bindingAdapter).resolve(eq(CONVERSATION_ID), eq(revision), anyList(), any());

    DesignExecutionCheckpoint checkpoint =
        outcome.candidates().stream()
            .filter(candidate -> candidate.kind() == Kind.DESIGN_EXECUTION_CHECKPOINT)
            .map(ArtifactCandidate::payload)
            .map(DesignExecutionCheckpoint.class::cast)
            .findFirst()
            .orElseThrow();
    assertEquals(DesignExecutionPhase.WAITING_FOR_MATERIALIZATION, checkpoint.phase());
    assertEquals(implementationApprovalRef, checkpoint.approvalRef());

    assertTrue(
        outcome.candidates().stream()
            .anyMatch(candidate -> candidate.kind() == Kind.VALIDATED_EXECUTION_BUNDLE));
    assertTrue(
        outcome.candidates().stream()
            .anyMatch(candidate -> candidate.kind() == Kind.MATERIALIZATION_REQUEST));
    assertTrue(
        outcome.candidates().stream()
            .noneMatch(candidate -> candidate.kind() == Kind.DESIGN_EXECUTION_RESULT),
        "Phase 6 DESIGN_EXECUTION_RESULT is owned by materialization, not design-execution");
  }

  @Test
  void configuredRecoveryFaultFailsTheFirstTwoMatchingRunAttempts() {
    capability = new DesignExecutionCapability(artifactStore, adapter, "Orders");

    StageOutcome first = execute(standardInputRefs());
    StageOutcome second = execute(standardInputRefs());

    assertEquals(StageOutcomeClass.VALIDATION_FAILURE, first.outcomeClass());
    assertEquals(StageOutcomeClass.VALIDATION_FAILURE, second.outcomeClass());
    assertTrue(first.message().contains("required setting"));
    assertTrue(second.message().contains("required setting"));
    verifyNoInteractions(bindingAdapter, runner);

    StageOutcome third = execute(standardInputRefs());

    assertEquals(StageOutcomeClass.SUCCEEDED, third.outcomeClass());
    verify(runner).execute(any(), any(), anyList(), any(), eq("attempt-1"), any());
  }

  @Test
  void ineligibleCompilerBundlePutsMergedFindingsOnStageOutcome() {
    when(runner.execute(
            eq(approvedPlan), eq(revision), eq(bindings), eq(manifest), eq("attempt-1"), any()))
        .thenReturn(ineligibleCompilerEngineResult());

    StageOutcome outcome = execute(standardInputRefs());

    assertEquals(StageOutcomeClass.VALIDATION_FAILURE, outcome.outcomeClass());
    PlanValidationResult validation =
        outcome.candidates().stream()
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
    String findingsText = FailureNarrative.findingsText(outcome.candidates());
    assertFalse(findingsText.isBlank());
    assertTrue(findingsText.contains("http-trigger"));
  }

  @Test
  void emitsGeneratorSkillProgressOnTheCapabilityStream() {
    doAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              BiConsumer<String, String> progress = invocation.getArgument(5);
              progress.accept("cip-trigger-generator", "running");
              progress.accept("cip-trigger-generator", "completed");
              return successfulEngineResult();
            })
        .when(runner)
        .execute(eq(approvedPlan), eq(revision), eq(bindings), eq(manifest), eq("attempt-1"), any());

    List<CapabilitySignal> signals = collectSignals(standardInputRefs());

    assertTrue(
        signals.stream()
            .anyMatch(
                signal ->
                    signal instanceof CapabilitySignal.SkillProgress progress
                        && "cip-trigger-generator".equals(progress.skillId())
                        && "running".equals(progress.status())),
        () -> "expected generator skill progress on the capability stream, got: " + signals);
  }

  @Test
  void doesNotUseFindSingleOverAllApprovalRecords() {
    StageOutcome outcome =
        execute(
            List.of(
                idsRef,
                revisionRef,
                reportRef,
                planRef,
                implementationRef,
                manifestRef,
                idsApprovalRef,
                implementationApprovalRef));

    assertEquals(StageOutcomeClass.SUCCEEDED, outcome.outcomeClass());
    verify(runner).execute(any(), any(), anyList(), any(), eq("attempt-1"), any());
    verify(runner, never()).execute(any(), any(), anyList(), eq(null), any(), any());
  }

  @Test
  void firstTurnCarriesNoRepairEvidenceToTheRunner() {
    StageOutcome outcome = execute(standardInputRefs());

    assertEquals(StageOutcomeClass.SUCCEEDED, outcome.outcomeClass());
    verify(runner, never())
        .execute(any(), any(), anyList(), any(), any(), any(StageRepairEvidence.class), any(), any());
  }

  @Test
  void repairTurnPassesHaltEvidenceAndThePriorGraphToTheRunner() {
    ChainPlanGraph priorGraph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("chain-1", "Chain"),
            List.of(new ChainPlanNode("trigger", "http-trigger", "Trigger", null, null, List.of())),
            List.of());
    append(Kind.CHAIN_PLAN_GRAPH, "1", priorGraph);
    when(runner.execute(
            eq(approvedPlan),
            eq(revision),
            eq(bindings),
            eq(manifest),
            eq("attempt-1"),
            any(StageRepairEvidence.class),
            eq(priorGraph),
            any()))
        .thenReturn(successfulEngineResult());

    Map<String, Object> repairAttributes = new HashMap<>();
    repairAttributes.put(
        org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport
            .STAGE_ERROR_CONTEXT_ATTR,
        "Phase 5 plan validation failed. Findings: http-trigger-1: schema violation");
    repairAttributes.put(
        org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport
            .STAGE_ERROR_OUTCOME_ATTR,
        "VALIDATION_FAILURE");
    repairAttributes.put(
        org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport
            .STAGE_ERROR_FAILED_STAGE_ATTR,
        "design-execution");
    repairAttributes.put(
        org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport
            .STAGE_ERROR_FINDINGS_ATTR,
        "http-trigger-1: schema violation (blocker)");
    repairAttributes.put(
        org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport
            .HALT_FOLLOW_UP_TEXT_ATTR,
        "use RBAC");

    StageOutcome outcome = execute(standardInputRefs(), repairAttributes);

    assertEquals(StageOutcomeClass.SUCCEEDED, outcome.outcomeClass());
    ArgumentCaptor<StageRepairEvidence> captor = ArgumentCaptor.forClass(StageRepairEvidence.class);
    verify(runner)
        .execute(
            eq(approvedPlan),
            eq(revision),
            eq(bindings),
            eq(manifest),
            eq("attempt-1"),
            captor.capture(),
            eq(priorGraph),
            any());
    StageRepairEvidence evidence = captor.getValue();
    assertEquals("VALIDATION_FAILURE", evidence.outcomeClass());
    assertEquals("design-execution", evidence.failedStageId());
    assertTrue(evidence.findings().contains("http-trigger-1"));
    assertEquals("use RBAC", evidence.haltFollowUpText());
  }

  @Test
  void repairTurnReadsTheGraphOfTheHaltedAttemptRatherThanTheLatestInTheRun() {
    ChainPlanGraph haltedGraph = graphWithTrigger("halted-trigger");
    Reference haltedRef = append(Kind.CHAIN_PLAN_GRAPH, "1", haltedGraph);
    // Appended afterwards, so a lookup by kind alone would hand the runner this one instead.
    ChainPlanGraph laterGraph = graphWithTrigger("later-trigger");
    append(Kind.CHAIN_PLAN_GRAPH, "1", laterGraph);
    when(runner.execute(
            eq(approvedPlan),
            eq(revision),
            eq(bindings),
            eq(manifest),
            eq("attempt-1"),
            any(StageRepairEvidence.class),
            eq(haltedGraph),
            any()))
        .thenReturn(successfulEngineResult());
    Map<String, Object> repairAttributes = new HashMap<>();
    repairAttributes.put(
        ProductPipelineRunSupport.STAGE_ERROR_CONTEXT_ATTR,
        "Phase 5 plan validation failed. Findings: halted-trigger: schema violation");
    repairAttributes.put(
        StageRepairEvidence.PRIOR_OUTPUT_REFS_ATTR, List.of(haltedRef));

    StageOutcome outcome = execute(standardInputRefs(), repairAttributes);

    assertEquals(StageOutcomeClass.SUCCEEDED, outcome.outcomeClass());
    verify(runner)
        .execute(
            eq(approvedPlan),
            eq(revision),
            eq(bindings),
            eq(manifest),
            eq("attempt-1"),
            any(StageRepairEvidence.class),
            eq(haltedGraph),
            any());
    verify(runner, never())
        .execute(any(), any(), anyList(), any(), any(), any(), eq(laterGraph), any());
  }

  private static ChainPlanGraph graphWithTrigger(String nodeId) {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("chain-1", "Chain"),
        List.of(new ChainPlanNode(nodeId, "http-trigger", "Trigger", null, null, List.of())),
        List.of());
  }

  private StageOutcome execute(List<Reference> inputRefs) {
    return execute(inputRefs, Map.of());
  }

  private StageOutcome execute(List<Reference> inputRefs, Map<String, Object> extraAttributes) {
    return collectSignals(inputRefs, extraAttributes).stream()
        .filter(CapabilitySignal.Completed.class::isInstance)
        .map(CapabilitySignal.Completed.class::cast)
        .findFirst()
        .orElseThrow(() -> new AssertionError("expected Completed signal"))
        .outcome();
  }

  private List<CapabilitySignal> collectSignals(List<Reference> inputRefs) {
    return collectSignals(inputRefs, Map.of());
  }

  private List<CapabilitySignal> collectSignals(
      List<Reference> inputRefs, Map<String, Object> extraAttributes) {
    Map<String, Object> attributes = new HashMap<>();
    attributes.put("idsDocument", ids);
    attributes.put("chainSemanticRevision", revision);
    attributes.put("designPlanReport", report);
    attributes.put("designExecutionPlan", approvedPlan);
    attributes.put("implementationPlan", implementationPlan);
    attributes.putAll(extraAttributes);
    StageExecutionContext context =
        new StageExecutionContext(
            RUN_ID, CONVERSATION_ID, "design-execution", "exec-1", "attempt-1", null, manifest,
            inputRefs, attributes);
    return capability.execute(context).collect().asList().await().indefinitely();
  }

  private List<Reference> standardInputRefs() {
    return List.of(
        idsRef,
        revisionRef,
        reportRef,
        planRef,
        implementationRef,
        manifestRef,
        idsApprovalRef,
        implementationApprovalRef);
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
                provenance()));
    return revision.reference();
  }

  private static ArtifactProvenance provenance() {
    return new ArtifactProvenance(
        RUN_ID,
        "design-execution",
        "create-chain",
        "2",
        "profile-sha",
        "design-execution",
        "1",
        "closure");
  }

  private static CompilerDagExecutionResult successfulEngineResult() {
    return engineResult(
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
        new CompilerValidationBundle(
            1,
            "graph-digest",
            List.of(
                new CompilerValidationPass(
                    "cip-element-validator",
                    new ValidationResult(false, List.of(issue), "element validation failed")))));
  }

  private static CompilerDagExecutionResult engineResult(CompilerValidationBundle bundle) {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("chain-1", "Chain"),
            List.of(new ChainPlanNode("trigger", "http-trigger", "Trigger", null, null, List.of())),
            List.of());
    return new CompilerDagExecutionResult(
        StageOutcomeClass.SUCCEEDED,
        "ok",
        List.of("cip-trigger-generator"),
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
        "pkg.1",
        "2024.4",
        "catalog:sys-1");
  }

  private static DesignExecutionPlan samplePlan() {
    return new DesignExecutionPlan(
        "1",
        "revision-orders",
        "cip-design-planner",
        "chain-semantic-revision/revision-orders",
        "design-input-hash",
        "2024.4",
        ApprovalPolicy.CATALOG_FIRST_V1,
        List.of(
            new DesignExecutionPlan.Step(
                "step-1-cip-trigger-generator",
                1,
                "Generate HTTP trigger",
                DesignExecutionPlan.OwnerKind.SKILL,
                List.of("cip-trigger-generator"),
                List.of(),
                List.of("client"),
                List.of(),
                List.of(),
                List.of("CHAIN_SEMANTIC_REVISION"),
                List.of("GRAPH_PATCH_ARTIFACT"))),
        "design-plan-report",
        "report-content-hash",
        Map.of("cip-trigger-generator", "skill-hash-trigger"),
        Map.of("cip-trigger-generator", "addon-hash-trigger"),
        "catalog-hash",
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

  private static String sampleReportMarkdown() {
    return """
        # Design plan

        1. Generate HTTP trigger — owning skill: `cip-trigger-generator`
        """;
  }

  private static RunManifest sampleManifest() {
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
            "catalog-hash",
            new ResolvedCompilerDag(List.of(node), List.of(), "dag-digest"),
            List.of("cip-trigger-generator"),
            Map.of("cip-trigger-generator", "skill-hash-trigger"),
            Map.of("cip-trigger-generator", "addon-hash-trigger"),
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
}
