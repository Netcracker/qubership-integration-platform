package org.qubership.integration.platform.ai.productpipeline.materialization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.smallrye.mutiny.Multi;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFactsService;
import org.qubership.integration.platform.ai.chain.reconcile.ChainReconcileService;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.AppendCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Revision;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.integration.catalog.materialize.MaterializationMap;
import org.qubership.integration.platform.ai.plan.ImplementationPlan;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.productpipeline.artifact.ApprovalRecordV2;
import org.qubership.integration.platform.ai.productpipeline.artifact.ArtifactProvenance;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerValidationBundle;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerValidationPass;
import org.qubership.integration.platform.ai.productpipeline.artifact.DependencyClosureEntry;
import org.qubership.integration.platform.ai.productpipeline.artifact.GraphAssemblyResult;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationResult;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.CipDesignExecutorJavaAdapter;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.DesignExecutionCheckpoint;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.DesignExecutionPhase;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.ExecutorValidationBundle;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.MaterializationRequest;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.OrderedGraphPatches;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.ValidatedExecutionBundle;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapabilityRegistry;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.profile.ApprovalPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationResult;

@ExtendWith(MockitoExtension.class)
class MaterializationCapabilityTest {

  private static final Instant FIXED = Instant.parse("2026-07-24T00:00:00Z");
  private static final String RUN_ID = "run-materialization-capability-1";

  @Mock private ProductChainMaterializer materializer;
  @Mock private ChainCatalogFactsService factsService;
  @Mock private ChainReconcileService reconcileService;

  private ProductPipelineArtifactStore artifactStore;
  private MaterializationCapability capability;
  private CipDesignExecutorJavaAdapter designExecutor;

  @BeforeEach
  void setUp() {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    CompilationArtifacts artifacts =
        new CompilationArtifacts(
            new InMemoryArtifactBlobStore(), mapper, Clock.fixed(FIXED, ZoneOffset.UTC));
    artifactStore = new ProductPipelineArtifactStore(artifacts);
    designExecutor =
        new CipDesignExecutorJavaAdapter(
            mock(org.qubership.integration.platform.ai.productpipeline.create.design.execution.ApprovedCompilerExecutionRunner.class),
            mock(org.qubership.integration.platform.ai.productpipeline.create.design.execution.ExecutorCatalogBindingAdapter.class),
            artifactStore,
            mock(org.qubership.integration.platform.ai.qipknowledge.validation.CompilerPlanValidator.class));
    capability =
        new MaterializationCapability(
            artifactStore, materializer, factsService, reconcileService, designExecutor);
  }

  @Test
  void returnsContractFailureWhenMandatoryInputsAreMissing() {
    StageExecutionContext context =
        new StageExecutionContext(
            RUN_ID,
            "conv-1",
            "materialization",
            RUN_ID,
            "attempt-1",
            null,
            runManifest(),
            List.of(),
            Map.of());

    CapabilitySignal.Completed completed = completed(capability.execute(context));

    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, completed.outcome().outcomeClass());
    verify(materializer, never()).resume(any(), any());
  }

  @Test
  void returnsContractFailureWhenApprovalDigestDoesNotMatchGraphDigest() {
    PreparedInputs prepared = appendHappyPathInputs();
    ApprovalRecordV2 mismatchedApproval =
        new ApprovalRecordV2(
            prepared.implementationPlanRef(),
            "different-digest",
            prepared.approvedCandidates(),
            "user",
            null,
            FIXED,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    Reference mismatchedApprovalRef = append(Kind.APPROVAL_RECORD, "2", mismatchedApproval, List.of());
    StageExecutionContext context = contextWith(prepared, mismatchedApprovalRef);

    CapabilitySignal.Completed completed = completed(capability.execute(context));

    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, completed.outcome().outcomeClass());
    verify(materializer, never()).resume(any(), any());
  }

  @Test
  void validInputsReturnSucceededWithCompleteCandidates() {
    PreparedInputs prepared = appendHappyPathInputs();
    StageExecutionContext context = contextWith(prepared, prepared.approvalRef());
    MaterializationResult readBack =
        new MaterializationResult(
            1,
            "catalog-chain-1",
            new MaterializationMap(
                "catalog-chain-1",
                Map.of("trigger-1", "catalog-trigger-1", "script-1", "catalog-script-1"), Map.of(), Map.of()),
            prepared.graphDigest(),
            MaterializationPhase.READ_BACK);
    MaterializationResult reconciled =
        new MaterializationResult(
            1,
            "catalog-chain-1",
            readBack.materializationMap(),
            prepared.graphDigest(),
            MaterializationPhase.RECONCILE);
    MaterializationResult complete =
        new MaterializationResult(
            1,
            "catalog-chain-1",
            readBack.materializationMap(),
            prepared.graphDigest(),
            MaterializationPhase.COMPLETE);
    when(materializer.resume(any(), any())).thenReturn(readBack);
    when(materializer.markReconciled(any(), any())).thenReturn(reconciled);
    when(materializer.markComplete(any(), any())).thenReturn(complete);
    when(factsService.load("catalog-chain-1"))
        .thenReturn(
            new org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFacts(
                "catalog-chain-1",
                "demo-chain",
                "Demo",
                2,
                0,
                "",
                List.of(),
                List.of(),
                "built_in_catalog"));
    when(reconcileService.compare(any(), any(), any()))
        .thenReturn(
            new org.qubership.integration.platform.ai.skill.orchestration.ReconcileResult(
                true, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "ok"));

    List<CapabilitySignal> signals =
        capability.execute(context).collect().asList().await().indefinitely();
    CapabilitySignal.Completed completed =
        signals.stream()
            .filter(CapabilitySignal.Completed.class::isInstance)
            .map(CapabilitySignal.Completed.class::cast)
            .findFirst()
            .orElseThrow();

    assertEquals(
        StageOutcomeClass.SUCCEEDED,
        completed.outcome().outcomeClass(),
        () -> String.valueOf(completed.outcome().message()));
    assertEquals(4, completed.outcome().candidates().size());
    assertTrue(
        signals.stream()
            .anyMatch(
                s ->
                    s instanceof CapabilitySignal.Message m
                        && m.text().contains("Chain \"demo-chain\" is ready.")
                        && m.text().contains("[Open graph](/chains/catalog-chain-1/graph)")));
    assertTrue(
        signals.stream()
            .anyMatch(
                s ->
                    s instanceof CapabilitySignal.SkillProgress sp
                        && MaterializationCapability.CAPABILITY_ID.equals(sp.skillId())
                        && "running".equals(sp.status())));
    assertTrue(
        signals.stream()
            .anyMatch(
                s ->
                    s instanceof CapabilitySignal.SkillProgress sp
                        && MaterializationCapability.CAPABILITY_ID.equals(sp.skillId())
                        && "completed".equals(sp.status())));
  }

  @Test
  void runtimeRegistryContainsCompletedMaterializationCapability() {
    StageCapabilityRegistry registry = new StageCapabilityRegistry(List.of(capability));
    assertInstanceOf(
        MaterializationCapability.class,
        registry.require(MaterializationCapability.CAPABILITY_ID));
  }

  @Test
  void returnsContractFailureWhenGraphBytesDifferFromAssemblyDigest() {
    PreparedInputs prepared = appendHappyPathInputs();
    ChainPlanGraph differentGraph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("other", "Other"),
            List.of(new ChainPlanNode("only", "script", "Only", null, null, List.of())),
            List.of());
    GraphAssemblyResult mismatched =
        new GraphAssemblyResult(
            1, differentGraph, prepared.graphDigest(), List.of(), List.of(), List.of());
    Reference badAssembly = append(Kind.GRAPH_ASSEMBLY_RESULT, "1", mismatched, List.of(prepared.graphRef()));
    List<Reference> candidates =
        List.of(
            prepared.implementationPlanRef(),
            prepared.validationRef(),
            prepared.graphRef(),
            badAssembly,
            prepared.bundleRef());
    ApprovalRecordV2 approval =
        new ApprovalRecordV2(
            prepared.implementationPlanRef(),
            prepared.implementationPlanRef().contentHash(),
            candidates,
            "user",
            null,
            FIXED,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    Reference approvalRef = append(Kind.APPROVAL_RECORD, "2", approval, candidates);
    StageExecutionContext context =
        new StageExecutionContext(
            RUN_ID,
            "conv-1",
            "materialization",
            RUN_ID,
            "attempt-1",
            null,
            runManifest(),
            List.of(
                prepared.implementationPlanRef(),
                prepared.validationRef(),
                prepared.graphRef(),
                badAssembly,
                prepared.bundleRef(),
                approvalRef,
                prepared.runManifestRef()),
            Map.of());

    CapabilitySignal.Completed completed = completed(capability.execute(context));

    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, completed.outcome().outcomeClass());
    verify(materializer, never()).resume(any(), any());
  }

  @Test
  void returnsFailureBeforeResumeWhenCompilerBundleNotEligible() {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo-chain", "Demo"),
            List.of(
                new ChainPlanNode("trigger-1", "http-trigger", "Trigger", null, null, List.of()),
                new ChainPlanNode("script-1", "script", "Script", "trigger-1", null, List.of())),
            List.of());
    String graphDigest =
        new org.qubership.integration.platform.ai.qipknowledge.patch.CanonicalGraphDigest(
                new ObjectMapper())
            .sha256(graph);
    GraphAssemblyResult assembly =
        new GraphAssemblyResult(1, graph, graphDigest, List.of(), List.of(), List.of());
    CompilerValidationBundle failingBundle =
        new CompilerValidationBundle(
            1,
            graphDigest,
            List.of(
                new CompilerValidationPass(
                    "validator", new ValidationResult(false, List.of(), "blocked"))));
    ImplementationPlan implementationPlan =
        ImplementationPlan.schemaVersion2(
            "Plan",
            "planning",
            "1",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of());
    PlanValidationResult validationResult = new PlanValidationResult(List.of());
    Reference implementationPlanRef = append(Kind.IMPLEMENTATION_PLAN, "2", implementationPlan, List.of());
    Reference graphRef = append(Kind.CHAIN_PLAN_GRAPH, "1", graph, List.of());
    Reference assemblyRef = append(Kind.GRAPH_ASSEMBLY_RESULT, "1", assembly, List.of(graphRef));
    Reference bundleRef = append(Kind.COMPILER_VALIDATION_BUNDLE, "1", failingBundle, List.of(graphRef));
    Reference validationRef =
        append(Kind.PLAN_VALIDATION_RESULT, "1", validationResult, List.of(implementationPlanRef));
    Reference runManifestRef = append(Kind.RUN_MANIFEST, "1", runManifest(), List.of());
    List<Reference> approvedCandidates =
        List.of(implementationPlanRef, validationRef, graphRef, assemblyRef, bundleRef);
    ApprovalRecordV2 approval =
        new ApprovalRecordV2(
            implementationPlanRef,
            implementationPlanRef.contentHash(),
            approvedCandidates,
            "user",
            null,
            FIXED,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    Reference approvalRef = append(Kind.APPROVAL_RECORD, "2", approval, approvedCandidates);
    StageExecutionContext context =
        new StageExecutionContext(
            RUN_ID,
            "conv-1",
            "materialization",
            RUN_ID,
            "attempt-1",
            null,
            runManifest(),
            List.of(
                implementationPlanRef,
                validationRef,
                graphRef,
                assemblyRef,
                bundleRef,
                approvalRef,
                runManifestRef),
            Map.of());

    CapabilitySignal.Completed completed = completed(capability.execute(context));

    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, completed.outcome().outcomeClass());
    verify(materializer, never()).resume(any(), any());
  }

  @Test
  void returnsFailureBeforeResumeWhenOrderedPatchReferenceMissing() {
    PreparedInputs prepared = appendHappyPathInputs();
    Reference dangling =
        new Reference(Kind.GRAPH_PATCH_ARTIFACT, "missing-art", "missing-hash");
    ChainPlanGraph graph =
        artifactStore.payload(
            artifactStore.get(RUN_ID, prepared.graphRef()).orElseThrow(), ChainPlanGraph.class);
    GraphAssemblyResult assembly =
        new GraphAssemblyResult(
            1, graph, prepared.graphDigest(), List.of(dangling), List.of(), List.of());
    Reference assemblyRef = append(Kind.GRAPH_ASSEMBLY_RESULT, "1", assembly, List.of(prepared.graphRef()));
    List<Reference> candidates =
        List.of(
            prepared.implementationPlanRef(),
            prepared.validationRef(),
            prepared.graphRef(),
            assemblyRef,
            prepared.bundleRef());
    ApprovalRecordV2 approval =
        new ApprovalRecordV2(
            prepared.implementationPlanRef(),
            prepared.implementationPlanRef().contentHash(),
            candidates,
            "user",
            null,
            FIXED,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    Reference approvalRef = append(Kind.APPROVAL_RECORD, "2", approval, candidates);
    StageExecutionContext context =
        new StageExecutionContext(
            RUN_ID,
            "conv-1",
            "materialization",
            RUN_ID,
            "attempt-1",
            null,
            runManifest(),
            List.of(
                prepared.implementationPlanRef(),
                prepared.validationRef(),
                prepared.graphRef(),
                assemblyRef,
                prepared.bundleRef(),
                approvalRef,
                prepared.runManifestRef()),
            Map.of());

    CapabilitySignal.Completed completed = completed(capability.execute(context));

    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, completed.outcome().outcomeClass());
    verify(materializer, never()).resume(any(), any());
  }

  @Test
  void v2RejectsValidatedBundleContentHashMismatch() {
    PreparedV2Inputs prepared = appendHappyPathV2Inputs();
    ValidatedExecutionBundle foreign =
        new ValidatedExecutionBundle(
            "1",
            prepared.bundle().approvalRef(),
            prepared.bundle().designPlanReportRef(),
            prepared.bundle().designPlanReportHash(),
            prepared.bundle().designExecutionPlanRef(),
            prepared.bundle().designExecutionPlanHash(),
            prepared.bundle().runManifestRef(),
            prepared.bundle().graphRef(),
            "foreign-graph-digest",
            prepared.bundle().orderedGraphPatchesRef(),
            prepared.bundle().orderedPatchDigest(),
            prepared.bundle().graphValidationRef(),
            prepared.bundle().planValidationRef(),
            prepared.bundle().compilerValidationRef(),
            prepared.bundle().executorValidationRef());
    Reference foreignBundleRef = append(Kind.VALIDATED_EXECUTION_BUNDLE, "1", foreign, List.of());
    CapabilitySignal.Completed completed =
        completed(capability.execute(v2Context(prepared, prepared.requestRef(), foreignBundleRef)));

    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, completed.outcome().outcomeClass());
    assertTrue(
        completed.outcome().message().toLowerCase().contains("does not reference the validated bundle"));
    verify(materializer, never()).resume(any(), any());
  }

  @Test
  void v2AcceptsReproducedValidatedBundleSameContentHash() {
    PreparedV2Inputs prepared = appendHappyPathV2Inputs();
    Reference reproducedBundleRef =
        append(Kind.VALIDATED_EXECUTION_BUNDLE, "1", prepared.bundle(), List.of());
    assertNotEquals(prepared.bundleRef().artifactId(), reproducedBundleRef.artifactId());
    assertEquals(prepared.bundleRef().contentHash(), reproducedBundleRef.contentHash());
    assertNotEquals(prepared.request().validatedExecutionBundleRef(), reproducedBundleRef);

    appendWaitingCheckpoint(prepared.approvalRef(), prepared);
    StageExecutionContext context =
        v2Context(prepared, prepared.requestRef(), reproducedBundleRef);
    MaterializationResult readBack =
        new MaterializationResult(
            1,
            "catalog-chain-1",
            new MaterializationMap(
                "catalog-chain-1",
                Map.of("trigger-1", "catalog-trigger-1", "script-1", "catalog-script-1"), Map.of(), Map.of()),
            prepared.request().graphDigest(),
            MaterializationPhase.READ_BACK);
    MaterializationResult reconciled =
        new MaterializationResult(
            1,
            "catalog-chain-1",
            readBack.materializationMap(),
            prepared.request().graphDigest(),
            MaterializationPhase.RECONCILE);
    MaterializationResult complete =
        new MaterializationResult(
            1,
            "catalog-chain-1",
            readBack.materializationMap(),
            prepared.request().graphDigest(),
            MaterializationPhase.COMPLETE);
    when(materializer.resume(any(), any())).thenReturn(readBack);
    when(materializer.markReconciled(any(), any())).thenReturn(reconciled);
    when(materializer.markComplete(any(), any())).thenReturn(complete);
    when(factsService.load("catalog-chain-1"))
        .thenReturn(
            new org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFacts(
                "catalog-chain-1",
                "demo-chain",
                "Demo",
                2,
                0,
                "",
                List.of(),
                List.of(),
                "built_in_catalog"));
    when(reconcileService.compare(any(), any(), any()))
        .thenReturn(
            new org.qubership.integration.platform.ai.skill.orchestration.ReconcileResult(
                true, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "ok"));

    CapabilitySignal.Completed completed = completed(capability.execute(context));

    assertEquals(
        StageOutcomeClass.SUCCEEDED,
        completed.outcome().outcomeClass(),
        () -> String.valueOf(completed.outcome().message()));
    verify(materializer).resume(any(), any());
  }

  @Test
  void v2RejectsStaleDesignPlanReportHash() {
    PreparedV2Inputs prepared = appendHappyPathV2Inputs();
    MaterializationRequest stale =
        new MaterializationRequest(
            "1",
            prepared.request().approvalRef(),
            new Reference(
                Kind.DESIGN_PLAN_REPORT,
                prepared.request().designPlanReportRef().artifactId(),
                "stale-report-hash"),
            prepared.request().designExecutionPlanRef(),
            prepared.request().graphDigest(),
            prepared.request().orderedPatchDigest(),
            prepared.request().validatedExecutionBundleRef());
    Reference staleRequestRef = append(Kind.MATERIALIZATION_REQUEST, "1", stale, List.of());
    CapabilitySignal.Completed completed =
        completed(capability.execute(v2Context(prepared, staleRequestRef, prepared.bundleRef())));

    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, completed.outcome().outcomeClass());
    verify(materializer, never()).resume(any(), any());
  }

  @Test
  void v2RejectsStaleProjectionHash() {
    PreparedV2Inputs prepared = appendHappyPathV2Inputs();
    MaterializationRequest stale =
        new MaterializationRequest(
            "1",
            prepared.request().approvalRef(),
            prepared.request().designPlanReportRef(),
            new Reference(
                Kind.DESIGN_EXECUTION_PLAN,
                prepared.request().designExecutionPlanRef().artifactId(),
                "stale-plan-hash"),
            prepared.request().graphDigest(),
            prepared.request().orderedPatchDigest(),
            prepared.request().validatedExecutionBundleRef());
    Reference staleRequestRef = append(Kind.MATERIALIZATION_REQUEST, "1", stale, List.of());
    CapabilitySignal.Completed completed =
        completed(capability.execute(v2Context(prepared, staleRequestRef, prepared.bundleRef())));

    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, completed.outcome().outcomeClass());
    verify(materializer, never()).resume(any(), any());
  }

  @Test
  void v2RejectsGraphDigestMismatch() {
    PreparedV2Inputs prepared = appendHappyPathV2Inputs();
    MaterializationRequest mismatched =
        new MaterializationRequest(
            "1",
            prepared.request().approvalRef(),
            prepared.request().designPlanReportRef(),
            prepared.request().designExecutionPlanRef(),
            "other-graph-digest",
            prepared.request().orderedPatchDigest(),
            prepared.request().validatedExecutionBundleRef());
    Reference badRequestRef = append(Kind.MATERIALIZATION_REQUEST, "1", mismatched, List.of());
    CapabilitySignal.Completed completed =
        completed(capability.execute(v2Context(prepared, badRequestRef, prepared.bundleRef())));

    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, completed.outcome().outcomeClass());
    verify(materializer, never()).resume(any(), any());
  }

  @Test
  void v2RejectsOrderedPatchDigestMismatch() {
    PreparedV2Inputs prepared = appendHappyPathV2Inputs();
    MaterializationRequest mismatched =
        new MaterializationRequest(
            "1",
            prepared.request().approvalRef(),
            prepared.request().designPlanReportRef(),
            prepared.request().designExecutionPlanRef(),
            prepared.request().graphDigest(),
            "other-patch-digest",
            prepared.request().validatedExecutionBundleRef());
    Reference badRequestRef = append(Kind.MATERIALIZATION_REQUEST, "1", mismatched, List.of());
    CapabilitySignal.Completed completed =
        completed(capability.execute(v2Context(prepared, badRequestRef, prepared.bundleRef())));

    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, completed.outcome().outcomeClass());
    verify(materializer, never()).resume(any(), any());
  }

  @Test
  void v2RejectsFailedExecutorValidationPass() {
    PreparedV2Inputs prepared = appendHappyPathV2Inputs();
    ExecutorValidationBundle failed =
        new ExecutorValidationBundle(
            "1",
            prepared.request().graphDigest(),
            prepared.request().designPlanReportRef(),
            prepared.request().designPlanReportRef().contentHash(),
            prepared.request().designExecutionPlanRef(),
            prepared.request().designExecutionPlanRef().contentHash(),
            false,
            List.of("failed"));
    Reference failedExecutorRef = append(Kind.EXECUTOR_VALIDATION_BUNDLE, "1", failed, List.of());
    ValidatedExecutionBundle badBundle =
        new ValidatedExecutionBundle(
            "1",
            prepared.bundle().approvalRef(),
            prepared.bundle().designPlanReportRef(),
            prepared.bundle().designPlanReportHash(),
            prepared.bundle().designExecutionPlanRef(),
            prepared.bundle().designExecutionPlanHash(),
            prepared.bundle().runManifestRef(),
            prepared.bundle().graphRef(),
            prepared.bundle().graphDigest(),
            prepared.bundle().orderedGraphPatchesRef(),
            prepared.bundle().orderedPatchDigest(),
            prepared.bundle().graphValidationRef(),
            prepared.bundle().planValidationRef(),
            prepared.bundle().compilerValidationRef(),
            failedExecutorRef);
    Reference badBundleRef = append(Kind.VALIDATED_EXECUTION_BUNDLE, "1", badBundle, List.of());
    MaterializationRequest request =
        new MaterializationRequest(
            "1",
            prepared.request().approvalRef(),
            prepared.request().designPlanReportRef(),
            prepared.request().designExecutionPlanRef(),
            prepared.request().graphDigest(),
            prepared.request().orderedPatchDigest(),
            badBundleRef);
    Reference requestRef = append(Kind.MATERIALIZATION_REQUEST, "1", request, List.of());
    CapabilitySignal.Completed completed =
        completed(capability.execute(v2Context(prepared, requestRef, badBundleRef)));

    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, completed.outcome().outcomeClass());
    assertTrue(completed.outcome().message().toLowerCase().contains("executor"));
    verify(materializer, never()).resume(any(), any());
  }

  @Test
  void v2RejectsMissingApprovalCandidate() {
    PreparedV2Inputs prepared = appendHappyPathV2Inputs();
    ApprovalRecordV2 incomplete =
        new ApprovalRecordV2(
            prepared.implementationPlanRef(),
            prepared.implementationPlanRef().contentHash(),
            List.of(prepared.implementationPlanRef()),
            "user",
            null,
            FIXED,
            ApprovalPolicy.CATALOG_FIRST_V1,
            ApprovalPolicy.CATALOG_FIRST_V1_HASH,
            null,
            null,
            null,
            null,
            null,
            null);
    Reference incompleteApprovalRef = append(Kind.APPROVAL_RECORD, "2", incomplete, List.of());
    ValidatedExecutionBundle badBundle =
        new ValidatedExecutionBundle(
            "1",
            incompleteApprovalRef,
            prepared.bundle().designPlanReportRef(),
            prepared.bundle().designPlanReportHash(),
            prepared.bundle().designExecutionPlanRef(),
            prepared.bundle().designExecutionPlanHash(),
            prepared.bundle().runManifestRef(),
            prepared.bundle().graphRef(),
            prepared.bundle().graphDigest(),
            prepared.bundle().orderedGraphPatchesRef(),
            prepared.bundle().orderedPatchDigest(),
            prepared.bundle().graphValidationRef(),
            prepared.bundle().planValidationRef(),
            prepared.bundle().compilerValidationRef(),
            prepared.bundle().executorValidationRef());
    Reference badBundleRef = append(Kind.VALIDATED_EXECUTION_BUNDLE, "1", badBundle, List.of());
    MaterializationRequest request =
        new MaterializationRequest(
            "1",
            incompleteApprovalRef,
            prepared.request().designPlanReportRef(),
            prepared.request().designExecutionPlanRef(),
            prepared.request().graphDigest(),
            prepared.request().orderedPatchDigest(),
            badBundleRef);
    Reference requestRef = append(Kind.MATERIALIZATION_REQUEST, "1", request, List.of());
    CapabilitySignal.Completed completed =
        completed(capability.execute(v2Context(prepared, requestRef, badBundleRef)));

    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, completed.outcome().outcomeClass());
    assertTrue(completed.outcome().message().toLowerCase().contains("candidate"));
    verify(materializer, never()).resume(any(), any());
  }

  @Test
  void v2SuccessfulReadbackMovesExecutorCheckpointToComplete() {
    PreparedV2Inputs prepared = appendHappyPathV2Inputs();
    appendWaitingCheckpoint(prepared.approvalRef(), prepared);
    StageExecutionContext context =
        v2Context(prepared, prepared.requestRef(), prepared.bundleRef());
    MaterializationResult readBack =
        new MaterializationResult(
            1,
            "catalog-chain-1",
            new MaterializationMap(
                "catalog-chain-1",
                Map.of("trigger-1", "catalog-trigger-1", "script-1", "catalog-script-1"), Map.of(), Map.of()),
            prepared.request().graphDigest(),
            MaterializationPhase.READ_BACK);
    MaterializationResult reconciled =
        new MaterializationResult(
            1,
            "catalog-chain-1",
            readBack.materializationMap(),
            prepared.request().graphDigest(),
            MaterializationPhase.RECONCILE);
    MaterializationResult complete =
        new MaterializationResult(
            1,
            "catalog-chain-1",
            readBack.materializationMap(),
            prepared.request().graphDigest(),
            MaterializationPhase.COMPLETE);
    when(materializer.resume(any(), any())).thenReturn(readBack);
    when(materializer.markReconciled(any(), any())).thenReturn(reconciled);
    when(materializer.markComplete(any(), any())).thenReturn(complete);
    when(factsService.load("catalog-chain-1"))
        .thenReturn(
            new org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFacts(
                "catalog-chain-1",
                "demo-chain",
                "Demo",
                2,
                0,
                "",
                List.of(),
                List.of(),
                "built_in_catalog"));
    when(reconcileService.compare(any(), any(), any()))
        .thenReturn(
            new org.qubership.integration.platform.ai.skill.orchestration.ReconcileResult(
                true, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "ok"));

    CapabilitySignal.Completed completed = completed(capability.execute(context));

    assertEquals(
        StageOutcomeClass.SUCCEEDED,
        completed.outcome().outcomeClass(),
        () -> String.valueOf(completed.outcome().message()));
    assertTrue(
        completed.outcome().candidates().stream()
            .anyMatch(candidate -> candidate.kind() == Kind.DESIGN_EXECUTION_RESULT));
    DesignExecutionCheckpoint checkpoint =
        artifactStore
            .latest(RUN_ID, Kind.DESIGN_EXECUTION_CHECKPOINT)
            .map(revision -> artifactStore.payload(revision, DesignExecutionCheckpoint.class))
            .orElseThrow();
    assertEquals(DesignExecutionPhase.COMPLETE, checkpoint.phase());
  }

  private PreparedInputs appendHappyPathInputs() {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo-chain", "Demo"),
            List.of(
                new ChainPlanNode("trigger-1", "http-trigger", "Trigger", null, null, List.of()),
                new ChainPlanNode("script-1", "script", "Script", "trigger-1", null, List.of())),
            List.of());
    String graphDigest =
        new org.qubership.integration.platform.ai.qipknowledge.patch.CanonicalGraphDigest(
                new ObjectMapper())
            .sha256(graph);
    GraphAssemblyResult assembly =
        new GraphAssemblyResult(1, graph, graphDigest, List.of(), List.of(), List.of());
    CompilerValidationBundle compilerBundle =
        new CompilerValidationBundle(
            1,
            graphDigest,
            List.of(new CompilerValidationPass("validator", new ValidationResult(true, List.of(), "ok"))));
    ImplementationPlan implementationPlan =
        ImplementationPlan.schemaVersion2(
            "Plan",
            "planning",
            "1",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of());
    PlanValidationResult validationResult = new PlanValidationResult(List.of());

    Reference implementationPlanRef = append(Kind.IMPLEMENTATION_PLAN, "2", implementationPlan, List.of());
    Reference graphRef = append(Kind.CHAIN_PLAN_GRAPH, "1", graph, List.of());
    Reference assemblyRef = append(Kind.GRAPH_ASSEMBLY_RESULT, "1", assembly, List.of(graphRef));
    Reference bundleRef = append(Kind.COMPILER_VALIDATION_BUNDLE, "1", compilerBundle, List.of(graphRef));
    Reference validationRef =
        append(Kind.PLAN_VALIDATION_RESULT, "1", validationResult, List.of(implementationPlanRef));
    Reference runManifestRef = append(Kind.RUN_MANIFEST, "1", runManifest(), List.of());
    List<Reference> approvedCandidates =
        List.of(implementationPlanRef, validationRef, graphRef, assemblyRef, bundleRef);
    ApprovalRecordV2 approval =
        new ApprovalRecordV2(
            implementationPlanRef,
            implementationPlanRef.contentHash(),
            approvedCandidates,
            "user",
            null,
            FIXED,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    Reference approvalRef = append(Kind.APPROVAL_RECORD, "2", approval, approvedCandidates);
    return new PreparedInputs(
        graphRef,
        assemblyRef,
        bundleRef,
        validationRef,
        implementationPlanRef,
        runManifestRef,
        approvalRef,
        approvedCandidates,
        graphDigest);
  }

  private StageExecutionContext contextWith(PreparedInputs prepared, Reference approvalRef) {
    return new StageExecutionContext(
        RUN_ID,
        "conv-1",
        "materialization",
        RUN_ID,
        "attempt-1",
        null,
        runManifest(),
        List.of(
            prepared.implementationPlanRef(),
            prepared.validationRef(),
            prepared.graphRef(),
            prepared.assemblyRef(),
            prepared.bundleRef(),
            approvalRef,
            prepared.runManifestRef()),
        Map.of());
  }

  private Reference append(
      Kind kind, String schemaVersion, Object payload, List<Reference> inputs) {
    Revision revision =
        artifactStore.append(
            new AppendCommand(
                RUN_ID,
                kind,
                schemaVersion,
                "test-producer",
                "1",
                payload,
                inputs,
                null,
                new ArtifactProvenance(RUN_ID, "materialization", "create-chain", "1", "profile-sha", "test", "1", "closure-sha")));
    return revision.reference();
  }

  private static CapabilitySignal.Completed completed(Multi<CapabilitySignal> stream) {
    return stream.collect().asList().await().indefinitely().stream()
        .filter(CapabilitySignal.Completed.class::isInstance)
        .map(CapabilitySignal.Completed.class::cast)
        .findFirst()
        .orElseThrow();
  }

  private static String sha256Hex(String value) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static RunManifest runManifest() {
    return new RunManifest(
        RUN_ID,
        null,
        List.of(),
        "product",
        "create-chain",
        "1",
        "profile-sha",
        "baseline",
        "baseline-sha",
        List.of(new DependencyClosureEntry("materialization", "1", "skill-catalog-sha")),
        "closure-sha",
        new KnowledgePackageRef(
            "knowledge-1",
            "1",
            "1.0.0",
            "checksum",
            "CERTIFIED",
            "sha256:certificate"),
        "24.4",
        List.of(new ArtifactTypeRef("implementation-plan", 2)),
        null);
  }

  private static RunManifest runManifestV2() {
    return new RunManifest(
        RUN_ID,
        null,
        List.of(),
        "product",
        "create-chain",
        "2",
        "profile-sha",
        "baseline",
        "baseline-sha",
        List.of(new DependencyClosureEntry("materialization", "1", "skill-catalog-sha")),
        "closure-sha",
        new KnowledgePackageRef(
            "knowledge-1",
            "1",
            "1.0.0",
            "checksum",
            "CERTIFIED",
            "sha256:certificate"),
        "24.4",
        List.of(new ArtifactTypeRef("implementation-plan", 2)),
        null);
  }

  private PreparedV2Inputs appendHappyPathV2Inputs() {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo-chain", "Demo"),
            List.of(
                new ChainPlanNode("trigger-1", "http-trigger", "Trigger", null, null, List.of()),
                new ChainPlanNode("script-1", "script", "Script", "trigger-1", null, List.of())),
            List.of());
    String graphDigest =
        new org.qubership.integration.platform.ai.qipknowledge.patch.CanonicalGraphDigest(
                new ObjectMapper())
            .sha256(graph);
    String orderedPatchDigest = sha256Hex("[]");
    GraphAssemblyResult assembly =
        new GraphAssemblyResult(1, graph, graphDigest, List.of(), List.of(), List.of());
    CompilerValidationBundle compilerBundle =
        new CompilerValidationBundle(
            1,
            graphDigest,
            List.of(new CompilerValidationPass("validator", new ValidationResult(true, List.of(), "ok"))));
    PlanValidationResult validationResult = new PlanValidationResult(List.of());
    ImplementationPlan implementationPlan =
        ImplementationPlan.schemaVersion2(
            "Plan",
            "planning",
            "1",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of());

    Reference implementationPlanRef = append(Kind.IMPLEMENTATION_PLAN, "2", implementationPlan, List.of());
    Reference reportRef =
        append(Kind.DESIGN_PLAN_REPORT, "1", Map.of("markdown", "# plan"), List.of());
    Reference planRef =
        append(Kind.DESIGN_EXECUTION_PLAN, "1", Map.of("flowId", "flow-1"), List.of());
    Reference graphRef = append(Kind.CHAIN_PLAN_GRAPH, "1", graph, List.of());
    Reference assemblyRef = append(Kind.GRAPH_ASSEMBLY_RESULT, "1", assembly, List.of(graphRef));
    Reference orderedPatchesRef =
        append(Kind.ORDERED_GRAPH_PATCHES, "1", new OrderedGraphPatches("1", List.of()), List.of());
    Reference compilerBundleRef =
        append(Kind.COMPILER_VALIDATION_BUNDLE, "1", compilerBundle, List.of(graphRef));
    Reference validationRef =
        append(Kind.PLAN_VALIDATION_RESULT, "1", validationResult, List.of(implementationPlanRef));
    Reference runManifestRef = append(Kind.RUN_MANIFEST, "1", runManifestV2(), List.of());
    List<Reference> approvedCandidates =
        List.of(implementationPlanRef, reportRef, planRef);
    ApprovalRecordV2 approval =
        new ApprovalRecordV2(
            implementationPlanRef,
            implementationPlanRef.contentHash(),
            approvedCandidates,
            "user",
            null,
            FIXED,
            ApprovalPolicy.CATALOG_FIRST_V1,
            ApprovalPolicy.CATALOG_FIRST_V1_HASH,
            null,
            null,
            null,
            null,
            null,
            null);
    Reference approvalRef = append(Kind.APPROVAL_RECORD, "2", approval, approvedCandidates);
    ExecutorValidationBundle executorValidation =
        new ExecutorValidationBundle(
            "1",
            graphDigest,
            reportRef,
            reportRef.contentHash(),
            planRef,
            planRef.contentHash(),
            true,
            List.of());
    Reference executorValidationRef =
        append(Kind.EXECUTOR_VALIDATION_BUNDLE, "1", executorValidation, List.of());
    ValidatedExecutionBundle bundle =
        new ValidatedExecutionBundle(
            "1",
            approvalRef,
            reportRef,
            reportRef.contentHash(),
            planRef,
            planRef.contentHash(),
            runManifestRef,
            graphRef,
            graphDigest,
            orderedPatchesRef,
            orderedPatchDigest,
            validationRef,
            validationRef,
            compilerBundleRef,
            executorValidationRef);
    Reference bundleRef = append(Kind.VALIDATED_EXECUTION_BUNDLE, "1", bundle, List.of());
    MaterializationRequest request =
        new MaterializationRequest(
            "1",
            approvalRef,
            reportRef,
            planRef,
            graphDigest,
            orderedPatchDigest,
            bundleRef);
    Reference requestRef = append(Kind.MATERIALIZATION_REQUEST, "1", request, List.of());
    return new PreparedV2Inputs(
        request,
        requestRef,
        bundle,
        bundleRef,
        implementationPlanRef,
        approvalRef,
        runManifestRef,
        assemblyRef);
  }

  private void appendWaitingCheckpoint(Reference approvalRef, PreparedV2Inputs prepared) {
    DesignExecutionCheckpoint checkpoint =
        new DesignExecutionCheckpoint(
            "1",
            approvalRef,
            prepared.request().designPlanReportRef().contentHash(),
            prepared.request().designExecutionPlanRef().contentHash(),
            "manifest-hash",
            DesignExecutionPhase.WAITING_FOR_MATERIALIZATION,
            List.of(
                new DesignExecutionCheckpoint.CompletedStep(
                    "phase-5",
                    List.of(
                        prepared.request().designPlanReportRef().contentHash(),
                        prepared.request().designExecutionPlanRef().contentHash()),
                    List.of(prepared.bundleRef()),
                    List.of(prepared.bundleRef().contentHash()),
                    new ArtifactProvenance(
                        RUN_ID,
                        "design-execution",
                        "create-chain",
                        "2",
                        "profile-sha",
                        "design-execution",
                        "1",
                        "closure"),
                    "WAITING_FOR_MATERIALIZATION")));
    append(Kind.DESIGN_EXECUTION_CHECKPOINT, "1", checkpoint, List.of());
  }

  private StageExecutionContext v2Context(
      PreparedV2Inputs prepared, Reference requestRef, Reference bundleRef) {
    return new StageExecutionContext(
        RUN_ID,
        "conv-1",
        "materialization",
        RUN_ID,
        "attempt-1",
        null,
        runManifestV2(),
        List.of(requestRef, bundleRef, prepared.runManifestRef()),
        Map.of());
  }

  private record PreparedInputs(
      Reference graphRef,
      Reference assemblyRef,
      Reference bundleRef,
      Reference validationRef,
      Reference implementationPlanRef,
      Reference runManifestRef,
      Reference approvalRef,
      List<Reference> approvedCandidates,
      String graphDigest) {}

  private record PreparedV2Inputs(
      MaterializationRequest request,
      Reference requestRef,
      ValidatedExecutionBundle bundle,
      Reference bundleRef,
      Reference implementationPlanRef,
      Reference approvalRef,
      Reference runManifestRef,
      Reference assemblyRef) {}
}
