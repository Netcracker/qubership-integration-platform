package org.qubership.integration.platform.ai.productpipeline.create.design.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.smallrye.mutiny.Uni;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.catalog.binding.CompositionCatalogBinder;
import org.qubership.integration.platform.ai.catalog.binding.McpSystemCatalogBinder;
import org.qubership.integration.platform.ai.catalog.binding.ResolvedServiceCallBinding;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.AppendCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerNodeExecutionMode;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPipelineDependency;
import org.qubership.integration.platform.ai.plan.RequirementBriefProjector;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.productpipeline.artifact.ArtifactProvenance;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerRunPin;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerValidationBundle;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerValidationPass;
import org.qubership.integration.platform.ai.productpipeline.artifact.GraphAssemblyResult;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerDag;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerNode;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.compiler.contract.ClasspathCompilerContractRepository;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerDagExecutionEngine;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerDagExecutionRequest;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerDagExecutionResult;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerExecutionSeed;
import org.qubership.integration.platform.ai.productpipeline.create.PlanningPatchLedger;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignExecutionPlan;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticFixtures;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.capability.StageRepairEvidence;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ApprovalPolicy;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationResult;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactPayload;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactType;

class DefaultApprovedCompilerExecutionRunnerTest {

  private static final Instant FIXED = Instant.parse("2026-07-30T13:00:00Z");
  private static final String RUN_ID = "run-engine-conv-1";
  private static final String CONVERSATION_ID = "conv-engine-real-1";

  private ProductPipelineRunStore runStore;
  private ProductPipelineArtifactStore artifactStore;
  private CompilerDagExecutionEngine engine;
  private DefaultApprovedCompilerExecutionRunner runner;
  private final AtomicReference<CompilerDagExecutionRequest> capturedRequest =
      new AtomicReference<>();
  private final AtomicReference<String> capturedAttemptId = new AtomicReference<>();

  @BeforeEach
  void setUp() {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    InMemoryArtifactBlobStore blobs = new InMemoryArtifactBlobStore();
    Clock clock = Clock.fixed(FIXED, ZoneOffset.UTC);
    runStore = new ProductPipelineRunStore(blobs, mapper, clock);
    artifactStore =
        new ProductPipelineArtifactStore(new CompilationArtifacts(blobs, mapper, clock));
    engine = mock(CompilerDagExecutionEngine.class);
    ChainSemanticGraphCompiler graphCompiler = mock(ChainSemanticGraphCompiler.class);
    when(graphCompiler.compile(any(), any(), any(), any()))
        .thenReturn(successfulEngineResult().graph());
    when(engine.execute(
            any(CompilerDagExecutionRequest.class), any(String.class), any(BiConsumer.class)))
        .thenAnswer(
            invocation -> {
              capturedRequest.set(invocation.getArgument(0));
              capturedAttemptId.set(invocation.getArgument(1));
              return Uni.createFrom().item(successfulEngineResult());
            });
    runner =
        new DefaultApprovedCompilerExecutionRunner(
            engine,
            runStore,
            artifactStore,
            graphCompiler,
            new ClasspathCompilerContractRepository(),
            new CompositionCatalogBinder(mock(CatalogRestClient.class)),
            new McpSystemCatalogBinder(mock(CatalogRestClient.class)),
            new org.qubership.integration.platform.ai.compiler.plan.GeneratorPlanManifestBuilder(
                new org.qubership.integration.platform.ai.compiler.plan.GeneratorReadinessEvaluator(),
                (catalog, intentRequest, intentBrief) -> ""));
  }

  @Test
  void aCommittedSemanticCannotUseADifferentCommittedBrief() {
    persistRun();
    storeBrief(protectedBrief());
    Reference otherBrief = appendRepairArtifact(Kind.REQUIREMENT_BRIEF, ordersBriefWith(List.of()), List.of());
    Reference wrongSemantic = appendRepairArtifact(Kind.CHAIN_SEMANTIC_REVISION, sampleRevision(), List.of(otherBrief));
    var doc = runStore.load(RUN_ID).orElseThrow();
    long revision = doc.run().runRevision();
    var stages = doc.run().stages().stream().map(stage -> "design-input".equals(stage.stageId())
        ? new org.qubership.integration.platform.ai.productpipeline.store.StageSnapshot(stage.stageId(),
            org.qubership.integration.platform.ai.productpipeline.store.StageStatus.SUCCEEDED, List.of(wrongSemantic), null)
        : stage).toList();
    runStore.commit(revision, new org.qubership.integration.platform.ai.productpipeline.store.LogicalCommit(
        RUN_ID, revision, RunStatus.RUNNING, "design-execution", stages,
        new org.qubership.integration.platform.ai.productpipeline.store.StageAttempt("mismatched-lineage",
            "design-execution", revision + 1, org.qubership.integration.platform.ai.productpipeline.store.StageStatus.RUNNING,
            FIXED, FIXED, List.of(), null),
        new org.qubership.integration.platform.ai.productpipeline.store.RunTransition(revision, revision + 1,
            RunStatus.RUNNING, RunStatus.RUNNING, "design-execution", FIXED, "Committed mismatched fixture lineage")));
    IllegalStateException failure = assertThrows(IllegalStateException.class, () -> runner.execute(
        samplePlan(), sampleRevision(), List.of(sampleBinding()), sampleManifest(), "attempt-mismatch", (skill, status) -> {}));
    assertTrue(failure.getMessage().contains("brief lineage"), failure.getMessage());
    org.mockito.Mockito.verifyNoInteractions(engine);
  }

  @Test
  void anUnapprovedNewerBriefCannotReplaceTheCommittedCompilerInput() {
    persistRun();
    storeBrief(protectedBrief());
    storeBrief(ordersBriefWith(List.of()));
    runner.execute(samplePlan(), sampleRevision(), List.of(sampleBinding()), sampleManifest(),
        "orphan-brief-control", (skillId, status) -> {});
    assertEquals(protectedBrief().facts(), capturedRequest.get().requirementBrief().facts());
  }

  @Test
  void partialProgressUsesOnlyAProtectedCandidateWithComparableValidationCoverage() {
    persistRun();
    storeBrief(protectedBrief());
    List<Reference> inputs = List.of(
        appendRepairArtifact(Kind.CHAIN_SEMANTIC_REVISION, sampleRevision(), List.of()),
        appendRepairArtifact(Kind.DESIGN_EXECUTION_PLAN, samplePlan(), List.of()),
        appendRepairArtifact(Kind.IMPLEMENTATION_PLAN, Map.of("approved", true), List.of()));
    var oldA = new org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationFinding(
        "INVALID_METHOD", "Invalid method", true, org.qubership.integration.platform.ai.productpipeline.artifact.MappingValidationDetails.empty(),
        "trigger-http", "/properties/httpMethod");
    var oldB = new org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationFinding(
        "INVALID_PROPERTY", "Invalid property", true, org.qubership.integration.platform.ai.productpipeline.artifact.MappingValidationDetails.empty(),
        "node-call", "/properties/operationId");
    var newC = new org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationFinding(
        "INVALID_SCRIPT", "Invalid script", true, org.qubership.integration.platform.ai.productpipeline.artifact.MappingValidationDetails.empty(),
        "node-call", "/properties/before.script");
    ChainPlanGraph prior = protectedGraph("preserved-partial-input");
    Reference priorGraph = appendRepairArtifact(Kind.CHAIN_PLAN_GRAPH, prior, inputs);
    Reference priorEvidence = repairObservation(priorGraph, prior, inputs, List.of(oldA, oldB), List.of(), "element-validator");

    for (String scenario : List.of("new-defect", "same-findings", "lost-value", "changed-coverage")) {
      ChainPlanGraph current = protectedGraph("new-partial-input");
      if ("lost-value".equals(scenario)) {
        current = current.withNodeProperty("trigger-http", "httpUri", "/obsolete");
      }
      Reference currentGraph = appendRepairArtifact(Kind.CHAIN_PLAN_GRAPH, current, inputs);
      Reference currentEvidence = repairObservation(currentGraph, current, inputs,
          "same-findings".equals(scenario) ? List.of(oldA, oldB) : List.of(oldB, newC), List.of(priorEvidence),
          "changed-coverage".equals(scenario) ? "different-validator" : "element-validator");
      StageRepairEvidence repair = new StageRepairEvidence("VALIDATION_FAILURE", "design-execution",
          "Retained or newly observed validation findings", "validation failed", "", currentEvidence.contentHash(), List.of(currentGraph));

      runner.execute(samplePlan(), sampleRevision(), List.of(sampleBinding()), sampleManifest(),
          "partial-" + scenario, repair, current, (skillId, status) -> {});

      boolean improved = "new-defect".equals(scenario);
      var selected = capturedRequest.get().repairEvidence();
      assertTrue(selected.diagnosticText().contains(improved ? "new-partial-input" : "preserved-partial-input"), scenario);
      assertEquals(improved ? currentGraph : priorGraph, selected.priorOutput(Kind.CHAIN_PLAN_GRAPH).orElseThrow());
      assertFalse(capturedRequest.get().effectiveSeed().seedText().contains("partial-input"));
      assertEquals(protectedBrief().facts(), capturedRequest.get().requirementBrief().facts());
    }
    ChainPlanGraph stalled = protectedGraph("no-progress-input");
    Reference stalledRef = appendRepairArtifact(Kind.CHAIN_PLAN_GRAPH, stalled, inputs);
    Reference stalledEvidence = repairObservation(stalledRef, stalled, inputs, List.of(oldA, oldB),
        List.of(priorEvidence), "element-validator");
    ChainPlanGraph regressed = protectedGraph("regressed-input")
        .withNodeProperty("trigger-http", "httpUri", "/obsolete");
    Reference regressedRef = appendRepairArtifact(Kind.CHAIN_PLAN_GRAPH, regressed, inputs);
    Reference regressedEvidence = repairObservation(regressedRef, regressed, inputs, List.of(oldB, newC),
        List.of(priorEvidence, stalledEvidence), "element-validator");
    runner.execute(samplePlan(), sampleRevision(), List.of(sampleBinding()), sampleManifest(),
        "partial-history", new StageRepairEvidence("VALIDATION_FAILURE", "design-execution",
            "Partial history", "validation failed", "", regressedEvidence.contentHash(), List.of(regressedRef)),
        regressed, (skillId, status) -> {});
    assertEquals(priorGraph, capturedRequest.get().repairEvidence().priorOutput(Kind.CHAIN_PLAN_GRAPH).orElseThrow());
    Reference sameGraph = appendRepairArtifact(Kind.CHAIN_PLAN_GRAPH, prior, inputs);
    Reference lesserCoverage = repairObservation(sameGraph, prior, inputs, List.of(oldA, oldB),
        List.of(priorEvidence), "different-validator");
    ChainPlanGraph improvement = protectedGraph("different-coverage-input");
    Reference improvementRef = appendRepairArtifact(Kind.CHAIN_PLAN_GRAPH, improvement, inputs);
    Reference improvementEvidence = repairObservation(improvementRef, improvement, inputs, List.of(oldB, newC),
        List.of(priorEvidence, lesserCoverage), "different-validator");
    runner.execute(samplePlan(), sampleRevision(), List.of(sampleBinding()), sampleManifest(),
        "partial-coverage", new StageRepairEvidence("VALIDATION_FAILURE", "design-execution",
            "Coverage history", "validation failed", "", improvementEvidence.contentHash(), List.of(improvementRef)),
        improvement, (skillId, status) -> {});
    assertEquals(priorGraph, capturedRequest.get().repairEvidence().priorOutput(Kind.CHAIN_PLAN_GRAPH).orElseThrow());
  }

  private Reference repairObservation(Reference graphRef, ChainPlanGraph graph, List<Reference> inputs,
      List<org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationFinding> findings,
      List<Reference> prior, String validator) {
    Reference bundle = appendRepairArtifact(Kind.COMPILER_VALIDATION_BUNDLE,
        new CompilerValidationBundle(1, new org.qubership.integration.platform.ai.qipknowledge.patch.CanonicalGraphDigest(
            new ObjectMapper()).sha256(graph), List.of(new CompilerValidationPass(validator,
            new ValidationResult(false, List.of(), "Rejected candidate")))), inputs);
    Reference report = appendRepairArtifact(Kind.PLAN_VALIDATION_RESULT,
        new org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationResult(findings), inputs);
    return appendRepairArtifact(Kind.RECOVERY_EVIDENCE,
        new org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryEvidence(1,
            java.util.UUID.randomUUID().toString(), "VALIDATION_BLOCKER", "design-execution", "design-execution",
            null, inputs.getFirst(), List.of(graphRef, bundle, report), List.of(), null, prior), inputs);
  }

  private Reference appendRepairArtifact(Kind kind, Object payload, List<Reference> inputs) {
    return artifactStore.append(new AppendCommand(RUN_ID, kind,
        kind == Kind.CHAIN_SEMANTIC_REVISION ? ChainSemanticRevision.CURRENT_SCHEMA_VERSION : "1",
        "design-execution", "1", payload, inputs, null,
        new ArtifactProvenance(RUN_ID, "design-execution", "create-chain", "2", "profile-sha", "design-execution", "1", "closure"))).reference();
  }

  @Test
  void aRejectedGraphCannotEraseProtectedRequirementsFromTheNextRepairInput() {
    persistRun();
    storeBrief(protectedBrief());
    ChainPlanGraph complete = protectedGraph("candidate-to-discard");
    for (String loss : List.of("structure", "value", "name", "hook", "hook-body")) {
      ChainPlanGraph rejected = switch (loss) {
        case "structure" -> new ChainPlanGraph(complete.schemaVersion(), complete.chain(),
            List.of(complete.nodes().getFirst()), List.of());
        case "value" -> complete.withNodeProperty("trigger-http", "httpUri", "/obsolete");
        case "hook" -> complete.withNodeProperty("node-call", "before", "[]");
        case "hook-body" -> complete.withNodeProperty("node-call", "before", "{\"type\":\"script\",\"script\":\"return\"}");
        default -> new ChainPlanGraph(complete.schemaVersion(), complete.chain(), complete.nodes().stream()
            .map(node -> node.nodeId().equals("trigger-http") ? new ChainPlanNode(node.nodeId(), node.type(),
                "Changed requested name", node.parentNodeId(), node.order(), node.properties()) : node).toList(), complete.edges());
      };
      StageRepairEvidence repair = new StageRepairEvidence("VALIDATION_FAILURE", "design-execution",
          "Invalid generated property.", "validation failed", "");

      runner.execute(samplePlan(), sampleRevision(), List.of(sampleBinding()), sampleManifest(),
          "repair-" + loss, repair, rejected, (skillId, status) -> {});

      assertFalse(capturedRequest.get().repairEvidence().diagnosticText().contains("candidate-to-discard"), loss);
      assertEquals(protectedBrief().facts(), capturedRequest.get().requirementBrief().facts());
    }
  }

  private static RequirementBrief protectedBrief() {
    var element = new org.qubership.integration.platform.ai.plan.RequirementElementIntent("trigger-http", "http-trigger",
        org.qubership.integration.platform.ai.plan.RequirementElementIntent.Placement.ANCHOR,
        null, null, null, "Orders API", List.of(new org.qubership.integration.platform.ai.plan.model.PlanProperty("httpUri", "/orders-v2")), List.of());
    var hook = new org.qubership.integration.platform.ai.plan.RequirementElementIntent("hook-before", "script",
        org.qubership.integration.platform.ai.plan.RequirementElementIntent.Placement.BEFORE_HOOK,
        "node-call", null, null, null, List.of(), List.of("/orders-v2"));
    var facts = List.of(element, hook).stream().map(intent -> new org.qubership.integration.platform.ai.plan.RequirementFact(
        intent.elementRef(), org.qubership.integration.platform.ai.plan.RequirementFactPolarity.POSITIVE,
        org.qubership.integration.platform.ai.plan.RequirementFactKind.PARAMETER, "", "Preserve author values", "", "", "", "", "", "",
        List.of("trigger-http"), null, intent)).toList();
    return ordersBriefWith(List.of()).withFacts(facts);
  }

  private static ChainPlanGraph protectedGraph(String marker) {
    return new ChainPlanGraph("1.0", new ChainSection("Orders", "Orders"),
        List.of(new ChainPlanNode("trigger-http", "http-trigger", "Orders API", null, null, List.of()),
            new ChainPlanNode("node-call", "service-call", "Create order", null, null, List.of())),
        List.of(new org.qubership.integration.platform.ai.plan.model.ChainPlanEdge("edge-1", "trigger-http", "node-call", null)))
        .withNodeProperty("trigger-http", "httpUri", "/orders-v2")
        .withNodeProperty("node-call", "operationId", marker)
        .withNodeProperty("node-call", "before", "{\"type\":\"script\",\"script\":\"exchange.message.body = '/orders-v2'\"}");
  }

  @Test
  void theRealEngineRequestKeepsTheRejectedGraphSeparateFromApprovedInputs() {
    persistRun();
    storeBrief(ordersBriefWith(List.of()));
    ChainPlanGraph rejected = protectedGraph("prior-rejected-graph")
        .withNodeProperty("trigger-http",
            "accessControlType", "rejected-candidate-value");
    StageRepairEvidence repair = new StageRepairEvidence(
        "VALIDATION_FAILURE", "design-execution", "SECURITY_POLICY: Use the confirmed policy.",
        "validation failed", "", "evidence-exact", List.of(), "cip-security-generator",
        "SECURITY_POLICY");

    runner.execute(samplePlan(), sampleRevision(), List.of(sampleBinding()), sampleManifest(),
        "repair-1", repair, rejected, (skillId, status) -> {});

    CompilerDagExecutionRequest request = capturedRequest.get();
    assertTrue(request.repairEvidence().repairTextForSkill("cip-security-generator")
        .contains("rejected-candidate-value"));
    assertFalse(request.effectiveSeed().seedText().contains("rejected-candidate-value"));
    ChainPlanGraph seeded = ((SkillArtifactPayload.ChainPlanGraphPayload)
        request.effectiveSeed().artifacts().stream()
            .filter(artifact -> artifact.type() == SkillArtifactType.CHAIN_PLAN_GRAPH)
            .findFirst().orElseThrow().payload()).graph();
    assertEquals(successfulEngineResult().graph(), seeded);
    assertEquals("", request.repairEvidence().repairTextForSkill("cip-naming-generator"));
  }

  @Test
  void engineRequestReceivesConversationIdResolvedFromRunStore() {
    RunManifest manifest = sampleManifest();
    Reference manifestRef =
        artifactStore
            .append(
                new AppendCommand(
                    RUN_ID,
                    Kind.RUN_MANIFEST,
                    "1",
                    "test",
                    "1",
                    manifest,
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
                        "closure")))
            .reference();
    runStore.create(
        new RunSnapshot(
            RUN_ID,
            CONVERSATION_ID,
            1L,
            RunStatus.RUNNING,
            "design-execution",
            List.of(),
            manifestRef));

    storeBrief(ordersBriefWith(List.of()));
    runner.execute(
        samplePlan(),
        sampleRevision(),
        List.of(sampleBinding()),
        manifest,
        "attempt-2",
        (skillId, status) -> {});

    CompilerDagExecutionRequest request = capturedRequest.get();
    assertEquals(RUN_ID, request.runId());
    assertEquals(CONVERSATION_ID, request.conversationId());
    assertNotEquals(request.runId(), request.conversationId());
    assertEquals("attempt-2", capturedAttemptId.get());
    assertTrue(
        request.effectiveSeed().presentArtifactTypes().contains("CHAIN_SEMANTIC_REVISION"));
    assertTrue(request.effectiveSeed().presentArtifactTypes().contains("CHAIN_PLAN_GRAPH"));
    assertEquals(
        List.of(sampleBinding()),
        ((SkillArtifactPayload.ServiceCallBindingsPayload)
                request
                    .effectiveSeed()
                    .artifacts()
                    .stream()
                    .filter(artifact -> artifact.type() == SkillArtifactType.SERVICE_CALL_BINDINGS)
                    .findFirst()
                    .orElseThrow()
                    .payload())
            .bindings());
    assertTrue(
        request
            .effectiveSeed()
            .preSatisfiedSkillIds()
            .contains(CompilerExecutionSeed.STRUCTURE_GENERATOR_SKILL));
    assertTrue(
        request
            .effectiveSeed()
            .preSatisfiedSkillIds()
            .contains(CompilerExecutionSeed.PATTERN_SELECTOR_SKILL));
    assertFalse(
        request
            .effectiveSeed()
            .preSatisfiedSkillIds()
            .contains(CompilerExecutionSeed.TRIGGER_GENERATOR_SKILL));
    assertTrue(request.effectiveSeed().presentArtifactTypes().contains("SELECTED_PATTERN"));
    assertTrue(request.effectiveSeed().presentArtifactTypes().contains("CONFIGURED_TRIGGER_SET"));
    assertTrue(request.effectiveSeed().presentArtifactTypes().contains("GENERATOR_PLAN_MANIFEST"));
  }

  @Test
  void executeRejectsADesignPlanPinnedToADifferentSemanticRevision() {
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                runner.execute(
                    planForRevision("revision-other"),
                    sampleRevision(),
                    List.of(sampleBinding()),
                    sampleManifest(),
                    "attempt-1",
                    (skillId, status) -> {}));

    assertTrue(thrown.getMessage().contains("does not match the approved semantic revision"));
  }

  @Test
  void executeFailsWhenRequirementBriefIsMissing() {
    persistRun();

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                runner.execute(
                    samplePlan(),
                    sampleRevision(),
                    List.of(sampleBinding()),
                    sampleManifest(),
                    "attempt-1",
                    (skillId, status) -> {}));

    assertTrue(thrown.getMessage().contains("committed RequirementBrief"), thrown.getMessage());
  }

  @Test
  void executeAcceptsLiveMappingIntentsWhenRevisionRefsAreProjectedEdgeIds() {
    MappingIntent liveIntent =
        new MappingIntent(
            "map-init",
            "trigger-http",
            MappingPort.OUTPUT,
            "node-call",
            MappingPort.REQUEST,
            List.of(new MappingIntentRule("id", "customerId", null)));
    persistRun();
    storeBrief(ordersBriefWith(List.of(liveIntent)), SemanticFixtures.linearOrdersWithMapping());

    runner.execute(
        samplePlan(),
        SemanticFixtures.linearOrdersWithMapping(),
        List.of(sampleBinding()),
        sampleManifest(),
        "attempt-1",
        (skillId, status) -> {});

    RequirementBrief stored =
        artifactStore.payload(
            artifactStore.latest(RUN_ID, Kind.REQUIREMENT_BRIEF).orElseThrow(),
            RequirementBrief.class);
    assertEquals("trigger-http", stored.mappingIntents().getFirst().sourceRef());
    assertEquals("node-call", stored.mappingIntents().getFirst().targetRef());
  }

  @Test
  void executeAcceptsAPlaceholderFoldedCanonicalBrief() {
    persistRun();
    MappingIntent approved =
        new MappingIntent(
            "map-init",
            "trigger-http",
            MappingPort.OUTPUT,
            "node-call",
            MappingPort.REQUEST,
            List.of(new MappingIntentRule("id", "customerId", null)));
    MappingIntent placeholder =
        new MappingIntent(
            "process-instance-to-process-id",
            "edge-495d48ab0cc3cf30",
            MappingPort.OUTPUT,
            "edge-495d48ab0cc3cf30",
            MappingPort.REQUEST,
            List.of(new MappingIntentRule("processInstanceId", "customerId", "alias")));
    RequirementBrief canonical =
        RequirementBriefProjector.canonicalizeMappingIntents(
            ordersBriefWith(List.of(approved, placeholder)));
    ChainSemanticRevision revision =
        SemanticFixtures.withoutMappingBodies(SemanticFixtures.linearOrdersWithMapping());
    storeBrief(canonical, revision);

    runner.execute(
        samplePlan(),
        revision,
        List.of(sampleBinding()),
        sampleManifest(),
        "attempt-1",
        (skillId, status) -> {});

    assertEquals(1, canonical.mappingIntents().size());
    assertEquals(2, canonical.mappingIntents().getFirst().rules().size());
  }

  private static CompilerDagExecutionResult successfulEngineResult() {
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
        new CompilerValidationBundle(
            1,
            "graph-digest",
            List.of(
                new CompilerValidationPass(
                    "graph", new ValidationResult(true, List.of(), "ok")))));
  }

  private static DesignExecutionPlan samplePlan() {
    return planForRevision("revision-orders");
  }

  private static DesignExecutionPlan planForRevision(String semanticRevisionId) {
    return new DesignExecutionPlan(
        "1",
        semanticRevisionId,
        "cip-design-planner",
        "chain-semantic-revision/" + semanticRevisionId,
        "design-input-hash",
        "2024.4",
        ApprovalPolicy.CATALOG_FIRST_V1,
        List.of(
            new DesignExecutionPlan.Step(
                "step-1-cip-trigger-generator",
                1,
                "Step 1",
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
  }

  private static RequirementBrief ordersBriefWith(List<MappingIntent> mappingIntents) {
    return new RequirementBrief(
            "Orders",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "Map OM output",
            "ref",
            "draft",
            List.of(),
            List.of())
        .withMappingIntents(mappingIntents);
  }

  private void storeBrief(RequirementBrief brief) {
    storeBrief(brief, sampleRevision());
  }

  private void storeBrief(RequirementBrief brief, ChainSemanticRevision semantic) {
    var stored = artifactStore.append(
        new AppendCommand(
            RUN_ID,
            Kind.REQUIREMENT_BRIEF,
            "1",
            "requirement-analysis",
            "1",
            brief,
            List.of(),
            null,
            new ArtifactProvenance(
                RUN_ID,
                "requirement-analysis",
                "create-chain",
                "2",
                "profile-sha",
                "requirement-analysis",
                "1",
                "closure")));
    var doc = runStore.load(RUN_ID).orElseThrow();
    if (doc.run().stages().stream().noneMatch(stage -> "requirement-analysis".equals(stage.stageId()))) {
      Reference semanticRef = appendRepairArtifact(Kind.CHAIN_SEMANTIC_REVISION, semantic, List.of(stored.reference()));
      long revision = doc.run().runRevision();
      runStore.commit(revision, new org.qubership.integration.platform.ai.productpipeline.store.LogicalCommit(
          RUN_ID, revision, RunStatus.RUNNING, "design-execution", List.of(
              new org.qubership.integration.platform.ai.productpipeline.store.StageSnapshot("requirement-analysis",
                  org.qubership.integration.platform.ai.productpipeline.store.StageStatus.SUCCEEDED,
                  List.of(stored.reference()), stored.artifactId()),
              new org.qubership.integration.platform.ai.productpipeline.store.StageSnapshot("design-input",
                  org.qubership.integration.platform.ai.productpipeline.store.StageStatus.SUCCEEDED, List.of(semanticRef), null),
              new org.qubership.integration.platform.ai.productpipeline.store.StageSnapshot("design-execution",
                  org.qubership.integration.platform.ai.productpipeline.store.StageStatus.RUNNING, List.of(), null)),
          new org.qubership.integration.platform.ai.productpipeline.store.StageAttempt(
              "fixture-brief-commit", "design-execution", revision + 1,
              org.qubership.integration.platform.ai.productpipeline.store.StageStatus.RUNNING,
              FIXED, FIXED, List.of(), null),
          new org.qubership.integration.platform.ai.productpipeline.store.RunTransition(revision, revision + 1,
              RunStatus.RUNNING, RunStatus.RUNNING, "design-execution", FIXED, "Committed fixture brief")));
    }
  }

  private void persistRun() {
    RunManifest manifest = sampleManifest();
    Reference manifestRef =
        artifactStore
            .append(
                new AppendCommand(
                    RUN_ID,
                    Kind.RUN_MANIFEST,
                    "1",
                    "test",
                    "1",
                    manifest,
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
                        "closure")))
            .reference();
    runStore.create(
        new RunSnapshot(
            RUN_ID,
            CONVERSATION_ID,
            1L,
            RunStatus.RUNNING,
            "design-execution",
            List.of(),
            manifestRef));
  }

  private static ChainSemanticRevision sampleRevision() {
    return SemanticFixtures.linearOrders();
  }

  private static ResolvedServiceCallBinding sampleBinding() {
    return new ResolvedServiceCallBinding(
        "call-orders",
        "call-orders",
        "EXTERNAL",
        "sys-orders",
        "sg-orders",
        "spec-orders",
        "op-orders",
        "http",
        "GET",
        "/orders/{id}",
        "getOrder",
        ResolvedServiceCallBinding.Source.EXISTING_CATALOG,
        "2024.4",
        "evidence-orders",
        "");
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

  @Test
  void scopeDagDoesNotPullUnplannedGeneratorsThroughAssembler() {
    ResolvedCompilerDag full =
        new ResolvedCompilerDag(
            List.of(
                generationNode("cip-naming-generator", List.of()),
                generationNode("cip-trigger-generator", List.of("cip-naming-generator")),
                generationNode("cip-security-generator", List.of("cip-naming-generator")),
                generationNode("cip-transformation-generator", List.of("cip-naming-generator")),
                generationNode("cip-auth-generator", List.of("cip-service-call-generator")),
                generationNode("cip-service-call-generator", List.of("cip-naming-generator")),
                generationNode("cip-quartz-scheduler-generator", List.of("cip-naming-generator")),
                terminalNode(
                    "cip-chain-assembler",
                    "Assembly",
                    List.of(
                        "cip-trigger-generator",
                        "cip-security-generator",
                        "cip-transformation-generator",
                        "cip-auth-generator",
                        "cip-quartz-scheduler-generator")),
                terminalNode(
                    "cip-structural-validator",
                    "Validation",
                    List.of("cip-chain-assembler"))),
            List.of(
                edge("cip-naming-generator", "cip-trigger-generator"),
                edge("cip-naming-generator", "cip-security-generator"),
                edge("cip-naming-generator", "cip-transformation-generator"),
                edge("cip-naming-generator", "cip-service-call-generator"),
                edge("cip-service-call-generator", "cip-auth-generator"),
                edge("cip-naming-generator", "cip-quartz-scheduler-generator"),
                edge("cip-trigger-generator", "cip-chain-assembler"),
                edge("cip-security-generator", "cip-chain-assembler"),
                edge("cip-transformation-generator", "cip-chain-assembler"),
                edge("cip-auth-generator", "cip-chain-assembler"),
                edge("cip-quartz-scheduler-generator", "cip-chain-assembler"),
                edge("cip-chain-assembler", "cip-structural-validator")),
            "full-dag");

    ResolvedCompilerDag scoped =
        DefaultApprovedCompilerExecutionRunner.scopeDag(
            full,
            List.of(
                "cip-trigger-generator", "cip-chain-assembler", "cip-structural-validator"));

    Set<String> skillIds =
        scoped.nodes().stream().map(ResolvedCompilerNode::skillId).collect(java.util.stream.Collectors.toSet());
    assertEquals(
        Set.of(
            "cip-naming-generator",
            "cip-trigger-generator",
            "cip-security-generator",
            "cip-transformation-generator",
            "cip-service-call-generator",
            "cip-auth-generator",
            "cip-chain-assembler",
            "cip-structural-validator"),
        skillIds);
    assertFalse(skillIds.contains("cip-quartz-scheduler-generator"));
    assertEquals(
        List.of(
            "cip-trigger-generator",
            "cip-security-generator",
            "cip-transformation-generator",
            "cip-auth-generator"),
        node(scoped, "cip-chain-assembler").dependsOn());
    assertEquals(
        List.of("cip-service-call-generator"), node(scoped, "cip-auth-generator").dependsOn());
  }

  private static ResolvedCompilerNode generationNode(String skillId, List<String> dependsOn) {
    return terminalNode(skillId, "Generation", dependsOn);
  }

  private static ResolvedCompilerNode terminalNode(
      String skillId, String phase, List<String> dependsOn) {
    return new ResolvedCompilerNode(
        skillId,
        phase,
        null,
        List.of(),
        List.of(),
        dependsOn,
        null,
        List.of(),
        List.of(),
        true,
        List.of(),
        0,
        0,
        true,
        CompilerNodeExecutionMode.LLM_SKILL,
        null);
  }

  private static CompilerPipelineDependency edge(String producer, String consumer) {
    return new CompilerPipelineDependency(producer, consumer, List.of());
  }

  private static ResolvedCompilerNode node(ResolvedCompilerDag dag, String skillId) {
    return dag.nodes().stream()
        .filter(n -> skillId.equals(n.skillId()))
        .findFirst()
        .orElseThrow();
  }
}
