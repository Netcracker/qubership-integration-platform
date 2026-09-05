package org.qubership.integration.platform.ai.productpipeline.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.AppendCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.contract.ClasspathCompilerContractRepository;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerNodeExecutionMode;
import org.qubership.integration.platform.ai.plan.MappingTurnAdapter;
import org.qubership.integration.platform.ai.plan.MappingTurnResult;
import org.qubership.integration.platform.ai.plan.MappingTurnResult.AddIntent;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.productpipeline.artifact.ArtifactProvenance;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerRunPin;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerValidationBundle;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerValidationPass;
import org.qubership.integration.platform.ai.productpipeline.artifact.DependencyClosureEntry;
import org.qubership.integration.platform.ai.productpipeline.artifact.GraphAssemblyResult;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerDag;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerNode;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.capability.ArtifactCandidate;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapability;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapabilityRegistry;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerDagExecutionEngine;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerDagExecutionRequest;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerDagExecutionResult;
import org.qubership.integration.platform.ai.productpipeline.create.CompilerRunPinResolver;
import org.qubership.integration.platform.ai.productpipeline.create.PlanningPatchLedger;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.ChainSemanticGraphCompiler;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.DefaultApprovedCompilerExecutionRunner;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.ChainSemanticCaptureAdapter;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.MappingGapCoverage;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.MappingGapWait;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignExecutionPlan;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticEntryPoint;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticExecutionEdge;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticNode;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticProvenance;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticRoute;
import org.qubership.integration.platform.ai.productpipeline.facade.PipelineGates;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ApprovalPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProfileStage;
import org.qubership.integration.platform.ai.productpipeline.profile.RetryPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.TerminalPolicy;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Interaction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Transition;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationResult;

/**
 * Design-input, execute, and restore read the requirement-brief log tip. A sticky in-memory copy
 * must not outrank a later approved candidate.
 */
class OneBriefLogTipTest {

  private static final Instant FIXED = Instant.parse("2026-09-05T12:00:00Z");
  private static final String RUN_ID = "run-one-brief-log-tip";
  private static final String CONV_ID = "conv-one-brief-log-tip";
  private static final String DESCRIBE_PROSE =
      "Map task-start into create-task: name to Subject. Then map create-task into task-result:"
          + " commandType is completeTask.";
  private static final CompilerContract CONTRACT =
      new ClasspathCompilerContractRepository().require(CompilerContract.V1);

  private ProductPipelineRunStore runStore;
  private ProductPipelineArtifactStore artifactStore;
  private ProductPipelineRunSupport support;
  private CreateChainTestOrchestrator runtime;
  private ProductPipelineProfile profile;
  private Clock clock;
  private MappingTurnAdapter mappingAdapter;
  private DefaultApprovedCompilerExecutionRunner executionRunner;
  private final AtomicReference<RequirementBrief> capturedDesignBrief = new AtomicReference<>();

  @BeforeEach
  void setUp() {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    InMemoryArtifactBlobStore blobStore = new InMemoryArtifactBlobStore();
    clock = Clock.fixed(FIXED, ZoneOffset.UTC);
    CompilationArtifacts artifacts = new CompilationArtifacts(blobStore, mapper, clock);
    runStore = new ProductPipelineRunStore(blobStore, mapper, clock);
    artifactStore = new ProductPipelineArtifactStore(artifacts);
    profile = fourStageProfile();
    mappingAdapter = coveringAdapter();
    executionRunner = executionRunner();
    capturedDesignBrief.set(null);
    support = newSupport();
    runtime = new CreateChainTestOrchestrator(support, runStore);
  }

  @Test
  void mappingGapThenRecaptureThenExecuteDoesNotHaltOnLiveMappingMismatch() {
    waitAtMappingGap();
    type(DESCRIBE_PROSE);
    RequirementBrief gapBrief = storedBrief();
    assertEquals("$.name", firstSourcePath(gapBrief));
    appendRecapturedBrief();

    captureThroughExecute();

    RequirementBrief recaptured = storedBrief();
    assertEquals("Recaptured OM to Salesforce WFM", recaptured.goal());
    assertEquals("$.title", firstSourcePath(recaptured));
    assertEquals("Recaptured OM to Salesforce WFM", capturedDesignBrief.get().goal());
    assertEquals("$.title", firstSourcePath(capturedDesignBrief.get()));
    assertNotEquals(firstSourcePath(gapBrief), firstSourcePath(capturedDesignBrief.get()));
    assertFalse(
        run()
            .transitions()
            .stream()
            .anyMatch(
                transition ->
                    transition.reason() != null
                        && transition
                            .reason()
                            .contains("Live mapping-intent collection differs")));
    assertEquals(RunStatus.PLAN_APPROVED, run().run().status());
  }

  @Test
  void mappingGapOnlyStillCapturesAndExecutes() {
    waitAtMappingGap();
    type(DESCRIBE_PROSE);

    captureThroughExecute();

    assertEquals("OM to Salesforce WFM", capturedDesignBrief.get().goal());
    assertEquals("$.name", firstSourcePath(capturedDesignBrief.get()));
    assertEquals(2, capturedDesignBrief.get().mappingIntents().size());
    assertEquals(RunStatus.PLAN_APPROVED, run().run().status());
  }

  @Test
  void restoreWithEmptyAttributesLoadsTheLogTip() {
    waitAtMappingGap();
    type(DESCRIBE_PROSE);
    appendRecapturedBrief();
    assertEquals("OM to Salesforce WFM", ((RequirementBrief) support.runAttributes(RUN_ID)
            .get("requirementBrief"))
        .goal());

    ProductPipelineRunSupport restored = newSupport();
    restored
        .restoreForExternalWorkflow(new StartOrResumeCommand(CONV_ID, RUN_ID, profile, manifest()))
        .collect()
        .asList()
        .await()
        .indefinitely();

    RequirementBrief hydrated =
        (RequirementBrief) restored.runAttributes(RUN_ID).get("requirementBrief");
    assertEquals("Recaptured OM to Salesforce WFM", hydrated.goal());
    assertEquals("$.title", firstSourcePath(hydrated));
  }

  @Test
  void restoreReplacesAStickyInMemoryBriefWithTheLogTip() {
    waitAtMappingGap();
    type(DESCRIBE_PROSE);
    appendRecapturedBrief();

    support
        .restoreForExternalWorkflow(new StartOrResumeCommand(CONV_ID, RUN_ID, profile, manifest()))
        .collect()
        .asList()
        .await()
        .indefinitely();

    RequirementBrief hydrated =
        (RequirementBrief) support.runAttributes(RUN_ID).get("requirementBrief");
    assertEquals("Recaptured OM to Salesforce WFM", hydrated.goal());
  }

  private void captureThroughExecute() {
    runtime.executeStage(RUN_ID, "design-input").collect().asList().await().indefinitely();
    assertEquals("planning", run().run().currentStageId());
    List<PipelineSignal> planning =
        runtime.executeStage(RUN_ID, "planning").collect().asList().await().indefinitely();
    PipelineSignal.WaitingForApproval waiting =
        planning.stream()
            .filter(PipelineSignal.WaitingForApproval.class::isInstance)
            .map(PipelineSignal.WaitingForApproval.class::cast)
            .findFirst()
            .orElseThrow();
    runtime
        .approve(new ApproveCommand(RUN_ID, waiting.candidate(), run().run().runRevision()))
        .collect()
        .asList()
        .await()
        .indefinitely();
  }

  private void appendRecapturedBrief() {
    artifactStore.append(
        new AppendCommand(
            RUN_ID,
            Kind.REQUIREMENT_BRIEF,
            "1",
            "requirement-analysis",
            "1",
            recapturedBrief(),
            List.of(),
            null,
            provenance("requirement-analysis")));
  }

  private RequirementBrief storedBrief() {
    return artifactStore
        .latest(RUN_ID, Kind.REQUIREMENT_BRIEF)
        .map(revision -> artifactStore.payload(revision, RequirementBrief.class))
        .orElseThrow();
  }

  private static String firstSourcePath(RequirementBrief brief) {
    return brief.mappingIntents().getFirst().rules().getFirst().sourcePath();
  }

  private void waitAtMappingGap() {
    runtime
        .startOrResume(new StartOrResumeCommand(CONV_ID, RUN_ID, profile, manifest()))
        .collect()
        .asList()
        .await()
        .indefinitely();
    List<PipelineSignal> afterInput =
        runtime
            .acceptInput(new AcceptInputCommand(RUN_ID, "build a mapped chain"))
            .collect()
            .asList()
            .await()
            .indefinitely();
    PipelineSignal.WaitingForApproval briefWaiting =
        afterInput.stream()
            .filter(PipelineSignal.WaitingForApproval.class::isInstance)
            .map(PipelineSignal.WaitingForApproval.class::cast)
            .findFirst()
            .orElseThrow();
    runtime
        .approve(new ApproveCommand(RUN_ID, briefWaiting.candidate(), run().run().runRevision()))
        .collect()
        .asList()
        .await()
        .indefinitely();
    assertEquals(RunStatus.WAITING_FOR_INPUT, run().run().status());
    assertEquals("design-input", run().run().currentStageId());
    assertEquals(PipelineGates.MAPPING_GAP, PipelineGates.gateOf(latestWaitingPrompt()).orElse(""));
  }

  private void type(String text) {
    support
        .recordInput(new AcceptInputCommand(RUN_ID, text))
        .collect()
        .asList()
        .await()
        .indefinitely();
  }

  private String latestWaitingPrompt() {
    return run().transitions().stream()
        .filter(transition -> transition.toStatus() == RunStatus.WAITING_FOR_INPUT)
        .reduce((first, second) -> second)
        .map(transition -> transition.reason() == null ? "" : transition.reason())
        .orElseThrow();
  }

  private ProductPipelineRunDocument run() {
    return runStore.load(RUN_ID).orElseThrow();
  }

  private ProductPipelineRunSupport newSupport() {
    CompilerRunPinResolver pinResolver = mock(CompilerRunPinResolver.class);
    return ProductPipelineRunSupport.builder(
            runStore,
            artifactStore,
            new StageCapabilityRegistry(
                List.of(
                    analysisCapability(),
                    designInputCapability(),
                    planningCapability(),
                    executionCapability())),
            clock)
        .compilerRunPinResolver(pinResolver)
        .mappingTurnAdapter((brief, message) -> mappingAdapter.interpret(brief, message))
        .build();
  }

  private ArtifactProvenance provenance(String stageId) {
    return new ArtifactProvenance(
        RUN_ID,
        stageId,
        profile.profileId(),
        profile.profileVersion(),
        "profile-sha",
        stageId,
        "1",
        "closure-sha");
  }

  private StageCapability analysisCapability() {
    return new ScriptedCapability(
        "requirement-analysis",
        new StageOutcome(
            StageOutcomeClass.CANDIDATE,
            List.of(new ArtifactCandidate(Kind.REQUIREMENT_BRIEF, uncoveredBrief(), List.of())),
            "brief ready",
            null));
  }

  private StageCapability designInputCapability() {
    return new StageCapability() {
      @Override
      public String capabilityId() {
        return "design-input";
      }

      @Override
      public Multi<CapabilitySignal> execute(StageExecutionContext context) {
        Object value = context.attributes().get("requirementBrief");
        if (!(value instanceof RequirementBrief brief)) {
          return Multi.createFrom()
              .item(
                  new CapabilitySignal.Completed(
                      StageOutcome.of(
                          StageOutcomeClass.MISSING_MANDATORY_INPUT,
                          "design-input requires an approved RequirementBrief")));
        }
        List<Transition> uncovered = MappingGapCoverage.uncovered(brief);
        if (MappingGapCoverage.shouldAsk(uncovered)) {
          String tagged =
              PipelineGates.tag(
                  PipelineGates.MAPPING_GAP,
                  MappingGapWait.encode(
                      null, MappingGapCoverage.readableEdges(uncovered)));
          return Multi.createFrom()
              .item(
                  new CapabilitySignal.Completed(
                      StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, tagged)));
        }
        capturedDesignBrief.set(brief);
        ChainSemanticRevision revision = revisionFromBrief(brief);
        return Multi.createFrom()
            .item(
                new CapabilitySignal.Completed(
                    new StageOutcome(
                        StageOutcomeClass.SUCCEEDED,
                        List.of(
                            new ArtifactCandidate(
                                Kind.CHAIN_SEMANTIC_REVISION, revision, List.of())),
                        "revision ready",
                        null)));
      }
    };
  }

  private StageCapability planningCapability() {
    return new StageCapability() {
      @Override
      public String capabilityId() {
        return "planning";
      }

      @Override
      public Multi<CapabilitySignal> execute(StageExecutionContext context) {
        Object value = context.attributes().get("chainSemanticRevision");
        ChainSemanticRevision revision =
            value instanceof ChainSemanticRevision stored
                ? stored
                : artifactStore
                    .latest(RUN_ID, Kind.CHAIN_SEMANTIC_REVISION)
                    .map(item -> artifactStore.payload(item, ChainSemanticRevision.class))
                    .orElseThrow();
        return Multi.createFrom()
            .item(
                new CapabilitySignal.Completed(
                    new StageOutcome(
                        StageOutcomeClass.CANDIDATE,
                        List.of(
                            new ArtifactCandidate(
                                Kind.DESIGN_EXECUTION_PLAN,
                                planFor(revision.revisionId()),
                                List.of())),
                        "plan ready",
                        null)));
      }
    };
  }

  private StageCapability executionCapability() {
    return new StageCapability() {
      @Override
      public String capabilityId() {
        return "design-execution";
      }

      @Override
      public Multi<CapabilitySignal> execute(StageExecutionContext context) {
        ChainSemanticRevision revision =
            artifactStore
                .latest(RUN_ID, Kind.CHAIN_SEMANTIC_REVISION)
                .map(item -> artifactStore.payload(item, ChainSemanticRevision.class))
                .orElseThrow();
        try {
          executionRunner.execute(
              planFor(revision.revisionId()),
              revision,
              List.of(),
              context.runManifest(),
              context.attemptId(),
              (skillId, status) -> {});
          return Multi.createFrom()
              .item(
                  new CapabilitySignal.Completed(
                      StageOutcome.of(StageOutcomeClass.SUCCEEDED, "created")));
        } catch (RuntimeException thrown) {
          return Multi.createFrom()
              .item(
                  new CapabilitySignal.Completed(
                      StageOutcome.of(StageOutcomeClass.CONTRACT_FAILURE, thrown.getMessage())));
        }
      }
    };
  }

  private DefaultApprovedCompilerExecutionRunner executionRunner() {
    CompilerDagExecutionEngine engine = mock(CompilerDagExecutionEngine.class);
    ChainSemanticGraphCompiler graphCompiler = mock(ChainSemanticGraphCompiler.class);
    when(graphCompiler.compile(any(), any(), any())).thenReturn(engineGraph());
    when(engine.execute(
            any(CompilerDagExecutionRequest.class), any(String.class), any(BiConsumer.class)))
        .thenReturn(Uni.createFrom().item(successfulEngineResult()));
    return new DefaultApprovedCompilerExecutionRunner(
        engine,
        runStore,
        artifactStore,
        graphCompiler,
        new ClasspathCompilerContractRepository());
  }

  private static ChainPlanGraph engineGraph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("chain-1", "Chain"),
        List.of(new ChainPlanNode("trigger", "http-trigger", "Trigger", null, null, List.of())),
        List.of());
  }

  private static CompilerDagExecutionResult successfulEngineResult() {
    ChainPlanGraph graph = engineGraph();
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

  private static DesignExecutionPlan planFor(String semanticRevisionId) {
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

  private static ChainSemanticRevision revisionFromBrief(RequirementBrief brief) {
    List<MappingIntent> intents = brief.mappingIntents();
    List<SemanticNode> nodes = new ArrayList<>();
    List<SemanticExecutionEdge> edges = new ArrayList<>();
    List<MappingIntent> projected = new ArrayList<>();
    nodes.add(
        new SemanticNode.Trigger(
            "task-start", "kafka-trigger-2", new SemanticProvenance(List.of())));
    String previous = "task-start";
    if (intents.isEmpty()) {
      nodes.add(
          new SemanticNode.ServiceCall(
              "create-task", "create-task", "createTask", new SemanticProvenance(List.of())));
      edges.add(
          new SemanticExecutionEdge(
              "edge-0", previous, "create-task", null, new SemanticRoute.Sequence(), null));
    } else {
      for (int index = 0; index < intents.size(); index++) {
        MappingIntent intent = intents.get(index);
        String nodeId = intent.targetRef().isBlank() ? "node-" + index : intent.targetRef();
        nodes.add(
            new SemanticNode.ServiceCall(
                nodeId, nodeId, "op-" + index, new SemanticProvenance(List.of())));
        String edgeId = "edge-" + index;
        SemanticExecutionEdge edge =
            new SemanticExecutionEdge(
                edgeId,
                previous,
                nodeId,
                null,
                new SemanticRoute.Sequence(),
                intent.mappingIntentId());
        edges.add(edge);
        projected.add(ChainSemanticCaptureAdapter.projectOntoCarryingEdge(intent, edge));
        previous = nodeId;
      }
    }
    return new ChainSemanticRevision(
        CONTRACT.semanticSchemaVersion(),
        "semantic-" + Integer.toHexString(brief.goal().hashCode()),
        brief.goal().isBlank() ? "chain" : brief.goal(),
        CONTRACT.contractVersion(),
        List.of(
            new SemanticEntryPoint(
                "entry-1",
                "task-start",
                edges.getFirst().targetNodeId(),
                0,
                new SemanticProvenance(List.of()),
                new SemanticEntryPoint.Presentation("OM", null))),
        nodes,
        List.of(),
        edges,
        List.of(),
        projected,
        List.of(),
        List.of(),
        List.of());
  }

  private static RequirementBrief uncoveredBrief() {
    return new RequirementBrief(
            "OM to Salesforce WFM",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "Uncovered flow transitions",
            "ref",
            "draft",
            List.of())
        .withFlow(mappedFlow());
  }

  private static RequirementBrief recapturedBrief() {
    return new RequirementBrief(
            "Recaptured OM to Salesforce WFM",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "Recaptured after mapping-gap",
            "ref",
            "draft",
            List.of())
        .withFlow(mappedFlow())
        .withMappingIntents(
            List.of(
                new MappingIntent(
                    "map-recapture-1",
                    "task-start",
                    MappingPort.OUTPUT,
                    "create-task",
                    MappingPort.REQUEST,
                    List.of(new MappingIntentRule("$.title", "Subject", null))),
                new MappingIntent(
                    "map-recapture-2",
                    "create-task",
                    MappingPort.OUTPUT,
                    "task-result",
                    MappingPort.REQUEST,
                    List.of(
                        new MappingIntentRule("", "commandType", "Set to completeTask.")))));
  }

  private static RequirementFlow mappedFlow() {
    return new RequirementFlow(
        List.of(
            new Interaction("task-start", Direction.INBOUND, "OM", "onTaskStart", ""),
            new Interaction("create-task", Direction.OUTBOUND, "Salesforce", "createTask", ""),
            new Interaction("task-result", Direction.OUTBOUND, "OM", "onTaskResult", "")),
        List.of(
            new Transition("task-start", "create-task"),
            new Transition("create-task", "task-result")));
  }

  private static MappingTurnAdapter coveringAdapter() {
    return (brief, message) ->
        MappingTurnResult.changes(
            new AddIntent(
                "task-start",
                "create-task",
                List.of(new MappingIntentRule("name", "Subject", null))),
            new AddIntent(
                "create-task",
                "task-result",
                List.of(new MappingIntentRule("", "commandType", "Set to completeTask."))));
  }

  private static ProductPipelineProfile fourStageProfile() {
    ArtifactTypeRef userInput = new ArtifactTypeRef("user-input", 1);
    ArtifactTypeRef brief = new ArtifactTypeRef("requirement-brief", 1);
    ArtifactTypeRef semantic = new ArtifactTypeRef("chain-semantic-revision", 1);
    ArtifactTypeRef plan = new ArtifactTypeRef("design-execution-plan", 1);
    return new ProductPipelineProfile(
        1,
        "test-one-brief-log-tip",
        "1",
        List.of(userInput),
        List.of(
            new ProfileStage(
                "requirement-analysis",
                "requirement-analysis",
                List.of(userInput),
                List.of(brief),
                new ApprovalPolicy(brief, List.of(brief)),
                null,
                new RetryPolicy(0, 1L)),
            new ProfileStage(
                "design-input",
                "design-input",
                List.of(brief),
                List.of(semantic),
                null,
                null,
                new RetryPolicy(0, 1L)),
            new ProfileStage(
                "planning",
                "planning",
                List.of(brief, semantic),
                List.of(plan),
                new ApprovalPolicy(plan, List.of(plan)),
                null,
                new RetryPolicy(0, 1L)),
            new ProfileStage(
                "design-execution",
                "design-execution",
                List.of(semantic, plan),
                List.of(),
                null,
                null,
                new RetryPolicy(0, 1L))),
        new TerminalPolicy("design-execution", "PLAN_APPROVED"),
        List.of("requirement-analysis", "design-input", "planning", "design-execution"));
  }

  private RunManifest manifest() {
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
        profile.profileId(),
        profile.profileVersion(),
        "profile-sha",
        "baseline",
        "baseline-sha",
        List.of(
            new DependencyClosureEntry("requirement-analysis", "1", "c1"),
            new DependencyClosureEntry("design-input", "1", "c2"),
            new DependencyClosureEntry("planning", "1", "c3"),
            new DependencyClosureEntry("design-execution", "1", "c4")),
        "closure-sha",
        new KnowledgePackageRef(
            "knowledge-1", "1", "1.0.0", "checksum", "CERTIFIED", "sha256:certificate"),
        "24.4",
        List.of(new ArtifactTypeRef("user-input", 1)),
        pin);
  }

  private static final class ScriptedCapability implements StageCapability {

    private final String id;
    private final Queue<StageOutcome> outcomes;

    private ScriptedCapability(String id, StageOutcome... outcomes) {
      this.id = id;
      this.outcomes = new ArrayDeque<>(List.of(outcomes));
    }

    @Override
    public String capabilityId() {
      return id;
    }

    @Override
    public Multi<CapabilitySignal> execute(StageExecutionContext context) {
      if ("requirement-analysis".equals(id)) {
        String userText = context.attributeAsString("userText");
        if (userText == null || userText.isBlank()) {
          return Multi.createFrom()
              .item(
                  new CapabilitySignal.Completed(
                      StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, "need user text")));
        }
      }
      StageOutcome outcome =
          outcomes.isEmpty()
              ? StageOutcome.of(StageOutcomeClass.CONTRACT_FAILURE, "no scripted outcome")
              : outcomes.remove();
      return Multi.createFrom().item(new CapabilitySignal.Completed(outcome));
    }
  }
}
