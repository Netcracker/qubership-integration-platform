package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.qubership.integration.platform.ai.catalog.binding.ResolvedServiceCallBinding;
import org.qubership.integration.platform.ai.compiler.CompilerSkillContextBuilder;
import org.qubership.integration.platform.ai.compiler.CompilerSkillRuntimeEligibility;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonContext;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonRepository;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.AppendCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Revision;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.catalog.CompilerSkillCatalog;
import org.qubership.integration.platform.ai.compiler.contract.ClasspathCompilerContractRepository;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerNodeExecutionMode;
import org.qubership.integration.platform.ai.compiler.policy.CompilerGeneratorSpecIndex;
import org.qubership.integration.platform.ai.plan.ChainPlanStore;
import org.qubership.integration.platform.ai.plan.ImplementationPlan;
import org.qubership.integration.platform.ai.plan.PlanCompilationTestSupport;
import org.qubership.integration.platform.ai.plan.mapping.schema.MappingSchemaSide;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.productpipeline.artifact.ApprovalRecordV2;
import org.qubership.integration.platform.ai.productpipeline.artifact.ArtifactProvenance;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerRunPin;
import org.qubership.integration.platform.ai.productpipeline.artifact.DependencyClosureEntry;
import org.qubership.integration.platform.ai.productpipeline.artifact.MappingValidationDetails;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationFinding;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerDag;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerNode;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.capability.ArtifactCandidate;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCause;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCauseCode;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapability;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapabilityRegistry;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerNodeExecutionAdapterRegistry;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerValidationPipeline;
import org.qubership.integration.platform.ai.productpipeline.create.DefaultCompilerDagExecutionEngine;
import org.qubership.integration.platform.ai.productpipeline.create.FailureNarrative;
import org.qubership.integration.platform.ai.productpipeline.create.FakeFailureNarrativeAgent;
import org.qubership.integration.platform.ai.productpipeline.create.GraphAssemblyService;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.ApprovedCompilerExecutionRunner;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.BindingResolutionResult;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.ChainSemanticGraphCompiler;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.CipDesignExecutorJavaAdapter;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.CipDesignExecutorJavaAdapter.ExecutionInputs;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.CipDesignExecutorJavaAdapter.ExecutionResult;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.DefaultApprovedCompilerExecutionRunner;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.ExecutorCatalogBindingAdapter;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignExecutionPlan;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanReport;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.IdsDocument;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticFixtures;
import org.qubership.integration.platform.ai.productpipeline.runtime.AcceptInputCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.ApproveCommand;
import org.qubership.integration.platform.ai.productpipeline.runtime.CreateChainTestOrchestrator;
import org.qubership.integration.platform.ai.productpipeline.runtime.ProductPipelineRunSupport;
import org.qubership.integration.platform.ai.productpipeline.runtime.StartOrResumeCommand;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeClient;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeContextProvider;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ApprovalPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProfileStage;
import org.qubership.integration.platform.ai.productpipeline.profile.RetryPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.TerminalPolicy;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryAction;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryCauseClass;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryDecision;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryEvidence;
import org.qubership.integration.platform.ai.productpipeline.recovery.SemanticFinding;
import org.qubership.integration.platform.ai.productpipeline.stage.StageDecision;
import org.qubership.integration.platform.ai.productpipeline.stage.StageExecutionResult;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingRuleStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackRepository;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackVersion;
import org.qubership.integration.platform.ai.qipknowledge.patch.CanonicalGraphDigest;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipPolicy;
import org.qubership.integration.platform.ai.qipknowledge.validation.CompilerPlanValidator;
import org.qubership.integration.platform.ai.qipknowledge.validation.CompilerQualityValidator;
import org.qubership.integration.platform.ai.qipknowledge.validation.CompilerSecurityValidator;
import org.qubership.integration.platform.ai.qipknowledge.validation.PlanGraphValidationInput;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationResult;
import org.qubership.integration.platform.ai.skill.executor.SkillExecutionResult;
import org.qubership.integration.platform.ai.skill.executor.SkillExecutor;
import org.qubership.integration.platform.ai.skill.executor.SkillExecutorKind;
import org.qubership.integration.platform.ai.skill.orchestration.SkillRunContext;
import org.qubership.integration.platform.ai.skill.registry.SkillExecutorRegistry;
import org.qubership.integration.platform.ai.skill.workspace.InMemorySkillWorkspaceStore;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactType;
import org.qubership.integration.platform.ai.skill.workspace.SkillWorkspace;
import org.qubership.integration.platform.ai.plan.mapping.MappingGenerationPipeline;

/**
 * Brief-owned mapping rejection reopens the brief producer. Java chooses owner and action before
 * advisory diagnosis. Production transport is pipeline to blocked exception to adapter.
 */
class MappingContractBriefProducerRecoveryTest {

  private static final Instant FIXED = Instant.parse("2026-09-10T21:00:00Z");
  private static final String RUN_ID = "run-mapping-brief-producer-1";
  private static final String CONV_ID = "conv-mapping-brief-producer-1";
  private static final String CATALOG_HASH = "catalog-hash";
  private static final String SKILL_HASH = "skill-hash-script";
  private static final String ADDON_HASH = "addon-hash-script";
  private static final ObjectMapper MAPPER =
      new ObjectMapper().registerModule(new JavaTimeModule());

  private ProductPipelineRunStore runStore;
  private ProductPipelineArtifactStore artifactStore;
  private CompilationArtifacts artifacts;
  private CipDesignExecutorJavaAdapter adapter;
  private ProductPipelineProfile profile;

  @BeforeEach
  void setUp() throws Exception {
    PlanCompilationTestSupport.memory();
    Clock clock = Clock.fixed(FIXED, ZoneOffset.UTC);
    InMemoryArtifactBlobStore blobStore = new InMemoryArtifactBlobStore();
    artifacts = new CompilationArtifacts(blobStore, MAPPER, clock);
    artifactStore = new ProductPipelineArtifactStore(artifacts);
    runStore = new ProductPipelineRunStore(blobStore, MAPPER, clock);
    profile = threeStageProfile();
    adapter = productionAdapter(clock);
    persistSide("trigger-http", MappingPort.OUTPUT, sourceSchema());
    persistSide("call-1", MappingPort.REQUEST, targetSchema());
  }

  @Test
  void productionTransportReopensTheBriefProducer() throws Exception {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.owner("Ask which field to invent.", "design-planning")
            .recoverReturns(askUserDecision());
    CreateChainTestOrchestrator runtime = runtime(agent, productionExecution());
    haltAtMappingContract(runtime);

    StageExecutionResult failed = execute(runtime, "design-execution");
    assertBriefProducerRoute(runtime, failed, agent);
    assertPersistedMappingEvidence();
  }

  @ParameterizedTest
  @EnumSource(AdvisoryConflict.class)
  void conflictingOrMissingAdvisoryKeepsMappingContractAndBriefProducer(AdvisoryConflict conflict)
      throws Exception {
    FakeFailureNarrativeAgent agent = conflict.agent();
    CreateChainTestOrchestrator runtime = runtime(agent, injectedMappingExecution());
    haltAtMappingContract(runtime);

    StageExecutionResult failed = execute(runtime, "design-execution");
    assertBriefProducerRoute(runtime, failed, agent);
    assertPersistedMappingEvidence();
  }

  private enum AdvisoryConflict {
    MISSING,
    ASK_USER,
    PARK,
    REGENERATE,
    INVALID;

    private FakeFailureNarrativeAgent agent() {
      FakeFailureNarrativeAgent agent =
          FakeFailureNarrativeAgent.owner("Conflicting advisory owner.", "design-planning");
      return switch (this) {
        case MISSING -> agent;
        case ASK_USER -> agent.recoverReturns(askUserDecision());
        case PARK ->
            agent.recoverReturns(
                new RecoveryDecision(
                    RecoveryCauseClass.UNCLASSIFIED,
                    null,
                    List.of(),
                    RecoveryAction.PARK,
                    List.of(),
                    "",
                    "Park this mapping halt."));
        case REGENERATE -> agent.recoverRegenerates(Kind.CHAIN_PLAN_GRAPH, "Regenerate the graph.");
        case INVALID ->
            agent.recoverReturns(
                new RecoveryDecision(
                    RecoveryCauseClass.BRIEF_DEFECT,
                    new Reference(Kind.IMPLEMENTATION_PLAN, "plan-1", "plan-hash"),
                    List.of("missing-failure"),
                    RecoveryAction.REVISE_BRIEF,
                    List.of(),
                    "",
                    "Revise from an invalid evidence ref."));
      };
    }
  }

  private static RecoveryDecision askUserDecision() {
    return new RecoveryDecision(
        RecoveryCauseClass.DERIVATION_DEFECT,
        new Reference(Kind.CHAIN_PLAN_GRAPH, "graph-1", "graph-hash"),
        List.of("failure-1"),
        RecoveryAction.ASK_USER,
        List.of(),
        "Which target should we add?",
        "Ask the author for a field.");
  }

  private void haltAtMappingContract(CreateChainTestOrchestrator runtime) {
    runtime
        .startOrResume(new StartOrResumeCommand(CONV_ID, RUN_ID, profile, manifest()))
        .collect()
        .asList()
        .await()
        .indefinitely();
    if (run().run().status() == RunStatus.WAITING_FOR_INPUT) {
      runtime
          .recordInput(new AcceptInputCommand(RUN_ID, "create a Salesforce task chain"))
          .collect()
          .asList()
          .await()
          .indefinitely();
    }
    assertEquals(RunStatus.WAITING_FOR_APPROVAL, run().run().status());
    runtime
        .recordApprove(
            new ApproveCommand(
                RUN_ID,
                snapshot("requirement-analysis").approvableReference(),
                run().run().runRevision()))
        .collect()
        .asList()
        .await()
        .indefinitely();
    applyLifecycle(runtime, execute(runtime, "design-input"));
  }

  private void assertBriefProducerRoute(
      CreateChainTestOrchestrator runtime,
      StageExecutionResult failed,
      FakeFailureNarrativeAgent agent) {
    StageDecision.ReopenProducer reopen =
        assertInstanceOf(StageDecision.ReopenProducer.class, failed.decision());
    assertEquals("requirement-analysis", reopen.producerStageId());
    assertEquals(
        RecoveryCauseCode.MAPPING_CONTRACT.name(),
        String.valueOf(
            runtime
                .support()
                .runAttributes(RUN_ID)
                .get(ProductPipelineRunSupport.STAGE_ERROR_CAUSE_CODE_ATTR)));
    assertNotEquals(
        RecoveryCauseCode.MISSING_BRIEF_FACTS.name(),
        String.valueOf(
            runtime
                .support()
                .runAttributes(RUN_ID)
                .get(ProductPipelineRunSupport.STAGE_ERROR_CAUSE_CODE_ATTR)));
    assertEquals(
        "requirement-analysis", runtime.support().diagnosedOwnerStageId(RUN_ID).orElseThrow());
    assertEquals(null, agent.lastRecoveryContextJson.get());
    assertFalse(failed.decision() instanceof StageDecision.WaitForInput);
    String findings =
        String.valueOf(
            runtime
                .support()
                .runAttributes(RUN_ID)
                .get(ProductPipelineRunSupport.STAGE_ERROR_FINDINGS_ATTR));
    assertTrue(findings.contains("MAPPING_UNKNOWN_TARGET"), findings);
    assertFalse(
        findings.toLowerCase(Locale.ROOT).contains("which target should we add"), findings);
  }

  private void assertPersistedMappingEvidence() throws Exception {
    List<Revision> history = artifactStore.history(RUN_ID, Kind.RECOVERY_EVIDENCE);
    assertEquals(1, history.size());
    RecoveryEvidence evidence = artifactStore.payload(history.getFirst(), RecoveryEvidence.class);
    assertEquals("design-execution", evidence.observingStageId());
    assertEquals("requirement-analysis", evidence.producerStageId());
    assertEquals("MAPPING_CONTRACT", evidence.observedCauseCode());
    SemanticFinding finding = evidence.findings().getFirst();
    JsonNode payload = MAPPER.readTree(finding.rawValidatorJson());
    assertTrue(payload.isObject(), finding.rawValidatorJson());
    assertEquals(
        "$.preserved.executionId", payload.path("mappingDetails").path("targetPath").asText());
    assertFalse(payload.path("mappingDetails").path("consumedBriefArtifactId").asText().isBlank());
    assertEquals("call-1", payload.path("mappingDetails").path("targetSchemaOwner").asText());
  }

  private CreateChainTestOrchestrator runtime(
      FakeFailureNarrativeAgent agent, StageCapability execution) {
    ProductPipelineRunSupport support =
        ProductPipelineRunSupport.builder(
                runStore,
                artifactStore,
                new StageCapabilityRegistry(
                    List.of(analysisCapability(), designInputCapability(), execution)),
                Clock.fixed(FIXED, ZoneOffset.UTC))
            .failureNarrative(new FailureNarrative(agent))
            .build();
    return new CreateChainTestOrchestrator(support, runStore);
  }

  private StageCapability analysisCapability() {
    return capability(
        "analysis-cap",
        context ->
            Multi.createFrom()
                .item(
                    new CapabilitySignal.Completed(
                        new StageOutcome(
                            StageOutcomeClass.CANDIDATE,
                            List.of(
                                new ArtifactCandidate(
                                    Kind.REQUIREMENT_BRIEF, unknownTargetBrief(), List.of())),
                            "brief ready",
                            null))));
  }

  private StageCapability designInputCapability() {
    return capability(
        "design-input-cap",
        context ->
            Multi.createFrom()
                .item(
                    new CapabilitySignal.Completed(
                        new StageOutcome(
                            StageOutcomeClass.SUCCEEDED,
                            List.of(
                                new ArtifactCandidate(
                                    Kind.CHAIN_SEMANTIC_REVISION,
                                    revisionWith(unknownTargetIntent()),
                                    List.of())),
                            "revision ready",
                            null))));
  }

  private StageCapability injectedMappingExecution() {
    return capability(
        "execution-cap",
        context ->
            Multi.createFrom()
                .item(
                    new CapabilitySignal.Completed(
                        StageOutcome.of(
                            StageOutcomeClass.VALIDATION_FAILURE,
                            "mapping contract rejected",
                            RecoveryCause.mappingContract(List.of(unknownTargetFinding()))))));
  }

  private StageCapability productionExecution() {
    return capability(
        "execution-cap",
        context -> {
          ExecutionResult result = adapter.executeAfterApproval(productionInputs());
          return Multi.createFrom()
              .item(
                  new CapabilitySignal.Completed(
                      new StageOutcome(
                          result.outcomeClass(),
                          result.candidates() == null ? List.of() : result.candidates(),
                          result.message(),
                          null,
                          result.recoveryCause())));
        });
  }

  private ExecutionInputs productionInputs() {
    ChainSemanticRevision revision = revisionWith(unknownTargetIntent());
    DesignPlanReport report = new DesignPlanReport("1", "# plan\n");
    DesignExecutionPlan plan = samplePlan();
    IdsDocument ids =
        new IdsDocument(
            "1",
            IdsDocument.Mode.PROVIDED,
            "brief-1",
            "brief-hash",
            "flow-hash",
            "renderer-1",
            "# IDS\n");
    ImplementationPlan implementation = new ImplementationPlan("plan text");
    RunManifest runManifest = emptySourceReferencesManifest(scriptGeneratorDag());
    Reference idsRef = appendRunArtifact(Kind.IDS_DOCUMENT, "1", ids).reference();
    Reference revisionRef =
        appendRunArtifact(
                Kind.CHAIN_SEMANTIC_REVISION, ChainSemanticRevision.CURRENT_SCHEMA_VERSION, revision)
            .reference();
    Reference reportRef = appendRunArtifact(Kind.DESIGN_PLAN_REPORT, "1", report).reference();
    Reference planRef = appendRunArtifact(Kind.DESIGN_EXECUTION_PLAN, "1", plan).reference();
    Reference implementationRef =
        appendRunArtifact(Kind.IMPLEMENTATION_PLAN, "1", implementation).reference();
    Reference manifestRef = appendRunArtifact(Kind.RUN_MANIFEST, "1", runManifest).reference();
    ApprovalRecordV2 approval =
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
    Reference approvalRef = appendRunArtifact(Kind.APPROVAL_RECORD, "2", approval).reference();
    return new ExecutionInputs(
        RUN_ID,
        CONV_ID,
        approvalRef,
        approval,
        report,
        reportRef,
        plan,
        planRef,
        revision,
        revisionRef,
        ids,
        idsRef,
        implementation,
        implementationRef,
        runManifest,
        manifestRef,
        List.of(),
        null,
        null,
        null);
  }

  private PlanValidationFinding unknownTargetFinding() {
    Revision brief = artifactStore.latest(RUN_ID, Kind.REQUIREMENT_BRIEF).orElseThrow();
    MappingValidationDetails details =
        new MappingValidationDetails(
            "salesforce-create-task",
            "trigger-http",
            "OUTPUT",
            "call-1",
            "REQUEST",
            "$.executionId",
            "$.preserved.executionId",
            "",
            "PROPOSED",
            brief.artifactId(),
            brief.contentHash(),
            "trigger-http",
            "OUTPUT",
            "sha-source",
            "conversation-schema",
            "call-1",
            "REQUEST",
            "sha-target",
            "conversation-schema",
            "Subject",
            "$.preserved.executionId");
    return new PlanValidationFinding(
        "MAPPING_UNKNOWN_TARGET",
        "Target path $.preserved.executionId is absent from the target contract.",
        true,
        details);
  }

  private StageExecutionResult execute(CreateChainTestOrchestrator runtime, String stageId) {
    return runtime.stageExecutor().execute(RUN_ID, stageId).await().indefinitely();
  }

  private void applyLifecycle(CreateChainTestOrchestrator runtime, StageExecutionResult result) {
    runtime
        .support()
        .applyStageLifecycle(RUN_ID, result)
        .collect()
        .asList()
        .await()
        .indefinitely();
  }

  private ProductPipelineRunDocument run() {
    return runStore.load(RUN_ID).orElseThrow();
  }

  private org.qubership.integration.platform.ai.productpipeline.store.StageSnapshot snapshot(
      String stageId) {
    return run().run().stages().stream()
        .filter(stage -> stageId.equals(stage.stageId()))
        .findFirst()
        .orElseThrow();
  }

  private static StageCapability capability(
      String id, java.util.function.Function<StageExecutionContext, Multi<CapabilitySignal>> exec) {
    return new StageCapability() {
      @Override
      public String capabilityId() {
        return id;
      }

      @Override
      public Multi<CapabilitySignal> execute(StageExecutionContext context) {
        return exec.apply(context);
      }
    };
  }

  private static ProductPipelineProfile threeStageProfile() {
    ArtifactTypeRef brief = new ArtifactTypeRef("requirement-brief", 1);
    ArtifactTypeRef flow = new ArtifactTypeRef("chain-semantic-revision", 1);
    return new ProductPipelineProfile(
        1,
        "mapping-brief-producer",
        "1",
        List.of(new ArtifactTypeRef("user-input", 1)),
        List.of(
            new ProfileStage(
                "requirement-analysis",
                "analysis-cap",
                List.of(new ArtifactTypeRef("user-input", 1)),
                List.of(brief),
                new ApprovalPolicy(brief),
                null,
                new RetryPolicy(0, 1L)),
            new ProfileStage(
                "design-input",
                "design-input-cap",
                List.of(brief),
                List.of(flow),
                null,
                null,
                new RetryPolicy(0, 1L)),
            new ProfileStage(
                "design-execution",
                "execution-cap",
                List.of(flow),
                List.of(),
                null,
                null,
                new RetryPolicy(0, 1L))),
        new TerminalPolicy("design-execution", "PLAN_APPROVED"),
        List.of("analysis-cap", "design-input-cap", "execution-cap"));
  }

  private RunManifest manifest() {
    return new RunManifest(
        RUN_ID,
        null,
        List.of(),
        "product",
        profile.profileId(),
        profile.profileVersion(),
        "profile-sha",
        "baseline",
        "baseline-sha",
        List.of(new DependencyClosureEntry("cap", "1", "c1")),
        "closure-sha",
        new KnowledgePackageRef(
            "knowledge-1",
            "1",
            "1.0.0",
            "checksum",
            "CERTIFIED",
            "sha256:certificate"),
        "24.4",
        List.of(new ArtifactTypeRef("user-input", 1)),
        null);
  }

  private CipDesignExecutorJavaAdapter productionAdapter(Clock clock) throws Exception {
    InMemorySkillWorkspaceStore workspaceStore =
        new InMemorySkillWorkspaceStore(new ChainPlanStore());
    SkillExecutorRegistry skillRegistry = mock(SkillExecutorRegistry.class);
    when(skillRegistry.require("cip-script-generator")).thenReturn(new MustNotRunScriptExecutor());
    QipKnowledgePackRepository packRepository = mock(QipKnowledgePackRepository.class);
    when(packRepository.activeVersion()).thenReturn(new QipKnowledgePackVersion("v1", "v1"));
    CanonicalGraphDigest digest = new CanonicalGraphDigest(MAPPER);
    CompilerSecurityValidator securityValidator = mock(CompilerSecurityValidator.class);
    when(securityValidator.validate(any())).thenReturn(new ValidationResult(true, List.of(), "ok"));
    CompilerQualityValidator qualityValidator = mock(CompilerQualityValidator.class);
    when(qualityValidator.validate(any(), any()))
        .thenReturn(new ValidationResult(true, List.of(), "ok"));
    CompilerValidationPipeline validationPipeline =
        new CompilerValidationPipeline(
            graph -> new ValidationResult(true, List.of(), "ok"),
            graph -> new ValidationResult(true, List.of(), "ok"),
            graph -> new ValidationResult(true, List.of(), "ok"),
            securityValidator,
            qualityValidator);
    MappingGenerationPipeline pipeline =
        new MappingGenerationPipeline(artifacts, MAPPER, contextBuilder());
    DefaultCompilerDagExecutionEngine engine =
        new DefaultCompilerDagExecutionEngine(
            workspaceStore,
            skillRegistry,
            mock(CompilerNodeExecutionAdapterRegistry.class),
            packRepository,
            new GraphAssemblyService(digest),
            validationPipeline,
            artifactStore,
            pipeline);
    ChainSemanticGraphCompiler graphCompiler = mock(ChainSemanticGraphCompiler.class);
    when(graphCompiler.compile(any(), any(), anyList(), any())).thenReturn(sampleGraph());
    ApprovedCompilerExecutionRunner engineRunner =
        new DefaultApprovedCompilerExecutionRunner(
            engine,
            runStore,
            artifactStore,
            graphCompiler,
            new ClasspathCompilerContractRepository());
    ExecutorCatalogBindingAdapter bindingAdapter = mock(ExecutorCatalogBindingAdapter.class);
    when(bindingAdapter.resolve(eq(CONV_ID), any(), anyList(), any()))
        .thenReturn(List.of(new BindingResolutionResult.Resolved(sampleBinding())));
    CompilerPlanValidator planValidator = mock(CompilerPlanValidator.class);
    when(planValidator.validate(any(PlanGraphValidationInput.class)))
        .thenReturn(new ValidationResult(true, List.of(), "ok"));
    return new CipDesignExecutorJavaAdapter(
        engineRunner, bindingAdapter, artifactStore, planValidator);
  }

  private Revision appendRunArtifact(Kind kind, String schemaVersion, Object payload) {
    return artifactStore.append(
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
  }

  private void persistSide(String serviceCallId, MappingPort direction, JsonNode schema) {
    artifacts.append(
        new AppendCommand(
            CONV_ID,
            Kind.MAPPING_SCHEMA_SIDE,
            "1",
            "test",
            "1",
            new MappingSchemaSide(
                "1",
                serviceCallId,
                "op-1",
                direction,
                "application/json",
                null,
                "sha-test",
                "conversation-schema",
                schema),
            List.of(),
            null));
  }

  private static JsonNode sourceSchema() throws Exception {
    return MAPPER.readTree(
        """
        {
          "type": "object",
          "properties": {
            "executionId": { "type": "string" },
            "subject": { "type": "string" }
          }
        }
        """);
  }

  private static JsonNode targetSchema() throws Exception {
    return MAPPER.readTree(
        """
        {
          "type": "object",
          "properties": { "Subject": { "type": "string" } },
          "required": ["Subject"]
        }
        """);
  }

  private static RequirementBrief unknownTargetBrief() {
    return new RequirementBrief(
            "goal", List.of(), List.of(), List.of(), List.of(), "summary")
        .withMappingIntents(List.of(unknownTargetIntent()));
  }

  private static MappingIntent unknownTargetIntent() {
    return new MappingIntent(
        "salesforce-create-task",
        "trigger-http",
        MappingPort.OUTPUT,
        "node-call",
        MappingPort.REQUEST,
        List.of(
            new MappingIntentRule("$.subject", "$.Subject", null, MappingRuleStatus.PROPOSED),
            new MappingIntentRule(
                "$.executionId",
                "$.preserved.executionId",
                "preserve for response",
                MappingRuleStatus.PROPOSED)));
  }

  private static ChainSemanticRevision revisionWith(MappingIntent intent) {
    return SemanticFixtures.linear(
        "Orders",
        "revision-orders",
        "trigger-http",
        "node-call",
        "call-1",
        "createOrder",
        "Orders API",
        List.of(intent),
        List.of("Preserve trace identifiers"));
  }

  private static ChainPlanGraph sampleGraph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("id", "Orders"),
        List.of(
            new ChainPlanNode("trigger-http", "http-trigger", "Trigger", null, 1, List.of()),
            new ChainPlanNode("node-call", "service-call", "Call", null, 2, List.of())),
        List.of());
  }

  private static ResolvedServiceCallBinding sampleBinding() {
    return new ResolvedServiceCallBinding(
        "node-call",
        "call-1",
        "INTEGRATION",
        "sys-1",
        "sg-1",
        "spec-1",
        "op-1",
        "http",
        "POST",
        "/orders",
        "createOrder",
        ResolvedServiceCallBinding.Source.EXISTING_CATALOG,
        "2024.4",
        "evidence-call-1",
        "");
  }

  private static ResolvedCompilerDag scriptGeneratorDag() {
    GraphPatchOwnershipPolicy scriptOwnership =
        new GraphPatchOwnershipPolicy(
            false, false, Set.of(), Set.of(), Map.of("script", Set.of("script")));
    return new ResolvedCompilerDag(
        List.of(
            new ResolvedCompilerNode(
                "cip-script-generator",
                "Implementation",
                null,
                List.of(SkillArtifactType.CHAIN_PLAN_GRAPH.name()),
                List.of(SkillArtifactType.GRAPH_PATCH.name()),
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
                null,
                scriptOwnership)),
        List.of(),
        "dag-script");
  }

  private static RunManifest emptySourceReferencesManifest(ResolvedCompilerDag dag) {
    CompilerRunPin pin =
        new CompilerRunPin(
            "compiler",
            "1",
            "pkg-digest",
            1,
            "1",
            CATALOG_HASH,
            dag,
            List.of("cip-script-generator"),
            Map.of("cip-script-generator", SKILL_HASH),
            Map.of("cip-script-generator", ADDON_HASH),
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
                "step-1-cip-script-generator",
                1,
                "Step 1",
                DesignExecutionPlan.OwnerKind.SKILL,
                List.of("cip-script-generator"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("GRAPH_PATCH_ARTIFACT"))),
        "design-plan-report",
        "report-content-hash",
        Map.of("cip-script-generator", SKILL_HASH),
        Map.of("cip-script-generator", ADDON_HASH),
        CATALOG_HASH,
        ApprovalPolicy.CATALOG_FIRST_V1_HASH);
  }

  private static CompilerSkillContextBuilder contextBuilder() {
    QipKnowledgePackRepository repository = mock(QipKnowledgePackRepository.class);
    CompilerSkillAddonRepository addonRepository = mock(CompilerSkillAddonRepository.class);
    when(addonRepository.loadForSkill(any())).thenReturn(CompilerSkillAddonContext.empty());
    when(repository.loadCompilerGeneratorSpecIndex())
        .thenReturn(new CompilerGeneratorSpecIndex(List.of()));
    when(repository.loadCompilerSkillCatalog()).thenReturn(new CompilerSkillCatalog(List.of()));
    return new CompilerSkillContextBuilder(
        MAPPER,
        repository,
        addonRepository,
        mock(CompilerSkillRuntimeEligibility.class),
        mock(KnowledgeClient.class),
        mock(KnowledgeContextProvider.class));
  }

  private static final class MustNotRunScriptExecutor implements SkillExecutor {
    @Override
    public String skillId() {
      return "cip-script-generator";
    }

    @Override
    public SkillExecutorKind kind() {
      return SkillExecutorKind.AGENT;
    }

    @Override
    public Set<SkillArtifactType> requiredInputs() {
      return Set.of(SkillArtifactType.CHAIN_PLAN_GRAPH);
    }

    @Override
    public Set<SkillArtifactType> outputTypes() {
      return Set.of(SkillArtifactType.GRAPH_PATCH);
    }

    @Override
    public Uni<SkillExecutionResult> run(SkillRunContext context, SkillWorkspace workspace) {
      throw new AssertionError("mapping contract must block before the script generator runs");
    }
  }
}
